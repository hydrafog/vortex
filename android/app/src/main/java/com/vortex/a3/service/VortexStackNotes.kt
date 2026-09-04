package com.vortex.a3.service

import com.vortex.a3.core.notes.NoteReminderScheduler
import com.vortex.a3.core.notes.NoteStore
import com.vortex.a3.core.notes.NoteSync
import kotlinx.coroutines.launch

 * Wires the notes/todos bidirectional sync (NOTES_SYNC 0x4D) into the BLE stack:
 * routes inbound NOTES_SYNC chunks into the LWW merge ([NoteSync]). BLE-only —
internal fun VortexStack.startNotesSync() {
    NoteStore.init(ctx)
    NoteSync.init(scope)
    NoteSync.sendChunk = { chunk ->
        gattServer?.let { server ->
            for (peer in peerStore.list()) {
                server.sendNotesSyncEncrypted(peer.peerStaticPub, chunk)
            }
        }
    }
    NoteStore.onLocalEdit = { NoteSync.markDirty() }
    // Laptop→phone NOTES_SYNC chunk → reassemble + merge (+ reply if we hold more).
    gattServer?.onNotesSyncReceived = { _, chunk -> NoteSync.onInbound(chunk) }
    scope.launch {
        NoteStore.notes.collect { NoteReminderScheduler.reschedule(ctx, NoteStore.snapshot()) }
    }
}
