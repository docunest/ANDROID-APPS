package com.docunest.dock

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Rebuilds the next APK from the installed one plus a small patch, so an
 * update over the iPhone hotspot costs a few KB instead of the whole APK.
 * Patches are made in CI by `dock/tools/delta.py` (format described there).
 * Any problem throws, and [Updater] falls back to the full download.
 */
object DeltaPatch {
    private val MAGIC = "DKP1".toByteArray()

    /** Writes the new APK to [out]. Throws if the patch is for another APK or the result is wrong. */
    fun apply(old: ByteArray, patch: InputStream, out: OutputStream): Long {
        val head = DataInputStream(BufferedInputStream(patch))
        val magic = ByteArray(4).also { head.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Dock patch")
        val oldSha = ByteArray(32).also { head.readFully(it) }
        val newSha = ByteArray(32).also { head.readFully(it) }
        val newLength = head.readLong()
        if (!sha256(old).contentEquals(oldSha)) throw IOException("Patch is for another build")

        val body = DataInputStream(InflaterInputStream(head))
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var oldPos = 0L
        var written = 0L
        try {
            while (written < newLength) {
                val diff = body.readLong()
                val extra = body.readLong()
                val seek = body.readLong()
                if (diff < 0 || extra < 0 || written + diff + extra > newLength) throw IOException("Bad patch record")
                var left = diff
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    body.readFully(buf, 0, n)
                    for (i in 0 until n) {
                        val o = oldPos + i
                        if (o >= 0 && o < old.size) buf[i] = (buf[i] + old[o.toInt()]).toByte()
                    }
                    out.write(buf, 0, n); digest.update(buf, 0, n)
                    oldPos += n; left -= n
                }
                left = extra
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    body.readFully(buf, 0, n)
                    out.write(buf, 0, n); digest.update(buf, 0, n)
                    left -= n
                }
                oldPos += seek
                written += diff + extra
            }
        } catch (e: EOFException) {
            throw IOException("Patch is cut short", e)
        }
        if (!digest.digest().contentEquals(newSha)) throw IOException("Rebuilt APK doesn't match")
        return written
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
