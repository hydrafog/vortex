package com.vortex.a3.core.notes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

object NoteStore {
    private const val FILE = "notes.json"

    private var file: File? = null
    private var all: List<Note> = emptyList()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        all = if (f.exists()) Note.listFromBytes(f.readBytes()) else emptyList()
        publish()
    }

    private fun publish() {
        _notes.value = all.filter { !it.deleted }.sortedByDescending { it.updatedAt }
    }

    private fun persist() {
        file?.let { runCatching { it.writeBytes(Note.listToBytes(all)) } }
    }

    private fun now() = System.currentTimeMillis()

    @Volatile var onLocalEdit: (() -> Unit)? = null

    fun snapshot(): List<Note> = all

    private fun afterLocalEdit() {
        persist(); publish(); onLocalEdit?.invoke()
    }

    fun replaceAll(items: List<Note>) {
        all = items
        persist(); publish()
    }

    fun create(kind: String): Note {
        val n = Note(id = UUID.randomUUID().toString(), kind = kind, updatedAt = now())
        all = all + n
        afterLocalEdit()
        return n
    }

    fun addTodo(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        all = all + Note(id = UUID.randomUUID().toString(), kind = "todo", title = t, updatedAt = now())
        afterLocalEdit()
    }

    fun upsert(note: Note) {
        val stamped = note.copy(updatedAt = now(), deleted = false)
        all = all.filter { it.id != stamped.id } + stamped
        afterLocalEdit()
    }

    fun toggle(id: String, done: Boolean) {
        all = all.map { if (it.id == id) it.copy(done = done, updatedAt = now()) else it }
        afterLocalEdit()
    }

    fun delete(id: String) {
        all = all.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it }
        afterLocalEdit()
    }
}
