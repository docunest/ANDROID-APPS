package com.docunest.camper.tuya

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tuya local protocol 3.5 (port of tinytuya). Frames: 0x6699 header, AES-GCM with the header as AAD.
 * A session key is negotiated per TCP connection from two nonces and the device's local key.
 */
object TuyaFrame {
    const val NEG_START = 3
    const val NEG_RESP = 4
    const val NEG_FINISH = 5
    const val STATUS = 8
    const val HEART_BEAT = 9
    const val CONTROL_NEW = 0x0D
    const val DP_QUERY_NEW = 0x10
    const val REQ_DEVINFO = 0x25
    private const val PREFIX = 0x00006699
    private val SUFFIX = byteArrayOf(0, 0, 0x99.toByte(), 0x66)
    private const val HEADER = 18
    private val rnd = SecureRandom()

    /** Tuya's public key for discovery broadcasts. */
    val UDP_KEY: ByteArray = MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray())

    class Frame(val seq: Int, val cmd: Int, val payload: ByteArray) {
        /** Device frames carry a 4-byte return code before the JSON; drop it like tinytuya (no_retcode=None). */
        fun body(): ByteArray =
            if (payload.size >= 4 && (payload.isEmpty() || payload[0] != '{'.code.toByte()) &&
                (payload.size == 4 || payload[4] == '{'.code.toByte())) payload.copyOfRange(4, payload.size)
            else payload

        fun text(): String = String(body(), Charsets.UTF_8).trimEnd('\u0000')
    }

    fun randomIv(): ByteArray = ByteArray(12).also { rnd.nextBytes(it) }

    fun pack(key: ByteArray, seq: Int, cmd: Int, payload: ByteArray, iv: ByteArray = randomIv()): ByteArray {
        val header = ByteBuffer.allocate(HEADER)
            .putInt(PREFIX).putShort(0).putInt(seq).putInt(cmd).putInt(payload.size + 28).array()
        val enc = gcm(Cipher.ENCRYPT_MODE, key, iv, payload, header.copyOfRange(4, HEADER))
        return header + iv + enc + SUFFIX
    }

    /** Parses one frame at [off]; returns the frame and its total length, or null if more bytes are needed. */
    fun unpack(buf: ByteArray, off: Int, len: Int, key: ByteArray): Pair<Frame, Int>? {
        if (len < HEADER) return null
        val bb = ByteBuffer.wrap(buf, off, len)
        val prefix = bb.int
        require(prefix == PREFIX) { "not a 6699 frame: %08x".format(prefix) }
        bb.short
        val seq = bb.int
        val cmd = bb.int
        val bodyLen = bb.int
        require(bodyLen in 28..0x100000) { "bad frame length $bodyLen" }
        val total = HEADER + bodyLen + 4
        if (len < total) return null
        val iv = buf.copyOfRange(off + HEADER, off + HEADER + 12)
        val ct = buf.copyOfRange(off + HEADER + 12, off + HEADER + bodyLen)
        val plain = gcm(Cipher.DECRYPT_MODE, key, iv, ct, buf.copyOfRange(off + 4, off + HEADER))
        return Frame(seq, cmd, plain) to total
    }

    fun sessionKey(realKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
        val x = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        return gcm(Cipher.ENCRYPT_MODE, realKey, localNonce.copyOfRange(0, 12), x, null).copyOfRange(0, 16)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }
}

/** One Tuya 3.5 device. Every call opens a fresh connection (the device allows ~1 client; we never hold it). */
class TuyaDevice(val id: String, localKey: String) {
    private val realKey = localKey.toByteArray(Charsets.UTF_8)
    @Volatile var ip: String? = null

    /** Current data points, e.g. {"1":true,"2":235,...}. */
    fun status(): JSONObject = session { s ->
        s.send(TuyaFrame.DP_QUERY_NEW, "{}".toByteArray())
        val f = s.readUntil { it.cmd == TuyaFrame.DP_QUERY_NEW || it.cmd == TuyaFrame.STATUS }
        dps(JSONObject(f.text()))
    }

    /** Write data points. DP 104 (factory reset, also rotates the key) is refused. */
    fun set(dps: JSONObject) {
        require(!dps.has("104")) { "refusing to write the factory-reset DP" }
        session { s ->
            val body = JSONObject().put("protocol", 5).put("t", System.currentTimeMillis() / 1000)
                .put("data", JSONObject().put("dps", dps))
            s.send(TuyaFrame.CONTROL_NEW, "3.5".toByteArray() + ByteArray(12) + body.toString().toByteArray())
            // ack and/or a status push follow; we only need to know the device took it
            runCatching { s.readUntil { it.cmd == TuyaFrame.CONTROL_NEW || it.cmd == TuyaFrame.STATUS } }
        }
    }

    private fun dps(j: JSONObject): JSONObject = j.optJSONObject("dps") ?: j.optJSONObject("data")?.optJSONObject("dps")
        ?: throw IllegalStateException("no dps in reply")

    private fun <T> session(block: (Session) -> T): T {
        val host = ip ?: TuyaDiscovery.find(id)?.first?.also { ip = it } ?: throw IllegalStateException("floor thermostat not found")
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, 6668), 5000)
                sock.soTimeout = 5000
                val s = Session(sock)
                s.negotiate()
                return block(s)
            }
        } catch (e: Exception) {
            ip = null // re-discover next time (IP may have changed after a power cut)
            throw e
        }
    }

    private inner class Session(sock: Socket) {
        private val input: InputStream = sock.getInputStream()
        private val output = sock.getOutputStream()
        private var seq = 1
        private var key = realKey
        private val buf = ByteArrayOutputStream()

        fun send(cmd: Int, payload: ByteArray) {
            output.write(TuyaFrame.pack(key, seq++, cmd, payload))
            output.flush()
        }

        fun read(): TuyaFrame.Frame {
            val chunk = ByteArray(4096)
            while (true) {
                val b = buf.toByteArray()
                TuyaFrame.unpack(b, 0, b.size, key)?.let { (f, used) ->
                    buf.reset(); buf.write(b, used, b.size - used)
                    return f
                }
                val n = input.read(chunk)
                if (n < 0) throw IllegalStateException("device closed the connection")
                buf.write(chunk, 0, n)
            }
        }

        fun readUntil(match: (TuyaFrame.Frame) -> Boolean): TuyaFrame.Frame {
            repeat(6) {
                val f = read()
                if (match(f) && (f.cmd != TuyaFrame.DP_QUERY_NEW && f.cmd != TuyaFrame.STATUS || f.body().isNotEmpty())) return f
            }
            throw IllegalStateException("no reply")
        }

        fun negotiate() {
            val local = ByteArray(16).also { SecureRandom().nextBytes(it) }
            send(TuyaFrame.NEG_START, local)
            val r = read()
            check(r.cmd == TuyaFrame.NEG_RESP) { "unexpected handshake reply ${r.cmd}" }
            val p = if (r.payload.size >= 52) r.payload.copyOfRange(r.payload.size - 48, r.payload.size) else r.payload
            check(p.size >= 48) { "short handshake reply" }
            val remote = p.copyOfRange(0, 16)
            check(TuyaFrame.hmac(realKey, local).contentEquals(p.copyOfRange(16, 48))) { "wrong local key" }
            send(TuyaFrame.NEG_FINISH, TuyaFrame.hmac(realKey, remote))
            key = TuyaFrame.sessionKey(realKey, local, remote)
        }
    }
}

/** Finds 3.5 devices by ID: they answer an encrypted {"from":"app"} broadcast to UDP 7000. */
object TuyaDiscovery {
    fun request(myIp: String): ByteArray =
        TuyaFrame.pack(TuyaFrame.UDP_KEY, 0, TuyaFrame.REQ_DEVINFO, JSONObject().put("from", "app").put("ip", myIp).toString().toByteArray())

    /** Decrypts a discovery packet to its JSON, or null if it is not one we understand. */
    fun decode(packet: ByteArray, len: Int = packet.size): JSONObject? = runCatching {
        val (f, _) = TuyaFrame.unpack(packet, 0, len, TuyaFrame.UDP_KEY) ?: return null
        JSONObject(f.text())
    }.getOrNull()

    /** (ip, version) of the device, or null after [timeoutMs]. */
    fun find(id: String, timeoutMs: Long = 8000): Pair<String, String>? {
        val sockets = listOf(7000, 6667).mapNotNull { port ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 250
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        }
        try {
            val req = request(Net.localIp() ?: "0.0.0.0")
            val end = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    sockets.firstOrNull()?.let { s ->
                        runCatching { s.send(DatagramPacket(req, req.size, InetAddress.getByName("255.255.255.255"), 7000)) }
                    }
                    nextSend = System.currentTimeMillis() + 2000
                }
                for (s in sockets) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val j = decode(p.data.copyOf(p.length)) ?: continue
                    if (j.optString("gwId") == id || j.optString("id") == id) {
                        return (j.optString("ip").ifEmpty { p.address.hostAddress }) to j.optString("version", "3.5")
                    }
                }
            }
            return null
        } finally {
            sockets.forEach { it.close() }
        }
    }
}

object Net {
    fun localIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
