package com.abrah.nightmare

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * ⭐ A file that READS as [bytes] long and takes no disk — for the tests that need a
 * whole DiT install (`missing()` checks sizes, not contents).
 *
 * ⚠⚠ `RandomAccessFile.setLength` is NOT sparse on Windows: NTFS allocates every byte,
 * so the DiT tests wanted ~18 GB free on the PC and failed "not enough space on the
 * disk" on a nearly full C: (2026-10-08). `SPARSE` with `CREATE_NEW` makes NTFS leave
 * the range unallocated; elsewhere it is ignored and the hole is sparse anyway.
 */
fun sparseFile(f: File, bytes: Long) {
    f.parentFile?.mkdirs()
    f.delete()
    FileChannel.open(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { ch ->
        if (bytes > 0) {
            ch.position(bytes - 1)
            ch.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }
}
