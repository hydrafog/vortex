package com.vortex.a3.core.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

 * NOTE_REMINDER action + BOOT_COMPLETED.
class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            NoteReminderScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra("id") ?: return
                val title = intent.getStringExtra("title").orEmpty()
                NoteReminderScheduler.fireNotification(ctx, id, title)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                NoteStore.init(ctx)
                NoteReminderScheduler.reschedule(ctx, NoteStore.snapshot())
            }
        }
    }
}
