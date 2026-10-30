package io.github.agsola.sublimesync.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes a file so that a crash or power cut leaves either the old or the new
 * content, never an empty file: write a temp file, sync it to disk, rename.
 */
fun writeAtomically(file: File, bytes: ByteArray) {
    val tmp = File(file.path + ".tmp")
    FileOutputStream(tmp).use {
        it.write(bytes)
        it.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw IOException("could not replace $file")
    }
}
