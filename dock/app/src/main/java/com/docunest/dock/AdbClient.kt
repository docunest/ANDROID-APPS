package com.docunest.dock

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/**
 * A minimal ADB client (the protocol `adb` speaks to `adbd`), so the dock can
 * talk to the head unit's *own* adbd over TCP (127.0.0.1:5555) and run shell
 * commands — as root on a userdebug build after [root]. That replaces `su`
 * for everything the dock needs root for. Pure JVM (no Android classes), so
 * the protocol is unit-tested against a fake adbd and `adb keygen` keys.
 *
 * The first connection with a new key makes the head unit ask "Allow USB
 * debugging?" — tap *Always allow*; after that it's silent.
 */
class AdbClient(private val keys: KeyPair, private val name: String = "dock@headunit") {
    class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0))

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1
    var maxData = MAX_DATA
        private set
    /** What the device said in its CNXN banner, e.g. "device::ro.product.name=…;". */
    var banner = ""
        private set

    val connected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Connect and authenticate. [onNeedsApproval] is called when the device shows its "Allow USB debugging?"
     * dialog (our key isn't trusted yet); then up to [approvalTimeoutMs] is given for the tap.
     */
    fun connect(host: String = "127.0.0.1", port: Int = 5555, timeoutMs: Int = 5000, approvalTimeoutMs: Int = 60_000, onNeedsApproval: () -> Unit = {}) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
        send(Msg(A_CNXN, VERSION, MAX_DATA, "host::$name\u0000".toByteArray()))
        var signed = false
        while (true) {
            val m = read()
            when (m.cmd) {
                A_CNXN -> {
                    maxData = minOf(MAX_DATA, m.arg1.takeIf { it > 0 } ?: MAX_DATA)
                    banner = String(m.data).trimEnd('\u0000')
                    s.soTimeout = timeoutMs
                    return
                }
                A_AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw IOException("unexpected AUTH type ${m.arg0}")
                    if (!signed) {
                        signed = true
                        send(Msg(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data)))
                    } else {
                        // Key not trusted yet: offer it; the device asks the person.
                        send(Msg(A_AUTH, AUTH_RSAPUBLICKEY, 0, (publicKeyString(keys.public as RSAPublicKey, name) + "\u0000").toByteArray()))
                        onNeedsApproval()
                        s.soTimeout = approvalTimeoutMs
                    }
                }
                else -> throw IOException("unexpected ${cmdName(m.cmd)} during connect")
            }
        }
    }

    /** Run [command] in `shell:`; returns everything it printed (stdout + stderr mixed). */
    fun shell(command: String, timeoutMs: Int = 30_000): String = service("shell:$command", timeoutMs)

    /** Ask adbd to restart as root (userdebug / eng builds). Returns adbd's answer; the connection drops after. */
    fun root(): String = runCatching { service("root:", 10_000) }.getOrElse { "connection dropped (${it.javaClass.simpleName})" }

    fun service(destination: String, timeoutMs: Int): String {
        val s = socket ?: throw IOException("not connected")
        s.soTimeout = timeoutMs
        val local = nextLocalId++
        send(Msg(A_OPEN, local, 0, "$destination\u0000".toByteArray()))
        var remote = 0
        val out = StringBuilder()
        while (true) {
            // adbd may answer and then drop the connection (root: restarts it): keep what came before.
            val m = try {
                read()
            } catch (e: IOException) {
                if (out.isNotEmpty()) return out.toString() else throw e
            }
            when (m.cmd) {
                A_OKAY -> if (m.arg1 == local) remote = m.arg0
                A_WRTE -> if (m.arg1 == local) {
                    remote = m.arg0
                    out.append(String(m.data))
                    send(Msg(A_OKAY, local, remote))
                }
                A_CLSE -> if (m.arg1 == local || m.arg1 == 0) {
                    if (remote != 0) runCatching { send(Msg(A_CLSE, local, remote)) }
                    return out.toString()
                }
                else -> Unit // a stray message for another stream: ignore
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun sign(token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keys.private)
        return c.doFinal(SHA1_DIGEST_INFO + token)
    }

    private fun send(m: Msg) {
        val o = output ?: throw IOException("not connected")
        o.write(encode(m))
        o.flush()
    }

    private fun read(): Msg = decode(input ?: throw IOException("not connected"))

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        /** Version with payload checksums (accepted by every adbd). */
        const val VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024

        /** DER prefix of a SHA-1 DigestInfo: adbd verifies the 20-byte token as if it were a SHA-1 digest. */
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun cmdName(c: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array())

        fun encode(m: Msg): ByteArray {
            val b = ByteBuffer.allocate(24 + m.data.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(m.cmd).putInt(m.arg0).putInt(m.arg1).putInt(m.data.size)
            b.putInt(m.data.sumOf { it.toInt() and 0xff })
            b.putInt(m.cmd xor -1)
            b.put(m.data)
            return b.array()
        }

        fun decode(input: InputStream): Msg {
            val d = if (input is DataInputStream) input else DataInputStream(input)
            val h = ByteArray(24)
            try {
                d.readFully(h)
            } catch (e: EOFException) {
                throw IOException("adbd closed the connection")
            }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int
            val a0 = b.int
            val a1 = b.int
            val len = b.int
            b.int // checksum (not needed over TCP)
            val magic = b.int
            if (magic != cmd xor -1) throw IOException("bad ADB header")
            if (len < 0 || len > 16 * 1024 * 1024) throw IOException("bad ADB length $len")
            val data = ByteArray(len)
            d.readFully(data)
            return Msg(cmd, a0, a1, data)
        }

        /**
         * The public key the way adb stores it in adbkey.pub / sends it in AUTH: base64 of Android's
         * "mincrypt" RSAPublicKey (word count, n0inv, n and R² mod n as little-endian 32-bit words,
         * exponent), then " name".
         */
        fun publicKeyString(pub: RSAPublicKey, name: String): String {
            val words = pub.modulus.bitLength() / 32
            val n = pub.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(words * 32).pow(2).mod(n)
            val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words)
            b.putInt(n0inv.toInt())
            putWords(b, n, words)
            putWords(b, rr, words)
            b.putInt(pub.publicExponent.toInt())
            return Base64.getEncoder().encodeToString(b.array()) + " " + name
        }

        private fun putWords(b: ByteBuffer, v: BigInteger, words: Int) {
            var x = v
            val mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
            repeat(words) {
                b.putInt(x.and(mask).toInt())
                x = x.shiftRight(32)
            }
        }
    }
}
