package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ [ModelInstaller.fetch] over byte ranges (a tester, 2026-10-13: slow China mirrors).
 * A local server, so every rule is exercised for real: ranges honoured, ranges refused
 * (one connection instead), and a resume from the `.mt.ranges` file.
 * ⚠ A bare [ServerSocket]: the Android unit-test classpath has no `com.sun` HTTP server.
 */
@RunWith(RobolectricTestRunner::class)
class ParallelFetchTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val body = ByteArray(1_000_003) { (it * 31 + it / 7).toByte() }
    private lateinit var server: ServerSocket
    private val ranged = AtomicInteger()
    @Volatile private var honourRanges = true
    private val saved = ModelInstaller.parallelMinBytes

    @Before
    fun up() {
        ModelInstaller.parallelMinBytes = 1L shl 16
        server = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(s) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: java.net.Socket) = s.use {
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val out = s.getOutputStream()
        if (range != null && honourRanges) {
            ranged.incrementAndGet()
            val (a, b) = range.removePrefix("bytes=").split('-')
            val start = a.toInt()
            val end = if (b.isEmpty()) body.size - 1 else b.toInt()
            out.write(
                ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${body.size}\r\n" +
                    "Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(body, start, end - start + 1)
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
        out.flush()
    }

    @After
    fun down() {
        server.close()
        ModelInstaller.parallelMinBytes = saved
    }

    private val url get() = "http://127.0.0.1:${server.localPort}/f"
    private fun dest() = File(ctx.cacheDir, "pf-${System.nanoTime()}.bin")

    @Test
    fun aBigFileArrivesOverFourRanges() {
        val d = dest()
        ModelInstaller.fetch(url, d, body.size.toLong(), "downloading", {})
        assertArrayEquals(body, d.readBytes())
        assertEquals(4, ranged.get())
        assertFalse("no partial left", File(d.path + ".mt").exists() || File(d.path + ".mt.ranges").exists())
    }

    @Test
    fun aServerThatIgnoresRangesGetsOneConnection() {
        honourRanges = false
        val d = dest()
        ModelInstaller.fetch(url, d, body.size.toLong(), "downloading", {})
        assertArrayEquals(body, d.readBytes())
        assertFalse(File(d.path + ".mt").exists())
    }

    @Test
    fun aParallelPartialResumesWhereItStopped() {
        val d = dest()
        val n = body.size.toLong()
        val starts = LongArray(4) { n * it / 4 }
        // Each range half done; the bytes for those halves already in the `.mt` file.
        val done = LongArray(4) { (n * (it + 1) / 4 - starts[it]) / 2 }
        java.io.RandomAccessFile(File(d.path + ".mt"), "rw").use { raf ->
            raf.setLength(n)
            for (i in 0 until 4) {
                raf.seek(starts[i])
                raf.write(body, starts[i].toInt(), done[i].toInt())
            }
        }
        File(d.path + ".mt.ranges").writeText(done.joinToString(" "))
        ModelInstaller.fetch(url, d, n, "downloading", {})
        assertArrayEquals(body, d.readBytes())
        assertEquals("each range asked only for its rest", 4, ranged.get())
    }
}
