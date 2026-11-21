package com.docunest.dock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** The app's patcher must rebuild exactly what `tools/delta.py` was given. */
class DeltaPatchTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/delta/$name")!!.readBytes()

    private fun rebuild(old: ByteArray, patch: ByteArray) =
        ByteArrayOutputStream().also { DeltaPatch.apply(old, patch.inputStream(), it) }.toByteArray()

    @Test fun rebuildsTheNewFile() {
        assertArrayEquals(res("new.bin"), rebuild(res("old.bin"), res("new.patch")))
    }

    @Test(expected = IOException::class) fun refusesAnotherBase() {
        rebuild(res("new.bin"), res("new.patch"))
    }

    @Test(expected = IOException::class) fun refusesACutPatch() {
        val p = res("new.patch")
        rebuild(res("old.bin"), p.copyOf(p.size - 50))
    }

    @Test(expected = IOException::class) fun refusesACorruptPatch() {
        val p = res("new.patch").clone()
        p[p.size - 10] = (p[p.size - 10] + 1).toByte()
        rebuild(res("old.bin"), p)
    }

    /** CI: every real patch (old-<n>.apk + dock-from-<n>.patch → dock.apk) before publishing. */
    @Test fun realReleasePatches() {
        val dir = System.getProperty("deltaCheckDir")?.let(::File) ?: return
        val want = File(dir, "dock.apk").readBytes()
        val patches = dir.listFiles { f -> f.name.matches(Regex("dock-from-\\d+\\.patch")) }.orEmpty()
        for (p in patches) {
            val n = p.name.removePrefix("dock-from-").removeSuffix(".patch")
            assertArrayEquals(p.name, want, rebuild(File(dir, "old-$n.apk").readBytes(), p.readBytes()))
        }
        println("Checked ${patches.size} real patch(es)")
        assertTrue(true)
    }
}
