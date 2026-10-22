package com.abrah.nightmare

import java.io.EOFException
import java.io.InputStream

/**
 * ⭐ The one copy of "read N bytes" and "skip N bytes" for every header parser and import.
 *
 * ⚠⚠ NOT `InputStream.readNBytes` / `skipNBytes`: they are API 33 and minSdk is 31. On Android 12 they throw
 * `NoSuchMethodError`, an `Error` that `catch (e: Exception)` does not catch, so an import crashed the app and the
 * "+" sniffer's `runCatching` reported every file as unknown. The JVM tests and the S25 (Android 15) cannot see it,
 * which is why there is ONE helper and no private copies.
 */

/** Reads until [count] bytes are in [into] from [off], or the stream ends; returns how many arrived. */
internal fun InputStream.readUpTo(into: ByteArray, off: Int = 0, count: Int = into.size - off): Int {
    var got = 0
    while (got < count) {
        val n = read(into, off + got, count - got)
        if (n < 0) break
        got += n
    }
    return got
}

/** Up to [count] bytes as a new array (shorter if the stream ends first): `readNBytes(count)` for API 31. */
internal fun InputStream.readUpTo(count: Int): ByteArray {
    val buf = ByteArray(count)
    val got = readUpTo(buf, 0, count)
    return if (got == count) buf else buf.copyOf(got)
}

/** Skips exactly [count] bytes, reading them (a `skip` may return 0 on a pipe); EOF before then throws. */
internal fun InputStream.skipExactly(count: Long, what: String = "the file ends between its tensors") {
    if (count <= 0) return
    var left = count
    val sink = ByteArray(minOf(count, 1L shl 16).toInt())
    while (left > 0) {
        val n = read(sink, 0, minOf(sink.size.toLong(), left).toInt())
        if (n < 0) throw EOFException(what)
        left -= n
    }
}
