package com.vortex.a3.core.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

 * Bidirectional notes/todos sync (NOTES_SYNC 0x4D) — the phone half of the
object NoteSync {
    private const val CHUNK_DATA = 400

    @Volatile var sendChunk: ((ByteArray) -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var dirtyJob: Job? = null
    private val asm = Assembler()

    fun init(scope: CoroutineScope) {
        this.scope = scope
    }

    fun merge(local: List<Note>, remote: List<Note>): List<Note> {
        val byId = HashMap<String, Note>()
        for (n in local + remote) {
            val cur = byId[n.id]
            if (cur == null || n.updatedAt > cur.updatedAt) byId[n.id] = n
        }
        return byId.values.toList()
    }

    fun sig(items: List<Note>): String =
        items.map { "${it.id}:${it.updatedAt}:${it.deleted}" }.sorted().joinToString("|")

    fun buildChunks(items: List<Note>): List<ByteArray> {
        val json = Note.listToBytes(items)
        val total = ((json.size + CHUNK_DATA - 1) / CHUNK_DATA).coerceAtLeast(1)
        return (0 until total).map { i ->
            val start = i * CHUNK_DATA
            val end = minOf(start + CHUNK_DATA, json.size)
            val data = json.copyOfRange(start, end)
            val f = ByteArray(4 + data.size)
            f[0] = (total ushr 8).toByte(); f[1] = total.toByte()
            f[2] = (i ushr 8).toByte(); f[3] = i.toByte()
            data.copyInto(f, 4)
            f
        }
    }

    private fun parseChunk(p: ByteArray): Triple<Int, Int, ByteArray>? {
        if (p.size < 4) return null
        val total = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val idx = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        return Triple(total, idx, p.copyOfRange(4, p.size))
    }

    private class Assembler {
        private val parts = sortedMapOf<Int, ByteArray>()

        @Synchronized
        fun add(total: Int, idx: Int, data: ByteArray): List<Note>? {
            if (total <= 0 || total > 4096) return null
            parts[idx] = data
            if (parts.size != total) return null
            val buf = parts.values.fold(ByteArray(0)) { a, b -> a + b }
            parts.clear()
            return Note.listFromBytes(buf)
        }
    }

    fun sendFull(items: List<Note>) {
        val s = sendChunk ?: return
        buildChunks(items).forEach { s(it) }
    }

    fun markDirty() {
        val sc = scope ?: return
        dirtyJob?.cancel()
        dirtyJob = sc.launch {
            delay(300)
            sendFull(NoteStore.snapshot())
        }
    }

    /** Handle an inbound NOTES_SYNC chunk: reassemble → LWW merge → persist, and
     *  reply only if WE hold items the peer's set lacked (→ converges). */
    fun onInbound(payload: ByteArray) {
        val (total, idx, data) = parseChunk(payload) ?: return
        val remote = asm.add(total, idx, data) ?: return
        val before = NoteStore.snapshot()
        val merged = merge(before, remote)
        if (sig(merged) != sig(before)) {
            NoteStore.replaceAll(merged)
        }
        if (sig(merged) != sig(remote)) {
            sendFull(merged)
        }
    }
}
