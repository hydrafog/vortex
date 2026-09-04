package com.vortex.a3.core.sms

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsProvider(
    private val context: Context,
    private val onSms: (List<SmsMessage>) -> Unit,
) {
    private val tag = "SmsProvider"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    companion object {
        private const val LIMIT = 80

        private const val EMIT_DEBOUNCE_MS = 2_000L
    }

    fun start() {
        Log.i(tag, "sms: READ_SMS runtime-granted=${hasPermission()}; starting")
        scope.launch { emitSnapshot() }
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleEmit()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI, true, obs,
            )
            observer = obs
        } catch (e: Exception) {
            Log.w(tag, "registerContentObserver: ${e.message}")
        }
    }

    fun stop() {
        observer?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        observer = null
        scope.coroutineContext[Job]?.cancel()
    }

    fun refresh() {
        scope.launch { emitSnapshot() }
    }

    private fun scheduleEmit() {
        pendingEmit?.cancel()
        pendingEmit = scope.launch {
            kotlinx.coroutines.delay(EMIT_DEBOUNCE_MS)
            emitSnapshot()
        }
    }

    private var pendingEmit: Job? = null

    fun loadThread(thread: Long, address: String, offset: Int, limit: Int): List<SmsMessage> {
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.READ,
        )
        val (sel, args) = when {
            thread > 0L -> "${Telephony.Sms.THREAD_ID}=?" to arrayOf(thread.toString())
            address.isNotEmpty() -> "${Telephony.Sms.ADDRESS}=?" to arrayOf(address)
            else -> return emptyList()
        }
        val cap = limit.coerceIn(1, 200)
        val skip = offset.coerceAtLeast(0)
        return querySms(
            cols, sel, args,
            "${Telephony.Sms.DATE} DESC LIMIT $cap OFFSET $skip",
            fallbackAddress = address.trim(),
            logTag = "loadThread",
        )
    }

    fun readHistorySince(sinceMs: Long, limit: Int): List<SmsMessage> {
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.READ,
        )
        val cap = limit.coerceIn(1, 5000)
        return querySms(
            cols,
            "${Telephony.Sms.DATE} > ?",
            arrayOf(sinceMs.toString()),
            "${Telephony.Sms.DATE} ASC LIMIT $cap",
            logTag = "readHistorySince",
        )
    }

    fun readAllIds(): List<String> {
        val out = ArrayList<String>()
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                "${Telephony.Sms._ID} ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                while (c.moveToNext()) {
                    if (idIdx >= 0) out.add(c.getString(idIdx).orEmpty())
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "readAllIds: ${e.message}")
        }
        return out
    }

    private fun querySms(
        cols: Array<String>,
        selection: String,
        args: Array<String>,
        sortOrder: String,
        fallbackAddress: String = "",
        logTag: String,
    ): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"), cols, selection, args, sortOrder,
            )?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                while (c.moveToNext()) {
                    out.add(
                        SmsMessage(
                            id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else "",
                            address = (if (addrIdx >= 0) c.getString(addrIdx) else null)
                                ?.trim().orEmpty().ifEmpty { fallbackAddress },
                            body = (if (bodyIdx >= 0) c.getString(bodyIdx) else null).orEmpty(),
                            type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                            date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                            thread = if (threadIdx >= 0) c.getLong(threadIdx) else 0L,
                            read = if (readIdx >= 0) c.getInt(readIdx) else 1,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "$logTag: ${e.message}")
        }
        return out
    }

    private fun emitSnapshot() {
        try {
            onSms(readSms())
        } catch (e: Exception) {
            Log.w(tag, "emitSnapshot: ${e.message}")
        }
    }

    private fun readSms(): List<SmsMessage> {
        val out = ArrayList<SmsMessage>(LIMIT)
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.READ,
        )
        context.contentResolver.query(
            Uri.parse("content://sms"),
            cols,
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $LIMIT",
        )?.use { c ->
            val idIdx = c.getColumnIndex(Telephony.Sms._ID)
            val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
            val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
            val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
            val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val readIdx = c.getColumnIndex(Telephony.Sms.READ)
            while (c.moveToNext()) {
                if (out.size >= LIMIT) break
                out.add(
                    SmsMessage(
                        id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else "",
                        address = (if (addrIdx >= 0) c.getString(addrIdx) else null)?.trim().orEmpty(),
                        body = (if (bodyIdx >= 0) c.getString(bodyIdx) else null).orEmpty(),
                        type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                        date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        thread = if (threadIdx >= 0) c.getLong(threadIdx) else 0L,
                        read = if (readIdx >= 0) c.getInt(readIdx) else 1,
                    ),
                )
            }
        }
        return out
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED
}
