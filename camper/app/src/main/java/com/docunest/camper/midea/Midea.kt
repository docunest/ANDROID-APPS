package com.docunest.camper.midea

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Midea V3 LAN protocol (port of msmart-ng), three layers:
 *  - "8370" transport: AES-256-CBC with a session key from a token handshake, SHA-256 checked
 *  - "5A5A" packet: AES-128-ECB with a fixed key, MD5 signed, carries the device ID
 *  - "AA" frame: the actual AC command / state, checksum + CRC-8
 */
object MideaCrypto {
    private val SIGN_KEY = "xhdiwjnchekd4d512chdjx5d8e4c394D2D7S".toByteArray()
    private val ENC_KEY = md5(SIGN_KEY)

    fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun sign(b: ByteArray): ByteArray = md5(b + SIGN_KEY)

    fun ecbEncrypt(b: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(ENC_KEY, "AES")) }.doFinal(b)

    fun ecbDecrypt(b: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(ENC_KEY, "AES")) }.doFinal(b)

    fun cbc(mode: Int, key: ByteArray, b: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16))) }.doFinal(b)

    fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

object MideaPacket {
    fun timestamp(): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val y = c.get(Calendar.YEAR)
        return byteArrayOf(
            (c.get(Calendar.MILLISECOND) / 10).toByte(), c.get(Calendar.SECOND).toByte(), c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(), (c.get(Calendar.MONTH) + 1).toByte(),
            (y % 100).toByte(), (y / 100).toByte(),
        )
    }

    fun encode(deviceId: Long, frame: ByteArray, ts: ByteArray = timestamp()): ByteArray {
        val enc = MideaCrypto.ecbEncrypt(frame)
        val length = 40 + enc.size + 16
        val header = ByteArray(40)
        header[0] = 0x5A; header[1] = 0x5A; header[2] = 0x01; header[3] = 0x11
        header[4] = (length and 0xFF).toByte(); header[5] = (length shr 8).toByte()
        header[6] = 0x20
        ts.copyInto(header, 12)
        for (i in 0 until 8) header[20 + i] = (deviceId shr (8 * i)).toByte()
        val pkt = header + enc
        return pkt + MideaCrypto.sign(pkt)
    }

    fun decode(data: ByteArray): ByteArray {
        require(data.size >= 6 && data[0] == 0x5A.toByte() && data[1] == 0x5A.toByte()) { "not a 5A5A packet" }
        val length = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        require(data.size >= length) { "truncated packet" }
        val pkt = data.copyOf(length)
        require(MideaCrypto.sign(pkt.copyOf(length - 16)).contentEquals(pkt.copyOfRange(length - 16, length))) { "bad packet signature" }
        return MideaCrypto.ecbDecrypt(pkt.copyOfRange(40, length - 16))
    }
}

object MideaV3 {
    private val rnd = SecureRandom()

    private fun header(length: Int, extra: Int) =
        byteArrayOf(0x83.toByte(), 0x70, (length shr 8).toByte(), length.toByte(), 0x20, extra.toByte())

    fun handshake(packetId: Int, token: ByteArray) =
        header(token.size, 0x0) + byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) + token

    fun encodeRequest(localKey: ByteArray, packetId: Int, data: ByteArray, padBytes: ByteArray? = null): ByteArray {
        val rem = (data.size + 2) % 16
        val pad = if (rem != 0) 16 - rem else 0
        val header = header(data.size + pad + 32, (pad shl 4) or 0x6)
        val padding = padBytes?.copyOf(pad) ?: ByteArray(pad).also { rnd.nextBytes(it) }
        val payload = byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) + data + padding
        return header + MideaCrypto.cbc(Cipher.ENCRYPT_MODE, localKey, payload) + MideaCrypto.sha256(header + payload)
    }

    /** Payload of one 8370 packet (type 3 = encrypted response, type 1 = handshake response). */
    fun decode(localKey: ByteArray?, packet: ByteArray): ByteArray {
        require(packet[0] == 0x83.toByte() && packet[1] == 0x70.toByte() && packet[4] == 0x20.toByte()) { "bad 8370 packet" }
        return when (packet[5].toInt() and 0xF) {
            0x1 -> packet.copyOfRange(8, packet.size)
            0x3 -> {
                val dec = MideaCrypto.cbc(Cipher.DECRYPT_MODE, localKey!!, packet.copyOfRange(6, packet.size - 32))
                require(MideaCrypto.sha256(packet.copyOf(6) + dec).contentEquals(packet.copyOfRange(packet.size - 32, packet.size))) { "bad 8370 hash" }
                val pad = (packet[5].toInt() and 0xFF) shr 4
                dec.copyOfRange(2, dec.size - pad)
            }
            0xF -> throw IllegalStateException("AC returned an error packet (token/key?)")
            else -> throw IllegalStateException("unexpected 8370 type")
        }
    }

    fun localKey(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size == 64) { "bad handshake length ${data.size}" }
        val dec = MideaCrypto.cbc(Cipher.DECRYPT_MODE, key, data.copyOf(32))
        require(MideaCrypto.sha256(dec).contentEquals(data.copyOfRange(32, 64))) { "handshake hash mismatch (wrong key?)" }
        return MideaCrypto.xor(dec, key)
    }
}

/** AC command frames and state parsing. */
object AcFrames {
    const val QUERY = 0x03
    const val CONTROL = 0x02
    private var messageId = 0

    fun crc8(b: ByteArray): Int {
        var crc = 0
        for (x in b) {
            crc = crc xor (x.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc
    }

    fun frame(type: Int, data: ByteArray, msgId: Int = (++messageId) and 0xFF): ByteArray {
        val payload = data + byteArrayOf(msgId.toByte())
        val body = payload + byteArrayOf(crc8(payload).toByte())
        val f = ByteArray(10) + body
        f[0] = 0xAA.toByte(); f[1] = (body.size + 10).toByte(); f[2] = 0xAC.toByte(); f[9] = type.toByte()
        var sum = 0
        for (i in 1 until f.size) sum += f[i].toInt() and 0xFF
        return f + byteArrayOf(((sum.inv() + 1) and 0xFF).toByte())
    }

    fun getState(msgId: Int? = null): ByteArray {
        val d = byteArrayOf(0x41, 0x81.toByte(), 0x00, 0xFF.toByte(), 0x03, 0xFF.toByte(), 0x00, 0x02,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x03)
        return if (msgId != null) frame(QUERY, d, msgId) else frame(QUERY, d)
    }

    /** Full set-state frame; fields we don't manage are carried over from the last state (like msmart's apply()). */
    fun setState(s: AcState, msgId: Int? = null): ByteArray {
        val t = s.target
        val integral = t.toInt()
        var temp: Int
        var tempAlt = 0
        if (integral in 17..30) temp = (integral - 16) and 0xF else { temp = 0; tempAlt = (integral - 12) and 0x1F }
        if (t - integral > 0) temp = temp or 0x10
        val d = byteArrayOf(
            0x40,
            (0x2 or (if (s.power) 0x1 else 0)).toByte(), // app control, no beep
            (temp or ((s.mode and 0x7) shl 5)).toByte(),
            s.fan.toByte(),
            0x7F, 0x7F, 0x00,
            (0x30 or (s.swing and 0x3F)).toByte(),
            ((if (s.followMe) 0x80 else 0) or (if (s.turbo) 0x20 else 0)).toByte(),
            ((if (s.eco) 0x80 else 0) or (if (s.purifier) 0x20 else 0)).toByte(),
            ((if (s.sleep) 0x01 else 0) or (if (s.turbo) 0x02 else 0) or (if (s.fahrenheit) 0x04 else 0)).toByte(),
            0, 0, 0, 0, 0, 0, 0,
            tempAlt.toByte(),
            (s.targetHumidity and 0x7F).toByte(),
            0,
            (if (s.freezeProtection) 0x80 else 0).toByte(),
            0, 0,
        )
        return if (msgId != null) frame(CONTROL, d, msgId) else frame(CONTROL, d)
    }

    fun toggleDisplay(): ByteArray = frame(QUERY, byteArrayOf(0x41, 0x02, 0x00, 0xFF.toByte(), 0x02, 0x00, 0x02,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

    private fun temperature(data: Int, decimals: Double, fahrenheit: Boolean): Double? {
        if (data == 0xFF) return null
        val t = (data - 50) / 2.0
        if (!fahrenheit && decimals != 0.0) return t.toInt() + (if (t >= 0) decimals else -decimals)
        if (decimals >= 0.5) return t.toInt() + (if (t >= 0) 0.5 else -0.5)
        return t
    }

    /** Parses a C0 state frame, or null if this frame is something else. */
    fun parseState(frame: ByteArray): AcState? {
        if (frame.size < 13 || frame[0] != 0xAA.toByte()) return null
        val p = frame.copyOfRange(10, frame.size - 2).map { it.toInt() and 0xFF }
        if (p.isEmpty() || p[0] != 0xC0) return null
        val fahrenheit = p[10] and 0x4 != 0
        var target = (p[2] and 0xF) + 16.0 + (if (p[2] and 0x10 != 0) 0.5 else 0.0)
        val alt = p[13] and 0x1F
        if (alt != 0) target = alt + 12.0 + (if (p[2] and 0x10 != 0) 0.5 else 0.0)
        return AcState(
            power = p[1] and 0x1 != 0,
            target = target,
            mode = (p[2] shr 5) and 0x7,
            fan = p[3] and 0x7F,
            swing = p[7] and 0xF,
            turbo = (p[8] and 0x20 != 0) || (p[10] and 0x2 != 0),
            followMe = p[8] and 0x80 != 0,
            eco = p[9] and 0x10 != 0,
            purifier = p[9] and 0x20 != 0,
            sleep = p[10] and 0x1 != 0,
            fahrenheit = fahrenheit,
            indoor = temperature(p[11], (p[15] and 0xF) / 10.0, fahrenheit),
            outdoor = temperature(p[12], (p[15] shr 4) / 10.0, fahrenheit),
            filterAlert = p[13] and 0x20 != 0,
            displayOn = p[14] != 0x70,
            errorCode = p[16],
            targetHumidity = if (p.size >= 20) p[19] and 0x7F else 40,
            freezeProtection = p.size >= 22 && p[21] and 0x80 != 0,
        )
    }
}

data class AcState(
    val power: Boolean, val target: Double, val mode: Int, val fan: Int, val swing: Int = 0,
    val turbo: Boolean = false, val followMe: Boolean = false, val eco: Boolean = false, val purifier: Boolean = false,
    val sleep: Boolean = false, val fahrenheit: Boolean = false, val indoor: Double? = null, val outdoor: Double? = null,
    val filterAlert: Boolean = false, val displayOn: Boolean = true, val errorCode: Int = 0,
    val targetHumidity: Int = 40, val freezeProtection: Boolean = false,
) {
    companion object {
        const val COOL = 2
        const val DRY = 3
        const val FAN_ONLY = 5
        const val FAN_LOW = 40
        const val FAN_HIGH = 80
        const val FAN_AUTO = 102
    }
}

/** One Midea V3 AC: keeps one authenticated TCP connection and re-establishes it on any error. */
class MideaAc(val id: Long, token: String, key: String) {
    private val token = MideaCrypto.hex(token)
    private val key = MideaCrypto.hex(key)
    @Volatile var ip: String? = null
    private var sock: Socket? = null
    private var localKey: ByteArray? = null
    private var authedAt = 0L
    private var packetId = 0
    private val buf = ByteArrayOutputStream()

    @Synchronized
    fun status(): AcState = request(AcFrames.getState()).firstNotNullOfOrNull { AcFrames.parseState(it) }
        ?: throw IllegalStateException("AC sent no state")

    @Synchronized
    fun apply(state: AcState): AcState? = request(AcFrames.setState(state)).firstNotNullOfOrNull { AcFrames.parseState(it) }

    @Synchronized
    fun toggleDisplay() { request(AcFrames.toggleDisplay()) }

    @Synchronized
    fun close() {
        runCatching { sock?.close() }
        sock = null; localKey = null
    }

    private fun request(frame: ByteArray): List<ByteArray> {
        try {
            connect()
            write(MideaV3.encodeRequest(localKey!!, nextId(), MideaPacket.encode(id, frame)))
            val reply = MideaV3.decode(localKey, readPacket())
            return listOf(MideaPacket.decode(reply))
        } catch (e: Exception) {
            close()
            ip = null
            throw e
        }
    }

    private fun connect() {
        // msmart re-authenticates every 12 h; modules also drop idle links, so reconnect after 10 min idle
        if (sock != null && localKey != null && System.currentTimeMillis() - authedAt < 10 * 60_000) return
        close()
        ip?.let { if (runCatching { open(it) }.isSuccess) return }
        // UDP discovery replies don't always reach the Z1; fall back to the hosts with TCP 6444 open.
        // Only our AC accepts our token/key handshake, so a wrong device can't be picked.
        val candidates = listOfNotNull(MideaDiscovery.find(id)) + MideaDiscovery.tcpCandidates()
        for (host in candidates.distinct()) {
            if (runCatching { open(host) }.isSuccess) { ip = host; return }
        }
        throw IllegalStateException("AC not found")
    }

    private fun open(host: String) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, 6444), 5000)
        s.soTimeout = 6000
        sock = s
        buf.reset()
        try {
            write(MideaV3.handshake(nextId(), token))
            localKey = MideaV3.localKey(key, MideaV3.decode(null, readPacket()))
            authedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun nextId(): Int = packetId.also { packetId = (packetId + 1) and 0xFFF }

    private fun write(b: ByteArray) {
        sock!!.getOutputStream().apply { write(b); flush() }
    }

    private fun readPacket(): ByteArray {
        val input = sock!!.getInputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val b = buf.toByteArray()
            val start = (0 until b.size - 1).firstOrNull { b[it] == 0x83.toByte() && b[it + 1] == 0x70.toByte() }
            if (start != null && b.size - start >= 6) {
                val total = (((b[start + 2].toInt() and 0xFF) shl 8) or (b[start + 3].toInt() and 0xFF)) + 8
                if (b.size - start >= total) {
                    buf.reset(); buf.write(b, start + total, b.size - start - total)
                    return b.copyOfRange(start, start + total)
                }
            }
            val n = input.read(chunk)
            if (n < 0) throw IllegalStateException("AC closed the connection")
            buf.write(chunk, 0, n)
        }
    }
}

object MideaDiscovery {
    private val MSG = MideaCrypto.hex(
        "5a5a011148009200000000000000000000000000000000000000000000000000000000000000000000000000" +
            "7f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5")

    /** Device ID in a discovery reply (V2 or V3), or null. */
    fun idOf(data: ByteArray): Long? {
        val inner = if (data.size > 24 && data[0] == 0x83.toByte() && data[1] == 0x70.toByte()) data.copyOfRange(8, data.size - 16) else data
        if (inner.size < 26 || inner[0] != 0x5A.toByte()) return null
        var v = 0L
        for (i in 0 until 6) v = v or ((inner[20 + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Hosts on our /24 with Midea's control port open (parallel connects, ~1 s). */
    fun tcpCandidates(): List<String> {
        val myIp = com.docunest.camper.tuya.Net.localIp() ?: return emptyList()
        val prefix = myIp.substringBeforeLast('.')
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        try {
            val futures = (1..254).map { "$prefix.$it" }.filter { it != myIp }.map { host ->
                pool.submit<String?> {
                    runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, 6444), 700); host } }.getOrNull()
                }
            }
            return futures.mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Broadcast first; the Z1 doesn't always get a 255.255.255.255 broadcast out, so the subnet broadcast and
     * finally a direct packet to every host of the /24 follow (254 x 72 bytes, the AC answers unicast).
     */
    fun find(id: Long, timeoutMs: Long = 4500): String? {
        val myIp = com.docunest.camper.tuya.Net.localIp()
        val prefix = myIp?.substringBeforeLast('.')
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val end = System.currentTimeMillis() + timeoutMs
            var round = 0
            var nextSend = 0L
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    val targets = when {
                        round == 0 -> listOf("255.255.255.255")
                        round == 1 && prefix != null -> listOf("$prefix.255")
                        prefix != null -> (1..254).map { "$prefix.$it" }.filter { it != myIp }
                        else -> listOf("255.255.255.255")
                    }
                    for (t in targets) for (port in intArrayOf(6445, 20086)) runCatching {
                        s.send(DatagramPacket(MSG, MSG.size, InetAddress.getByName(t), port))
                    }
                    round++
                    nextSend = System.currentTimeMillis() + 1500
                }
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (idOf(p.data.copyOf(p.length)) == id) return p.address.hostAddress
            }
        }
        return null
    }
}
