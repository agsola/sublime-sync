package io.github.agsola.sublimesync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { ignoreUnknownKeys = true }

@Serializable
data class Tab(
    val id: Long,
    val window: Long = 0,
    val windowName: String = "",
    val index: Int = 0,
    val title: String = "",
    val path: String? = null,
    val dirty: Boolean = false,
    val syntax: String = "",
    val text: String = "",
    val truncated: Boolean = false,
    /** When the text last changed (unix ms); 0 if unknown. */
    val modified: Long = 0,
)

@Serializable
data class TabRef(val blob: String, val window: Long = 0, val index: Int = 0)

@Serializable
data class Manifest(
    val seq: Long,
    val created: Long,
    val source: String = "plugin",
    val tabs: List<TabRef> = emptyList(),
)

/** Envelope is base64 in JSON (Go's []byte encoding). */
@Serializable
data class SnapshotJson(val seq: Long, val at: Long = 0, val envelope: String)

@Serializable
data class ListItem(val seq: Long, val at: Long)

@Serializable
data class Status(val macOnline: Boolean, val macLastSeen: Long = 0, val latestSeq: Long = 0)

@Serializable
data class Event(val type: String, val seq: Long = 0, val macOnline: Boolean? = null)

@Serializable
data class PairPayload(
    val v: Int,
    @SerialName("k") val key: String,
    @SerialName("s") val server: String,
    @SerialName("m") val mac: String,
) {
    companion object {
        /** Parses and validates a scanned QR code. */
        fun parse(raw: String): PairPayload {
            val p = try {
                json.decodeFromString<PairPayload>(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("not a sublime-sync QR code")
            }
            require(p.v == 1) { "unsupported QR version ${p.v}" }
            require(p.server.startsWith("https://")) { "server must use https" }
            require(p.mac.startsWith("http://") || p.mac.startsWith("https://")) { "bad Mac URL" }
            val key = java.util.Base64.getUrlDecoder().decode(p.key)
            require(key.size == 32) { "bad key" }
            return p
        }
    }
}
