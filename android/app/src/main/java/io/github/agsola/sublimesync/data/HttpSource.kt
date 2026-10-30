package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.model.Event
import io.github.agsola.sublimesync.model.ListItem
import io.github.agsola.sublimesync.model.SnapshotJson
import io.github.agsola.sublimesync.model.Status
import io.github.agsola.sublimesync.model.json
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP client for one source. Plain http is refused unless the host is a
 * Tailscale address (100.64.0.0/10) or a MagicDNS name (*.ts.net).
 */
class HttpSource(baseUrl: String, private val bearer: String, connectTimeoutMs: Long) : Source {
    private val base = baseUrl.trimEnd('/')

    init {
        require(isAllowedUrl(base)) { "refusing insecure URL $base" }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override suspend fun latest(): SnapshotJson = json.decodeFromString(getString("/v1/snapshots/latest"))
    override suspend fun snapshot(seq: Long): SnapshotJson = json.decodeFromString(getString("/v1/snapshots/$seq"))
    override suspend fun list(before: Long?, limit: Int): List<ListItem> =
        json.decodeFromString(getString("/v1/snapshots?limit=$limit" + (before?.let { "&before=$it" } ?: "")))
    override suspend fun blob(id: String): ByteArray = get("/v1/blobs/$id").use { it.body.bytes() }
    override suspend fun status(): Status = json.decodeFromString(getString("/v1/status"))

    override fun events(): Flow<Event> = reconnecting(1_000, 60_000) { socket() }

    private fun socket(): Flow<Event> = callbackFlow {
        val wsUrl = base.replaceFirst("http", "ws") + "/v1/ws?role=phone"
        val ws = client.newWebSocket(request(wsUrl), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<Event>(text) }.onSuccess { trySend(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { ws.cancel() }
    }

    private suspend fun getString(path: String) = get(path).use { it.body.string() }

    private suspend fun get(path: String): Response {
        val resp = client.newCall(request(base + path)).await()
        if (!resp.isSuccessful) {
            resp.close()
            throw HttpStatusException(resp.code)
        }
        return resp
    }

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $bearer").build()

    companion object {
        fun isAllowedUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return when (uri.scheme) {
                "https" -> true
                "http" -> (uri.host ?: "").let { isTailscaleIp(it) || it.endsWith(".ts.net") }
                else -> false
            }
        }

        fun isTailscaleIp(host: String): Boolean {
            val p = host.split(".").mapNotNull { it.toIntOrNull() }
            return p.size == 4 && p.all { it in 0..255 } && p[0] == 100 && p[1] in 64..127
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
