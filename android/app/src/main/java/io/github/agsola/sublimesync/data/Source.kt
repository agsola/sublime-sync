package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.model.Event
import io.github.agsola.sublimesync.model.ListItem
import io.github.agsola.sublimesync.model.SnapshotJson
import io.github.agsola.sublimesync.model.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One place the phone can read snapshots from: the Mac or the relay. */
interface Source {
    suspend fun latest(): SnapshotJson
    suspend fun snapshot(seq: Long): SnapshotJson
    suspend fun list(before: Long?, limit: Int): List<ListItem>
    suspend fun blob(id: String): ByteArray
    suspend fun status(): Status
    /** Live events; reconnects forever while collected. */
    fun events(): Flow<Event>
}

class HttpStatusException(val code: Int) : Exception("HTTP $code")

/** Re-subscribes to [open] with exponential backoff whenever it fails or ends. */
fun reconnecting(minDelayMs: Long, maxDelayMs: Long, open: () -> Flow<Event>): Flow<Event> = flow {
    var wait = minDelayMs
    while (true) {
        try {
            open().collect {
                wait = minDelayMs
                emit(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        delay(wait)
        wait = minOf(wait * 2, maxDelayMs)
    }
}
