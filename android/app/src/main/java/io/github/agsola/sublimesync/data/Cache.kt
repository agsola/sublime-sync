package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.model.SnapshotJson
import io.github.agsola.sublimesync.model.json
import java.io.File

/**
 * On-disk cache of envelopes exactly as received: still end-to-end encrypted,
 * so reading it needs the key, which needs the user's fingerprint/PIN.
 */
class Cache(private val dir: File, private val maxBytes: Long = 200L shl 20) {
    private val blobs = File(dir, "blobs").apply { mkdirs() }
    private val latestFile = File(dir, "latest.json")
    private val maxSeqFile = File(dir, "maxseq")

    fun blob(id: String): ByteArray? = File(blobs, id).takeIf { it.exists() }?.let {
        it.setLastModified(System.currentTimeMillis())
        it.readBytes()
    }

    fun putBlob(id: String, env: ByteArray) {
        val f = File(blobs, id)
        val tmp = File(blobs, "$id.tmp")
        tmp.writeBytes(env)
        tmp.renameTo(f)
    }

    fun latest(): SnapshotJson? = runCatching { json.decodeFromString<SnapshotJson>(latestFile.readText()) }.getOrNull()

    fun putLatest(s: SnapshotJson, referenced: Set<String>) {
        latestFile.writeText(json.encodeToString(SnapshotJson.serializer(), s))
        trim(referenced)
    }

    /** Highest seq ever shown, to refuse rolled-back data across restarts. */
    var maxSeq: Long
        get() = runCatching { maxSeqFile.readText().toLong() }.getOrDefault(0)
        set(v) = maxSeqFile.writeText(v.toString())

    fun clear() {
        dir.deleteRecursively()
        blobs.mkdirs()
    }

    private fun trim(keep: Set<String>) {
        val files = blobs.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f.name in keep) continue
            total -= f.length()
            f.delete()
        }
    }
}
