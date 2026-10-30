package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.model.CommandJson

/** A sealed, versioned document as stored by the relay or the Mac. */
class StateJson(val version: Long, val envelope: ByteArray?)

sealed interface PutResult {
    data class Ok(val version: Long) : PutResult
    class Conflict(val current: StateJson) : PutResult
}

/** The phone-writable side of the Mac agent and the relay. */
interface CommandApi {
    suspend fun postCommand(id: String, env: ByteArray)
    /** null if this endpoint has never seen the command. */
    suspend fun command(id: String): CommandJson?
    /** null if the document doesn't exist yet. */
    suspend fun getState(name: String): StateJson?
    suspend fun putState(name: String, ifVersion: Long, env: ByteArray): PutResult
}
