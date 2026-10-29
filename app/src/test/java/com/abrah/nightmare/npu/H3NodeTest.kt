package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐ MiniMax H3 ([H3Node]): its frame grid and token count against the measured runs (`docs/MINIMAX-H3.md` §5b, §5d),
 * the limits per chip, the structured prompt the fields build ([H3Prompt]), the soundtrack file ([AudioMux.readNma1])
 * and its catalogue entry (licence, Krea 2's encoder).
 */
class H3NodeTest {

    @Test
    fun framesSitOnTheSeventeenPlusFiveGrid() {
        assertEquals(listOf(22, 56, 73, 90, 124), (1..5).map { H3Node.framesFor(it) })
        for (s in 1..5) assertEquals(0, (H3Node.framesFor(s) - 5) % 17)
        // ⚠ Clamped: 0 s would be a still, which H3 does not make; past 5 s is past 124 frames.
        assertEquals(22, H3Node.framesFor(0))
        assertEquals(124, H3Node.framesFor(9))
        assertEquals(73, WanVideoNode.framesOf(Node("v", H3Node.name, mapOf("seconds" to "3")), mapOf("seconds" to "3")))
        assertEquals(73, WanVideoNode.framesFor(3, WanVideoNode.H3))
        // Wan's own grid is untouched.
        assertEquals(73, WanVideoNode.framesFor(3, WanVideoNode.TURBO))
        assertEquals(121, WanVideoNode.framesFor(5, WanVideoNode.TURBO))
    }

    @Test
    fun tokensMatchTheMeasuredLatents() {
        val h3 = WanVideoNode.H3
        // 256x256 x124 was a 16x16x37 latent; 832x480 x73 ~8,580 tokens; 544x960 x56 ~8,700; 768x1344 x22 7,056.
        assertEquals(37, H3Node.latentFrames(124))
        assertEquals(8 * 8 * 37L, WanVideoNode.tokens(256, 256, 124, h3))
        assertEquals(26 * 15 * 22L, WanVideoNode.tokens(832, 480, 73, h3))
        assertEquals(17 * 30 * 17L, WanVideoNode.tokens(544, 960, 56, h3))
        assertEquals(24 * 42 * 7L, WanVideoNode.tokens(768, 1344, 22, h3))
        // Wan's formula is untouched.
        assertEquals(26 * 15 * 31L, WanVideoNode.tokens(832, 480, 121, WanVideoNode.TURBO))
    }

    @Test
    fun theLengthsAreItsLatentSteps() {
        assertEquals(listOf("0.9", "1.6", "2.3", "3.0", "3.8", "4.5", "5.2"), H3Node.LENGTH_LABELS)
        for (l in H3Node.LENGTH_LABELS) assertEquals(l, H3Node.label(H3Node.framesFor(l.toDouble())))
        assertEquals(56, H3Node.framesOf(emptyMap()))
        assertEquals("2.3", H3Node.widgets.first { it.name == "seconds" }.default)
        // ⭐ The opening clip fits the 8 Gen 3 as well as v79.
        assertTrue(WanVideoNode.fitsTokens(832, 480, H3Node.framesOf(emptyMap()), 75, WanVideoNode.H3))
        assertEquals(H3Node.LENGTH_LABELS, H3Node.widgets.first { it.name == "seconds" }.options)
        assertEquals(39, WanVideoNode.framesOf(Node("v", H3Node.name, mapOf("seconds" to "1.6")), mapOf("seconds" to "1.6")))
    }

    @Test
    fun theLimitsAreTheMeasuredOnes() {
        val h3 = WanVideoNode.H3
        val ram = 12L shl 30
        // ⭐ v79: every row of §5d's list fits, the next length of each does not.
        for ((w, h, f) in listOf(Triple(768, 1344, 22), Triple(1152, 640, 39), Triple(960, 544, 56), Triple(832, 480, 73), Triple(640, 640, 73), Triple(384, 384, 124))) {
            assertTrue("$w x $h x$f on v79", WanVideoNode.fitsPhone(w, h, f, 0, ram, 79, h3))
        }
        assertFalse(WanVideoNode.fitsPhone(768, 1344, 39, 0, ram, 79, h3))
        assertFalse(WanVideoNode.fitsPhone(1152, 640, 56, 0, ram, 79, h3))
        assertFalse(WanVideoNode.fitsPhone(832, 480, 90, 0, ram, 79, h3))
        // ⭐ v75 (the S25 forced): ran 384x384 3 s, 640x640 1.6 s, 544x960 1.6 s, 768x1344 0.9 s; failed 544x960 2.3 s
        // and 832x480 3 s.
        for ((w, h, f) in listOf(Triple(384, 384, 73), Triple(640, 640, 39), Triple(544, 960, 39), Triple(768, 1344, 22))) {
            assertTrue("$w x $h x$f on v75", WanVideoNode.fitsPhone(w, h, f, 0, ram, 75, h3))
        }
        assertFalse(WanVideoNode.fitsPhone(544, 960, 56, 0, ram, 75, h3))
        assertFalse(WanVideoNode.fitsPhone(832, 480, 73, 0, ram, 75, h3))
        // The inspector's advice: the longest length that fits.
        assertEquals("3.0", H3Node.lengthThatFits(832, 480, ram, 79))
        assertEquals("1.6", H3Node.lengthThatFits(1152, 640, ram, 79))
        assertEquals("0.9", H3Node.lengthThatFits(768, 1344, ram, 79))
        assertEquals("5.2", H3Node.lengthThatFits(384, 384, ram, 75))
        assertEquals("2.3", H3Node.lengthThatFits(832, 480, ram, 75))
        // Wan has no token ceiling.
        assertTrue(WanVideoNode.fitsTokens(1280, 704, 121, 79, WanVideoNode.TURBO))
    }

    @Test
    fun itAlwaysStreamsAtTheMeasuredBudgets() {
        val h3 = WanVideoNode.H3
        assertEquals("4.5", WanVideoNode.memoryBudget(WanVideoNode.pinnedBytes(384, 384, 73, h3), 12L shl 30, 79, stream = false, prof = h3))
        assertEquals("4.5", WanVideoNode.memoryBudget(0L, 16L shl 30, 0, stream = false, prof = h3))
        assertEquals("2.75", WanVideoNode.memoryBudget(0L, 12L shl 30, 75, stream = false, prof = h3))
    }

    @Test
    fun sizesStayOnItsGridAndRange() {
        val h3 = WanVideoNode.H3
        assertEquals(384 to 384, WanVideoNode.sizeOf(mapOf("width" to "256", "height" to "300"), h3))
        assertEquals(768 to 1344, WanVideoNode.sizeOf(mapOf("width" to "770", "height" to "1344"), h3))
        // ⭐ Dragging width up at 1344 tall gives way on the height: within 768x1344 worth.
        val (w, h) = WanVideoNode.fitArea(1344, 1344, widthMoved = true, prof = h3)
        assertEquals(1344, w)
        assertTrue(w.toLong() * h <= 768L * 1344 && h % 32 == 0 && h >= 384)
        assertEquals(832 to 480, H3Node.framesTo(Node("v", H3Node.name)))
    }

    @Test
    fun itRunsTheEnginesScheduleWithNoTiling() {
        val h3 = WanVideoNode.H3
        assertTrue(h3.sigmas.isEmpty())
        assertEquals(4, h3.steps)
        assertEquals(0, WanVideoNode.vaeTileFor(1152, 640, h3))
        assertEquals(24, h3.fps)
        assertTrue(h3.sound)
        // The others keep their step counts.
        assertEquals(4, WanVideoNode.TURBO.steps)
        assertEquals(5, WanVideoNode.NSFW13.steps)
        assertFalse(WanVideoNode.TURBO.sound)
    }

    @Test
    fun theNodeHasOnePromptBoxAndAnOptionalPicture() {
        // ⭐ A wired picture is the first frame (H3's keyframe); nothing wired is text to video.
        assertEquals(listOf("prompt", "image"), H3Node.inputs.map { it.name })
        assertEquals("Text to video with sound", H3Node.titleFor(Node("v", H3Node.name)))
        assertEquals("Image to video with sound", H3Node.titleFor(Node("v", H3Node.name, inputs = mapOf("image" to com.abrah.nightmare.Source("img")))))
        val knobs = H3Node.widgets.associateBy { it.name }
        // ⚠ ONE prompt box, the Prompt node's (the user, 2026-10-30): no sentence fields on this node; the framing as Wan's.
        assertEquals(setOf("width", "height", "seed", "seconds", WanVideoNode.SMOOTH, "x", "y", "w", "h") + WanParts.KNOBS, knobs.keys)
        assertEquals(VideoPrompts.H3_I2V, VideoPrompts.forType(H3Node.name, i2v = true))
        assertTrue(com.abrah.nightmare.SdSampler.LORAS !in knobs)
        assertEquals(ModelCatalog.MINIMAX_H3, WanVideoNode.specOf(Node("v", H3Node.name)))
        assertEquals(WanVideoNode.H3, WanVideoNode.profileOf(H3Node.name))
        assertTrue(H3Node.name in com.abrah.nightmare.WAN_TYPES)
        assertTrue(H3Node.name in com.abrah.nightmare.VIDEO_MODEL_TYPES)
        assertTrue(com.abrah.nightmare.NODE_TYPES.containsKey(H3Node.name))
    }

    @Test
    fun theOpeningPromptIsTheStructureH3WasTrainedOn() {
        val p = VideoPrompts.H3
        val sections = listOf("subject_definitions:", "summary:", "retention_analysis:", "detailed_description:", "overall_soundscape:", "non_diegetic_music:")
        assertTrue(sections.all { it in p })
        assertEquals(sections, sections.sortedBy { p.indexOf(it) })
        assertTrue(Regex("<d>\\[English\\] .+</d>").containsMatchIn(p))
        assertTrue(p, "The target video lasts 2.33 seconds." in p)
        assertEquals(VideoPrompts.H3, VideoPrompts.forType(H3Node.name, i2v = false))
    }

    @Test
    fun theStatedLengthFollowsTheClip() {
        // ⭐ The Seconds chip changes the frames; the prompt's own sentence follows (H3 reads it).
        val built = H3Prompt.build(VideoPrompts.H3, frames = 124)
        assertTrue(built, "The target video lasts 5.17 seconds." in built)
        assertFalse("2.33 seconds" in built)
        assertEquals(VideoPrompts.H3.replace("2.33", "0.92"), H3Prompt.build(VideoPrompts.H3, 22))
        // A structured prompt with no length sentence goes through as it is.
        val pasted = "subject_definitions:\nS1 is a cat.\n\nsummary:\nA cat meows."
        assertEquals(pasted, H3Prompt.build(pasted, 22))
    }

    @Test
    fun aPlainPromptIsWrappedWithNoSpeechAndNoMusic() {
        val p = H3Prompt.build("Rain falls on a quiet street at night", frames = 56)
        assertTrue(p, p.startsWith("subject_definitions:\nN/A - no speaking character is defined."))
        assertTrue(p, "The target video lasts 2.33 seconds.\n[Shot 1] Rain falls on a quiet street at night." in p)
        assertTrue(p, "overall_soundscape:\nDiegetic sound only: the natural sound of the scene." in p)
        assertTrue(p, "non_diegetic_music:\nN/A - no score" in p)
        assertFalse("<d>" in p)
    }

    @Test
    fun aLaterPartKeepsTheSpeakerAndSoundOfTheMainPrompt() {
        // ⭐ A plain part line takes the place of [Shot 1]; who speaks, the soundscape and the music carry through.
        val part = H3Prompt.forPart(VideoPrompts.H3, "She waves goodbye and walks out of the café.", frames = 56)
        assertTrue(part, "[Shot 1] She waves goodbye and walks out of the café." in part)
        assertTrue(part, "S1 is a barista in her twenties" in part)
        assertTrue(part, "The hiss of the espresso machine" in part)
        assertFalse(part, "Your flat white" in part)
        // A structured line is its own prompt; a plain main prompt has nothing to carry.
        val own = "subject_definitions:\nS1 is a dog.\n\nsummary:\nA dog barks."
        assertEquals(own, H3Prompt.forPart(VideoPrompts.H3, own, 22))
        assertTrue(H3Prompt.forPart("a beach", "the tide comes in", 22).startsWith("subject_definitions:\nN/A"))
    }

    @Test
    fun partSoundtracksJoinInSync() {
        val dir = java.nio.file.Files.createTempDirectory("h3parts").toFile()
        fun nma1(rate: Int, ch: Int, vararg v: Float): java.io.File {
            val b = ByteBuffer.allocate(20 + v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.put("NMA1".toByteArray()).putInt(rate).putInt(ch).putLong((v.size / ch).toLong())
            v.forEach { b.putFloat(it) }
            return java.io.File(dir, "p${System.nanoTime()}.pcm").apply { writeBytes(b.array()) }
        }
        val into = java.io.File(dir, "all.audio")
        val fmt = java.io.File(dir, "all.fmt")
        // 4 Hz mono at 2 fps: one video frame is 2 samples. Part 2's first frame is dropped, and its sound with it.
        AudioMux.appendPart(nma1(4, 1, 1f, 2f, 3f, 4f), into, fmt, dropFrames = 0, fps = 2)
        AudioMux.appendPart(nma1(4, 1, 9f, 9f, 5f, 6f), into, fmt, dropFrames = 1, fps = 2)
        val out = AudioMux.finishParts(into, fmt, java.io.File(dir, "film.pcm"))!!
        val pcm = AudioMux.readNma1(out.readBytes())!!
        assertEquals(4, pcm.rate)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f).map { (it.coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, pcm.samples.toList())
        // A resumed run trims back to the parts it finished: re-appending part 2 does not double it.
        AudioMux.appendPart(nma1(4, 1, 1f, 2f), into, fmt, 0, 2)
        java.io.File(dir, "x").writeText("")
        fmt.writeText("4 1 2")
        AudioMux.appendPart(nma1(4, 1, 9f, 9f, 5f, 6f), into, fmt, 1, 2)
        assertEquals("4 1 4", fmt.readText())
        dir.deleteRecursively()
    }

    @Test
    fun theSoundtrackFileIsRead() {
        // ⭐ Backend 050's NMA1: "NMA1", int32 rate, int32 channels, int64 frames, f32 interleaved.
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, 2f, -3f)
        val b = ByteBuffer.allocate(20 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("NMA1".toByteArray()).putInt(32000).putInt(2).putLong(3)
        samples.forEach { b.putFloat(it) }
        val pcm = AudioMux.readNma1(b.array())!!
        assertEquals(32000, pcm.rate)
        assertEquals(2, pcm.channels)
        assertEquals(listOf<Short>(0, 16383, -16383, 32767, 32767, -32767), pcm.samples.toList())
        assertEquals(3.0 / 32000, pcm.seconds, 1e-12)
        assertNull(AudioMux.readNma1("NMV1xxxxxxxxxxxxxxxxxxxx".toByteArray()))
        assertNull(AudioMux.readNma1(ByteArray(8)))
    }

    @Test
    fun theCatalogueAsksForTheLicenceAndSharesKrea2sEncoder() {
        val spec = ModelCatalog.MINIMAX_H3
        assertTrue(spec in ModelCatalog.video)
        assertTrue(spec !in ModelCatalog.all)
        assertEquals("h3", spec.backendType)
        assertEquals("krea2_turbo", spec.partsFrom)
        val lic = spec.licence!!
        assertTrue(lic.url.startsWith("https://huggingface.co/MiniMaxAI/MiniMax-H3/"))
        assertEquals(
            setOf("dit.gguf", "vae.gguf", "audio_vae.safetensors", "clipproj.safetensors", "llm.gguf"),
            spec.files.map { it.name }.toSet(),
        )
        assertEquals(11_583_540_608L, spec.files.first { it.name == "dit.gguf" }.bytes)
        assertTrue("AbrahamPJ/fasth3-dense-v1-q4_0-gguf" in spec.files.first { it.name == "dit.gguf" }.url)
        // ⭐ The same encoder file as Krea 2's, so it is borrowed, not fetched twice.
        val krea = ModelCatalog.byId("krea2_turbo")!!.files.first { it.name == "llm.gguf" }
        assertEquals(krea.url, spec.files.first { it.name == "llm.gguf" }.url)
        assertEquals(ModelCatalog.DIT_GGML_MIN_ARCH, spec.builds.minOf { it.minArch })
        // No other model asks.
        assertTrue((ModelCatalog.all + ModelCatalog.video - spec).all { it.licence == null })
    }
}
