package com.vortex.a3.core.storage

import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.identity.Platform
import java.util.concurrent.atomic.AtomicReference

interface IdentityStore {
    fun save(record: IdentityRecord)
    fun load(): IdentityRecord?
    fun forget()
    fun exists(): Boolean = load() != null
}

fun IdentityStore.loadOrGenerate(platform: Platform): IdentityRecord {
    return load() ?: IdentityRecord.generate(platform).also { save(it) }
}

class InMemoryIdentityStore : IdentityStore {
    private val cell = AtomicReference<IdentityRecord?>(null)

    override fun save(record: IdentityRecord) {
        cell.set(record)
    }

    override fun load(): IdentityRecord? = cell.get()

    override fun forget() {
        cell.set(null)
    }
}
