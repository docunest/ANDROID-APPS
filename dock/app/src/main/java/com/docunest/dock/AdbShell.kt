package com.docunest.dock

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.locks.ReentrantLock

/**
 * Root without `su`: the dock talks to the head unit's own adbd on
 * 127.0.0.1:5555 ([AdbClient]) and, on this userdebug build, asks it to run
 * as root — so everything [Root] does works again (forced Wi-Fi rejoin,
 * Bluetooth off/on, grants, GPS chip checks).
 *
 * Needs adbd listening on TCP once (owner's laptop:
 * `adb root; adb shell setprop persist.adb.tcp.port 5555; adb tcpip 5555`)
 * and one tap on the head unit's "Allow USB debugging?" (Always allow) the
 * first time. Never blocks a caller while that dialog is open: the first
 * connection runs on its own thread; until it's done callers get null.
 */
object AdbShell {
    @Volatile var status = "not tried yet"
        private set
    /** null = unknown, true = commands run as root (uid 0), false = as shell (uid 2000). */
    @Volatile var asRoot: Boolean? = null
        private set
    @Volatile var waitingForAllow = false
        private set

    private lateinit var app: Context
    private var client: AdbClient? = null
    private val lock = ReentrantLock()
    @Volatile private var lastFailAt = 0L
    @Volatile private var connecting = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    val ready: Boolean get() = client?.connected == true

    /** Start connecting in the background (dock start); harmless if already connected. */
    fun warmUp() {
        if (!::app.isInitialized || ready || connecting) return
        connecting = true
        Thread {
            try {
                lock.lock()
                try { ensure(allowWait = true) } finally { lock.unlock() }
            } finally {
                connecting = false
            }
        }.start()
    }

    /** Run commands through adb; returns their output if the last one exited 0, else null. Never waits for the Allow dialog. */
    fun output(vararg commands: String, timeoutMs: Int = 30_000): String? {
        if (!::app.isInitialized) return null
        if (!lock.tryLock(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        try {
            val c = ensure(allowWait = false) ?: return null
            val raw = runCatching { c.shell(commands.joinToString("; ") + "; echo __EXIT:\$?", timeoutMs) }.getOrElse {
                c.close()
                client = null
                return null
            }
            val code = Regex("__EXIT:(\\d+)").findAll(raw).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            val text = raw.replace(Regex("\\r?\\n?__EXIT:\\d+\\s*$"), "")
            return if (code == 0) text else null
        } finally {
            lock.unlock()
        }
    }

    fun run(vararg commands: String): Boolean = output(*commands) != null

    /** Caller holds [lock]. */
    private fun ensure(allowWait: Boolean): AdbClient? {
        client?.takeIf { it.connected }?.let { return it }
        if (connecting && !allowWait) return null
        if (SystemClock.elapsedRealtime() - lastFailAt < 60_000) return null // don't hammer a closed port
        return try {
            val c = AdbClient(keys(), "dock@headunit")
            connect(c, allowWait)
            var id = c.shell("id")
            if (!id.contains("uid=0")) {
                // userdebug: adbd may run as root. It restarts — reconnect with the now-trusted key.
                val r = c.root().trim()
                DebugLog.log("ROOT", "adb root: $r")
                if (r.contains("restarting")) {
                    c.close()
                    SystemClock.sleep(2500)
                    connect(c, allowWait)
                    id = c.shell("id")
                }
            }
            asRoot = id.contains("uid=0")
            status = if (asRoot == true) "ADB as root ✓" else "ADB as shell (no root: ${id.trim().take(40)})"
            DebugLog.log("ROOT", "$status · ${c.banner.take(80)}")
            client = c
            c
        } catch (e: Exception) {
            lastFailAt = SystemClock.elapsedRealtime()
            status = if (waitingForAllow) "waiting for Allow on the head unit (or refused)"
            else "no ADB on 127.0.0.1:5555 (${e.javaClass.simpleName}) — needs the one-time setup"
            DebugLog.change("ROOT", "adb", status)
            null
        } finally {
            waitingForAllow = false
        }
    }

    private fun connect(c: AdbClient, allowWait: Boolean) {
        c.connect(approvalTimeoutMs = if (allowWait) 90_000 else 2_000, onNeedsApproval = {
            waitingForAllow = true
            status = "tap “Always allow” on the head unit's USB debugging dialog"
            DebugLog.log("ROOT", status)
        })
    }

    /** One RSA key per install, kept in the app's files (adb trusts it after the first Allow). */
    private fun keys(): KeyPair {
        val dir = File(app.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.x509")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) runCatching {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }
}
