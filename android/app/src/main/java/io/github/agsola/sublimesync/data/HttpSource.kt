package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.model.CommandJson
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import java.util.Base64
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP client for one source. Plain http is refused unless the host is a
 * Tailscale address (100.64.0.0/10) or a MagicDNS name (*.ts.net).
 */
class HttpSource(baseUrl: String, private val bearer: String, connectTimeoutMs: Long) : Source, CommandApi {
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

    override suspend fun postCommand(id: String, env: ByteArray) {
        val body = json.encodeToString(CommandJson.serializer(), CommandJson(id, envelope = b64(env)))
        send(Request.Builder().url("$base/v1/commands").post(body.toRequestBody(JSON))).use {
            if (it.code != 204) throw HttpStatusException(it.code)
        }
    }

    override suspend fun command(id: String): CommandJson? =
        send(Request.Builder().url("$base/v1/commands/$id")).use { resp ->
            when (resp.code) {
                404 -> null
                200 -> json.decodeFromString(CommandJson.serializer(), resp.body.string())
                else -> throw HttpStatusException(resp.code)
            }
        }

    override suspend fun getState(name: String): StateJson? =
        send(Request.Builder().url("$base/v1/state/$name")).use { resp ->
            when (resp.code) {
                404 -> null
                200 -> parseState(resp.body.string())
                else -> throw HttpStatusException(resp.code)
            }
        }

    override suspend fun putState(name: String, ifVersion: Long, env: ByteArray): PutResult =
        send(Request.Builder().url("$base/v1/state/$name").header("If-Match", ifVersion.toString())
            .put(env.toRequestBody(BYTES))).use { resp ->
            when (resp.code) {
                200 -> PutResult.Ok(parseState(resp.body.string()).version)
                409 -> PutResult.Conflict(parseState(resp.body.string()))
                else -> throw HttpStatusException(resp.code)
            }
        }

    @Serializable
    private class WireState(val version: Long, val envelope: String? = null)

    private fun parseState(body: String): StateJson {
        val w = json.decodeFromString(WireState.serializer(), body)
        return StateJson(w.version, w.envelope?.let { Base64.getDecoder().decode(it) })
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private suspend fun send(b: Request.Builder): Response =
        client.newCall(b.header("Authorization", "Bearer $bearer").build()).await()

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
        private val JSON = "application/json".toMediaType()
        private val BYTES = "application/octet-stream".toMediaType()

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
