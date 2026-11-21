package com.docunest.dock

import com.docunest.dock.AdbClient.Companion.A_AUTH
import com.docunest.dock.AdbClient.Companion.A_CLSE
import com.docunest.dock.AdbClient.Companion.A_CNXN
import com.docunest.dock.AdbClient.Companion.A_OKAY
import com.docunest.dock.AdbClient.Companion.A_OPEN
import com.docunest.dock.AdbClient.Companion.A_WRTE
import com.docunest.dock.AdbClient.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * The ADB client against (1) a key made by the real `adb keygen` (test fixture, trusted nowhere) and
 * (2) a fake adbd that does what the real one does: token → signature check → offer key → "Allow?" → CNXN.
 */
class AdbClientTest {
    private fun fixtureKeys(): Pair<KeyPair, String> {
        val pem = javaClass.getResource("/adb/adbkey")!!.readText()
            .lines().filter { !it.startsWith("-----") }.joinToString("")
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))) as RSAPrivateCrtKey
        val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
        return KeyPair(pub, priv) to javaClass.getResource("/adb/adbkey.pub")!!.readText().trim()
    }

    @Test fun publicKeyMatchesAdbKeygenByteForByte() {
        val (keys, pubFile) = fixtureKeys()
        val mine = AdbClient.publicKeyString(keys.public as RSAPublicKey, "x").substringBefore(' ')
        assertEquals(pubFile.substringBefore(' '), mine)
    }

    @Test fun headerRoundTrip() {
        val m = Msg(A_WRTE, 7, 9, "hello".toByteArray())
        val back = AdbClient.decode(encode(m).inputStream())
        assertEquals(A_WRTE, back.cmd)
        assertEquals(7, back.arg0)
        assertEquals("hello", String(back.data))
    }

    private fun encode(m: Msg) = AdbClient.encode(m)

    /** Parses the mincrypt key the client offers (what adbd does with adbkey.pub). */
    private fun parseMincrypt(s: String): RSAPublicKey {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(s.substringBefore(' ').trimEnd('\u0000'))).order(ByteOrder.LITTLE_ENDIAN)
        val words = b.int
        b.int // n0inv
        var n = BigInteger.ZERO
        val parts = IntArray(words) { b.int }
        for (i in words - 1 downTo 0) n = n.shiftLeft(32).or(BigInteger.valueOf(parts[i].toLong() and 0xffffffffL))
        repeat(words) { b.int } // rr
        val e = b.int
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(e.toLong()))) as RSAPublicKey
    }

    /** A fake adbd: trusts keys once "approved", answers shell:/root: like the real one. */
    private inner class FakeAdbd {
        val server = ServerSocket(0)
        val trusted = mutableListOf<RSAPublicKey>()
        var approvals = 0
        var isRoot = false
        val token = ByteArray(20) { it.toByte() }

        fun verify(sig: ByteArray, key: RSAPublicKey): Boolean = runCatching {
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.DECRYPT_MODE, key)
            c.doFinal(sig).contentEquals(AdbClient.SHA1_DIGEST_INFO + token)
        }.getOrDefault(false)

        fun serveOnce() = thread(isDaemon = true) {
            server.accept().use { s ->
                val inp = java.io.DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun send(m: Msg) { out.write(AdbClient.encode(m)); out.flush() }
                var m = AdbClient.decode(inp)
                check(m.cmd == A_CNXN)
                send(Msg(A_AUTH, AdbClient.AUTH_TOKEN, 0, token))
                while (true) {
                    m = AdbClient.decode(inp)
                    if (m.cmd != A_AUTH) return@thread
                    when (m.arg0) {
                        AdbClient.AUTH_SIGNATURE ->
                            if (trusted.any { verify(m.data, it) }) break
                            else send(Msg(A_AUTH, AdbClient.AUTH_TOKEN, 0, token))
                        AdbClient.AUTH_RSAPUBLICKEY -> {
                            approvals++ // the person taps "Always allow"
                            trusted += parseMincrypt(String(m.data))
                            break
                        }
                    }
                }
                send(Msg(A_CNXN, AdbClient.VERSION, 4096, "device::ro.product.name=fake;\u0000".toByteArray()))
                while (true) {
                    m = runCatching { AdbClient.decode(inp) }.getOrNull() ?: return@thread
                    if (m.cmd != A_OPEN) continue
                    val dest = String(m.data).trimEnd('\u0000')
                    val local = m.arg0
                    send(Msg(A_OKAY, 99, local))
                    when {
                        dest == "root:" -> {
                            send(Msg(A_WRTE, 99, local, "restarting adbd as root\n".toByteArray()))
                            isRoot = true
                            return@thread // adbd restarts: connection drops
                        }
                        dest.startsWith("shell:") -> {
                            val cmd = dest.removePrefix("shell:")
                            val reply = if (cmd.startsWith("id")) (if (isRoot) "uid=0(root)" else "uid=2000(shell)") + "\n__EXIT:0\n" else "ran: $cmd\n__EXIT:0\n"
                            reply.chunked(5).forEach { part ->
                                send(Msg(A_WRTE, 99, local, part.toByteArray()))
                                check(AdbClient.decode(inp).cmd == A_OKAY) // client acks every write
                            }
                            send(Msg(A_CLSE, 99, local))
                            AdbClient.decode(inp) // client's CLSE
                        }
                    }
                }
            }
        }
    }

    @Test fun firstConnectAsksOnceThenSignatureIsEnoughAndRootWorks() {
        val (keys, _) = fixtureKeys()
        val adbd = FakeAdbd()
        var asked = 0

        adbd.serveOnce()
        val c = AdbClient(keys)
        c.connect(port = adbd.server.localPort, onNeedsApproval = { asked++ })
        assertEquals(1, asked)
        assertTrue(c.banner.contains("fake"))
        assertEquals("uid=2000(shell)\n__EXIT:0\n", c.shell("id; echo __EXIT:$?"))

        // root: adbd restarts as root and drops us; reconnect with the now-trusted key — no new dialog.
        assertTrue(c.root().contains("restarting adbd as root"))
        adbd.serveOnce()
        c.connect(port = adbd.server.localPort, onNeedsApproval = { asked++ })
        assertEquals(1, asked)
        assertEquals(1, adbd.approvals)
        assertTrue(c.shell("id").startsWith("uid=0(root)"))
        assertTrue(c.shell("svc wifi enable").contains("ran: svc wifi enable"))
        c.close()
    }
}
