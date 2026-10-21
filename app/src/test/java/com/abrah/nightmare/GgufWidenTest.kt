package com.abrah.nightmare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐ A self-converted Klein 9B Q4_0 with `txt_in` and the modulations quantized aborted the engine in `flux.prelude`
 * (TG #6347); [GgufWiden] makes those BF16 at import, the layout of the Q4_0 we ship.
 */
class GgufWidenTest {

    private class T(val name: String, val type: Int, val ne: LongArray, val bytes: ByteArray)

    private fun gguf(tensors: List<T>, alignment: Int = 32): ByteArray {
        val o = ByteArrayOutputStream()
        val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(v: Int) { le.clear(); le.putInt(v); o.write(le.array(), 0, 4) }
        fun u64(v: Long) { le.clear(); le.putLong(v); o.write(le.array(), 0, 8) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); o.write(b) }
        o.write("GGUF".toByteArray()); u32(3); u64(tensors.size.toLong()); u64(2)
        str("general.architecture"); u32(8); str("flux")
        str("general.alignment"); u32(4); u32(alignment)
        var at = 0L
        val offs = tensors.map { t -> val a = (at + alignment - 1) / alignment * alignment; at = a + t.bytes.size; a }
        tensors.forEachIndexed { i, t -> str(t.name); u32(t.ne.size); t.ne.forEach { u64(it) }; u32(t.type); u64(offs[i]) }
        while (o.size() % alignment != 0) o.write(0)
        val start = o.size()
        tensors.forEachIndexed { i, t -> while (o.size() < start + offs[i]) o.write(0); o.write(t.bytes) }
        return o.toByteArray()
    }

    /** One Q4_0 block: d = 0.5, nibbles 0..15 then 15..0. */
    private fun q4Block(): ByteArray = ByteArray(18).also { b ->
        b[0] = 0x00; b[1] = 0x38 // fp16 0.5
        for (j in 0 until 16) b[2 + j] = ((j and 0xF) or ((15 - j) shl 4)).toByte()
    }

    /** One Q8_0 block: d = 2.0, values -16..15. */
    private fun q8Block(): ByteArray = ByteArray(34).also { b ->
        b[0] = 0x00; b[1] = 0x40 // fp16 2.0
        for (j in 0 until 32) b[2 + j] = (j - 16).toByte()
    }

    private fun widen(src: ByteArray, family: Family?): Pair<ByteArray, GgufWiden.Result> {
        val out = ByteArrayOutputStream()
        val r = GgufWiden.copy(src.inputStream(), out, family)
        return out.toByteArray() to r
    }

    private fun bf16(b: ByteArray, at: Int, n: Int) = FloatArray(n) {
        Float.fromBits(((b[at + 2 * it].toInt() and 0xFF) or ((b[at + 2 * it + 1].toInt() and 0xFF) shl 8)) shl 16)
    }

    private val tensors = listOf(
        T("txt_in.weight", 2, longArrayOf(64, 2), q4Block() + q4Block() + q4Block() + q4Block()),
        T("double_blocks.0.img_attn.qkv.weight", 2, longArrayOf(64, 2), q4Block() + q4Block() + q4Block() + q4Block()),
        T("double_blocks.0.img_attn.norm.query_norm.scale", 8, longArrayOf(32), q8Block()),
        T("img_in.bias", 0, longArrayOf(3), ByteArray(12) { it.toByte() }),
    )

    @Test
    fun kleinsOutsideTensorsAndNormScalesBecomeBf16() {
        val (out, r) = widen(gguf(tensors), Family.FLUX2)
        assertEquals(2, r.widened)
        val t = GgufHeader.parse(out.copyOf(minOf(out.size, 1 shl 20)))
        assertEquals("BF16", t.typeOf["txt_in.weight"])
        assertEquals("Q4_0", t.typeOf["double_blocks.0.img_attn.qkv.weight"])
        assertEquals("BF16", t.typeOf["double_blocks.0.img_attn.norm.query_norm.scale"])
        assertEquals("F32", t.typeOf["img_in.bias"])
        // ⭐ The file is whole by its own header, the check every installed GGUF goes through.
        val (end, align) = GgufHeader.dataEnd(out)!!
        assertTrue(out.size.toLong() == end || out.size.toLong() == (end + align - 1) / align * align)
    }

    @Test
    fun theValuesAreDequantizedAndTheRestIsByteForByte() {
        val (out, _) = widen(gguf(tensors), Family.FLUX2)
        val offsets = offsetsOf(out)
        // Q4_0: (q - 8) * 0.5, first half nibbles 0..15, second half 15..0.
        val txt = bf16(out, offsets.getValue("txt_in.weight"), 128)
        for (j in 0 until 16) {
            assertEquals((j - 8) * 0.5f, txt[j])
            assertEquals((15 - j - 8) * 0.5f, txt[j + 16])
        }
        assertEquals(txt.copyOfRange(0, 32).toList(), txt.copyOfRange(96, 128).toList())
        val scale = bf16(out, offsets.getValue("double_blocks.0.img_attn.norm.query_norm.scale"), 32)
        for (j in 0 until 32) assertEquals((j - 16) * 2f, scale[j])
        val qkv = offsets.getValue("double_blocks.0.img_attn.qkv.weight")
        assertArrayEquals(tensors[1].bytes, out.copyOfRange(qkv, qkv + tensors[1].bytes.size))
        val bias = offsets.getValue("img_in.bias")
        assertArrayEquals(tensors[3].bytes, out.copyOfRange(bias, bias + 12))
    }

    @Test
    fun anotherFamilyWidensOnlyItsNormScalesAndAPlainFileIsCopiedAsItIs() {
        val (out, r) = widen(gguf(tensors), Family.ZIMAGE)
        assertEquals(1, r.widened)
        assertEquals("Q4_0", GgufHeader.parse(out.copyOf(minOf(out.size, 1 shl 20))).typeOf["txt_in.weight"])
        val plain = gguf(tensors.filter { it.name.startsWith("double_blocks.0.img_attn.qkv") || it.name == "img_in.bias" })
        val (same, r2) = widen(plain, Family.FLUX2)
        assertEquals(0, r2.widened)
        assertArrayEquals(plain, same)
    }

    @Test
    fun aKQuantThereIsLeftAndNamed() {
        val k = T("txt_in.weight", 12, longArrayOf(256, 1), ByteArray(144))
        val (_, r) = widen(gguf(listOf(k)), Family.FLUX2)
        assertEquals(0, r.widened)
        assertEquals(listOf("txt_in.weight (Q4_K)"), r.left)
    }

    @Test
    fun bf16RoundsToNearestEven() {
        assertEquals(0x3F80, GgufWiden.toBf16(1f))
        assertEquals(0x3F80, GgufWiden.toBf16(Float.fromBits(0x3F808000))) // a tie, to even
        assertEquals(0x3F82, GgufWiden.toBf16(Float.fromBits(0x3F818000))) // a tie, to even (up)
        assertEquals(0x3F81, GgufWiden.toBf16(Float.fromBits(0x3F808001)))
    }

    /** Each tensor's absolute data offset in a written file. */
    private fun offsetsOf(b: ByteArray): Map<String, Int> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val n = bb.long; val kvs = bb.long
        var align = 32
        repeat(kvs.toInt()) {
            val key = GgufHeader.readString(bb); val type = bb.int
            if (key == "general.alignment" && type == 4) align = bb.int else GgufHeader.skipValue(bb, type)
        }
        val rel = LinkedHashMap<String, Long>()
        repeat(n.toInt()) {
            val name = GgufHeader.readString(bb); val nd = bb.int
            repeat(nd) { bb.long }; bb.int
            rel[name] = bb.long
        }
        val start = (bb.position() + align - 1) / align * align
        return rel.mapValues { (start + it.value).toInt() }
    }
}
