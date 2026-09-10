package com.abrah.nightmare

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * The executor's caching rules, on the JVM.
 *
 * ⚠ These tests are NOT a substitute for the device's four-pass `graph` op.
 * [FakeHost] is content-addressed because the real backend is, and a fake that
 * agreed with the executor about everything would prove only that the executor
 * agrees with itself. What these cover is the SHAPE -- ordering, propagation,
 * refusals -- fast enough to run on every build; what only the device can show
 * is that the real `/handles` and the real handle ids behave as assumed.
 *
 * ⚠ Robolectric with NATIVE graphics, because the decode node now produces a
 * real [Bitmap] and [ImageStore] hashes real pixels. Faking that away would
 * skip the part of the image path most likely to be wrong -- whether two runs
 * that draw the same thing get the same id.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExecutorTest {

    /**
     * A backend that is content-addressed the way the real one is: the same
     * request yields the same handle id, and [restart] forgets every tensor
     * while the ids stay reproducible.
     */
    private class FakeHost : OpHost {
        val resident = mutableSetOf<String>()
        var samples = 0
        var decodes = 0
        var encodes = 0
        var encodesText = 0
        var reachable = true

        override suspend fun residentHandles(): Set<String>? =
            if (reachable) resident.toSet() else null

        var upscales = 0
        /** ⚠ The path the last upscale was asked for, so a test can assert it. */
        var lastUpscalerPath: String? = null

        /**
         * ⚠ Returns a JPEG-shaped nothing: these tests never decode it. What is
         * worth asserting here is that the node reached the host AT ALL with the
         * path it was told to use — an upscaler that is not installed must fail
         * inside the node, before this is called.
         */
        override suspend fun upscale(
            rgb: ByteArray,
            width: Int,
            height: Int,
            upscalerPath: String,
        ): Ops.Result<Ops.Upscaled> {
            upscales++
            lastUpscalerPath = upscalerPath
            return Ops.Result.Ok(
                Ops.Upscaled(ByteArray(0), width * 4, height * 4, 1, 1)
            )
        }

        /** ⚠ The scheduler the last sample was asked for, so a test can assert it. */
        var lastScheduler: String? = null

        override suspend fun sample(
            steps: Int, cfg: Double, seed: Int,
            width: Int, height: Int, latentHandle: String?, denoise: Double,
            scheduler: String, condHandle: String, onProgress: (Ops.Progress) -> Unit,
        ): Ops.Result<Ops.Sampled> {
            samples++
            lastScheduler = scheduler
            // ⚠ The starting latent and the strength are part of the id, as they
            // are in the real backend's key: an img2img render from a different
            // source is a different picture.
            // ⚠⚠ And so is the CONDITIONING, which is now the only thing
            // carrying the prompt. The real backend hashes the cond TENSOR
            // rather than the handle string; here a handle IS its content
            // address, so the two agree.
            val id = "lat_" + listOf(
                condHandle, steps, cfg, seed, width, height,
                latentHandle.orEmpty(), if (latentHandle != null) denoise else 0.0,
                // ⚠ In the key, as it is in the real backend's (`main.cpp`):
                // two runs differing only in scheduler are different pictures,
                // and a fake that collided them would hide exactly that.
                scheduler,
            ).joinToString("|").hashCode().toUInt().toString(16)
            resident += id
            return Ops.Result.Ok(
                Ops.Sampled(id, "sha_$id", steps, seed, 100L, 101L, steps, 5L, 99L)
            )
        }

        /**
         * ⚠ Returns a REAL PNG whose pixels depend on the latent, because the
         * decode node decodes it and the store hashes it. A stub byte array
         * would fail to decode and every test here would be measuring the
         * failure path.
         */
        override suspend fun vaeDecode(
            latentHandle: String, width: Int, height: Int,
        ): Ops.Result<Ops.Decoded> {
            decodes++
            if (latentHandle !in resident) {
                return Ops.Result.Err(400, "unknown latent_handle: $latentHandle")
            }
            val png = solidPng(width, height, latentHandle.hashCode() or 0xFF000000.toInt())
            return Ops.Result.Ok(Ops.Decoded(png, "rgb_$latentHandle", 190L, 195L))
        }

        override suspend fun encodeText(prompt: String, negative: String): Ops.Result<Ops.Cond> {
            encodesText++
            val id = "cond_" + (prompt + "|" + negative).hashCode().toUInt().toString(16)
            resident += id
            return Ops.Result.Ok(Ops.Cond(id, 77, 768, "neg", "pos", 129L, 130L))
        }

        override suspend fun vaeEncode(
            png: ByteArray, seed: Int, width: Int, height: Int,
        ): Ops.Result<Ops.Sampled> {
            encodes++
            val id = "lat_enc_" + listOf(png.size, seed, width, height)
                .joinToString("|").hashCode().toUInt().toString(16)
            resident += id
            return Ops.Result.Ok(Ops.Sampled(id, "sha_$id", 0, seed, 88L, 90L, 0, -1L, -1L))
        }

        var blends = 0

        /**
         * ⚠ Content-addressed on BOTH latents and the mask bytes, as the real
         * one is. A fake that ignored the mask would let a test claim a blend
         * re-ran when only the mask changed while the app cached it away.
         */
        override suspend fun latentBlend(
            a: String, b: String, maskPng: ByteArray,
        ): Ops.Result<Ops.Blended> {
            blends++
            if (a !in resident) return Ops.Result.Err(400, "unknown latent_handle: $a")
            if (b !in resident) return Ops.Result.Err(400, "unknown latent_handle: $b")
            val id = "lat_mix_" + listOf(a, b, maskPng.toList().hashCode())
                .joinToString("|").hashCode().toUInt().toString(16)
            resident += id
            return Ops.Result.Ok(Ops.Blended(id, "sha_$id", "mask_sha", 50L))
        }

        /** What a kill + relaunch does: the ids survive, the tensors do not. */
        fun restart() = resident.clear()
    }



    /**
     * ⭐ The prompt, as the node that now owns it.
     *
     * ⚠ Every graph below needs one: the sampler has no prompt of its own
     * (notes/PROGRESS.md ⑦4) and refuses by name with nothing on `cond`. Most
     * graphs here share ONE, which is both the cheap shape and the one that
     * exercises a COND handle fanning out to two consumers.
     */
    private fun text(id: String = "text", prompt: String = "a cat on grass") =
        Node(id, "sd.clip_encode", mapOf("prompt" to prompt, "negative" to "blurry"))

    private fun sampler(
        id: String,
        seed: Int,
        model: String = "dreamshaper",
        size: Int = 512,
        cond: String = "text",
    ) =
        Node(
            id = id, type = "sd.sample",
            params = mapOf(
                "model" to model,
                "steps" to "8", "cfg" to "7.5", "seed" to seed.toString(),
                "width" to size.toString(), "height" to size.toString(),
            ),
            inputs = sources("cond" to cond),
        )

    private fun decoder(id: String, from: String, model: String = "dreamshaper", size: Int = 512) =
        Node(
            id = id, type = "sd.vae_decode",
            params = mapOf(
                "model" to model, "width" to size.toString(), "height" to size.toString(),
            ),
            inputs = sources("latent" to from),
        )

    private fun twoBranches(seedA: Int, seedB: Int) = Graph(
        listOf(
            text(),
            sampler("sample_a", seedA), decoder("decode_a", "sample_a"),
            sampler("sample_b", seedB), decoder("decode_b", "sample_b"),
        )
    )

    @Test
    fun coldRunRunsEveryNode() = runBlocking {
        val host = FakeHost()
        val r = Executor(host).run(twoBranches(42, 7))
        assertNull(r.error)
        assertEquals(5, r.ran)
        assertEquals(0, r.cached)
        assertEquals(2, host.samples)
        assertEquals(2, host.decodes)
        // ⚠ ONE encode for two samplers. Both read the same conditioning, and a
        // second encode here would mean the cond handle was not being shared.
        assertEquals(1, host.encodesText)
    }

    @Test
    fun identicalSecondRunTouchesTheBackendForNothing() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        ex.run(twoBranches(42, 7))
        val r = ex.run(twoBranches(42, 7))
        assertEquals(0, r.ran)
        assertEquals(5, r.cached)
        assertEquals(2, host.samples)
        assertEquals(2, host.decodes)
    }

    /**
     * ⭐ The propagation case. decode_b's own params do not change; its INPUT
     * does, and that has to be enough to invalidate it.
     */
    @Test
    fun reseedingOneBranchRerunsOnlyThatBranch() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        ex.run(twoBranches(42, 7))
        val r = ex.run(twoBranches(42, 8))
        assertEquals(2, r.ran)
        assertEquals(3, r.cached)
        // ⚠ Keyed by node id, not positional. Kahn's runs BOTH samplers before
        // either decoder (level order, not branch order), and an assertion that
        // encoded the order I assumed rather than the one the algorithm
        // produces would have to be rewritten every time the sort changed.
        assertEquals(
            mapOf(
                "text" to Outcome.CACHED,
                "sample_a" to Outcome.CACHED, "decode_a" to Outcome.CACHED,
                "sample_b" to Outcome.RAN, "decode_b" to Outcome.RAN,
            ),
            r.runs.associate { it.id to it.outcome },
        )
        assertEquals(3, host.samples)
        assertEquals(3, host.decodes)
    }

    /**
     * ⚠⚠ The one an in-app memo cache fails. After a restart the conditioning
     * and the latents are gone, so the text node and the samplers must re-run --
     * but every one of those ops is content-addressed, so the decodes' keys
     * still match and they must NOT.
     */
    @Test
    fun afterARestartTheSamplersRerunAndTheDecodersDoNot() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        ex.run(twoBranches(42, 7))
        host.restart()
        val r = ex.run(twoBranches(42, 7))
        assertEquals(3, r.ran)
        assertEquals(2, r.cached)
        assertTrue("nothing was pruned, so residency was not consulted", r.prunedHandles > 0)
        assertEquals(4, host.samples)
        assertEquals(2, host.encodesText)
        assertEquals(2, host.decodes)   // the decoder was never asked a second time
    }

    /** An unreachable backend is ONE failure, not one per node. */
    @Test
    fun anUnreachableBackendIsReportedOnce() = runBlocking {
        val host = FakeHost().apply { reachable = false }
        val r = Executor(host).run(twoBranches(42, 7))
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("/handles"))
        assertTrue(r.runs.isEmpty())
        assertEquals(0, host.samples)
    }

    /** v1 pins one context key; a second one is refused, not scheduled. */
    @Test
    fun aGraphNeedingTwoBackendContextsIsRefused() = runBlocking {
        val g = Graph(
            listOf(
                text(),
                sampler("sample_a", 42), decoder("decode_a", "sample_a"),
                sampler("sample_b", 7, model = "absolutereality"),
                decoder("decode_b", "sample_b", model = "absolutereality"),
            )
        )
        val host = FakeHost()
        val r = Executor(host).run(g)
        assertNotNull(r.error)
        // ⚠⚠ The message must name the MODELS, not our roadmap. "the process
        // scheduler is v1.1" is true and useless to someone holding a phone:
        // it describes what we have not built rather than what their graph
        // says, and the `model` params are LOCKED, so the mixture can only have
        // come from the app letting the selection drift under a saved graph.
        assertTrue(r.error!!, r.error!!.contains("dreamshaper"))
        assertTrue(r.error!!, r.error!!.contains("absolutereality"))
        assertTrue(r.error!!, r.error!!.contains("open Models"))
        assertEquals(0, host.samples)
    }

    @Test
    fun aResolutionChangeIsAContextChangeToo() = runBlocking {
        val g = Graph(
            listOf(
                text(),
                sampler("sample_a", 42), decoder("decode_a", "sample_a"),
                sampler("sample_b", 7, size = 768), decoder("decode_b", "sample_b", size = 768),
            )
        )
        val r = Executor(FakeHost()).run(g)
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("512x512"))
        assertTrue(r.error!!, r.error!!.contains("768x768"))
    }

    @Test
    fun aCycleIsNamedRatherThanSilentlyTruncated() = runBlocking {
        val g = Graph(
            listOf(
                Node("a", "sd.vae_decode", mapOf("model" to "m", "width" to "512", "height" to "512"),
                    sources("latent" to "b")),
                Node("b", "sd.vae_decode", mapOf("model" to "m", "width" to "512", "height" to "512"),
                    sources("latent" to "a")),
            )
        )
        val r = Executor(FakeHost()).run(g)
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.startsWith("cycle among"))
    }

    @Test
    fun anUnknownNodeTypeIsRefusedBeforeAnythingRuns() = runBlocking {
        val host = FakeHost()
        val r = Executor(host).run(Graph(listOf(Node("x", "upscale"))))
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("unknown type"))
        assertEquals(0, host.samples)
    }

    @Test
    fun anEdgeToAMissingNodeIsRefused() = runBlocking {
        val r = Executor(FakeHost()).run(Graph(listOf(decoder("d", "nowhere"))))
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("unknown node"))
    }

    /** A failing node must not be reported as a successful downstream skip. */
    @Test
    fun downstreamOfAFailureIsBlockedNotCached() = runBlocking {
        val host = object : OpHost by FakeHost() {
            override suspend fun sample(
                steps: Int, cfg: Double, seed: Int,
                width: Int, height: Int, latentHandle: String?, denoise: Double,
                scheduler: String, condHandle: String, onProgress: (Ops.Progress) -> Unit,
            ): Ops.Result<Ops.Sampled> = Ops.Result.Err(500, "QNN execute failed: 1002")
        }
        val g = Graph(listOf(text(), sampler("s", 42), decoder("d", "s")))
        val r = Executor(host).run(g)
        assertEquals(
            listOf(Outcome.RAN, Outcome.FAILED, Outcome.BLOCKED),
            r.runs.map { it.outcome },
        )
        assertEquals(1, r.failed)
        assertTrue(r.error!!, r.error!!.contains("1002"))
    }

/**
     * ⚠⚠ A widget left out of the graph takes its DECLARED DEFAULT, for
     * built-ins as much as for plugins.
     *
     * This used to fail, and the change is deliberate: when only plugin nodes
     * applied their defaults, a built-in gaining a widget broke every graph
     * saved before it existed — `sample` gaining `denoise` failed with
     * `missing param "denoise"` on graphs that predated img2img. A widget with
     * no default anywhere is still refused by name (`PluginTest`).
     */
    @Test
    fun aMissingWidgetTakesItsDeclaredDefault() = runBlocking {
        val bare = sampler("s", 42).let { it.copy(params = it.params - "seed" - "denoise") }
        val r = Executor(FakeHost()).run(Graph(listOf(text(), bare)))
        assertNull(r.error)
        assertEquals(2, r.ran)
    }

    /** ⚠ A value that is present but nonsense still fails, by name. */
    @Test
    fun anUnparseableParamFailsTheGraphByName() = runBlocking {
        val bad = sampler("s", 42).let { it.copy(params = it.params + ("seed" to "not-a-number")) }
        val r = Executor(FakeHost()).run(Graph(listOf(text(), bad)))
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("seed"))
    }

    /** ⚠ The key must not depend on the order the params were written in. */
    @Test
    fun theKeyIsIndependentOfParamOrder() {
        val a = cacheKey("t", "1", linkedMapOf("b" to "2", "a" to "1"), emptyMap())
        val b = cacheKey("t", "1", linkedMapOf("a" to "1", "b" to "2"), emptyMap())
        assertEquals(a, b)
    }

    /** ⚠ …and it must not collide across the delimiter. */
    @Test
    fun theKeySeparatesParamsThatWouldOtherwiseConcatenate() {
        val a = cacheKey("t", "1", mapOf("a" to "1", "b" to "2"), emptyMap())
        val b = cacheKey("t", "1", mapOf("a" to "1b=2"), emptyMap())
        assertTrue(a != b)
    }

    /** A version bump must invalidate everything the old code produced. */
    /** ⭐ Including a plugin version bump: `pack@0.1.0` vs `pack@0.2.0`. */
    @Test
    fun aTypeVersionBumpChangesTheKey() {
        val p = mapOf("a" to "1")
        assertTrue(cacheKey("t", "1", p, emptyMap()) != cacheKey("t", "2", p, emptyMap()))
        assertTrue(
            cacheKey("t", "pack@0.1.0", p, emptyMap()) !=
                cacheKey("t", "pack@0.2.0", p, emptyMap())
        )
    }

    /** The bound is real: an unbounded cache on a long-running device leaks. */
    @Test
    fun theCacheEvictsTheLeastRecentlyUsed() {
        val c = NodeCache(limit = 2)
        c.put("k1", Value.Handle("lat_1", "latent"))
        c.put("k2", Value.Handle("lat_2", "latent"))
        c.get("k1")                                   // k2 is now the oldest
        c.put("k3", Value.Handle("lat_3", "latent"))
        assertEquals(2, c.size)
        assertNotNull(c.get("k1"))
        assertNull(c.get("k2"))
        assertNotNull(c.get("k3"))
    }

    /**
     * ⭐ Image ids are content addresses, and the executor leans on it: after a
     * backend restart the decode node is skipped only because a re-render would
     * have produced the same id anyway.
     */
    @Test
    fun decodingTheSameLatentTwiceYieldsTheSameImageId() = runBlocking {
        val host = FakeHost()
        val a = Executor(host).run(twoBranches(42, 7))
        val b = Executor(host).run(twoBranches(42, 7))   // fresh cache, same backend
        assertEquals(
            (a.outputs["decode_a"] as Value.Image).id,
            (b.outputs["decode_a"] as Value.Image).id,
        )
    }

    /** …and two different renders must NOT collide. */
    @Test
    fun differentSeedsYieldDifferentImageIds() = runBlocking {
        val r = Executor(FakeHost()).run(twoBranches(42, 7))
        assertNotEquals(
            (r.outputs["decode_a"] as Value.Image).id,
            (r.outputs["decode_b"] as Value.Image).id,
        )
    }

    /**
     * ⚠⚠ An image evicted from the store must invalidate the cache entry that
     * names it. Otherwise a cached hit hands a plugin an id that resolves to
     * nothing, and the node fails with "unknown image handle" for a picture the
     * user can see on screen.
     */
    @Test
    fun anEvictedImageIsNotServedFromTheCache() = runBlocking {
        val host = FakeHost()
        val images = ImageStore()
        val ex = Executor(host, images = images)
        ex.run(twoBranches(42, 7))
        images.clear()
        val r = ex.run(twoBranches(42, 7))
        assertEquals("the decoders had to re-run", 2, r.ran)
        assertEquals(3, r.cached)
    }

    /**
     * ⚠⚠ A node with a side effect must run EVERY time. The cache says "this
     * node already produced this result", which is a lie for a node whose
     * result is a file: the second Run would skip it, save nothing, and the user
     * would have pressed the button twice for one picture.
     */
    @Test
    fun aNonCacheableNodeRunsEveryTime() = runBlocking {
        var runs = 0
        val sideEffect = object : NodeType {
            override val name = "sink"
            override val version = "1"
            override val category = "image"
            override val cacheable = false
            override val inputs = listOf(Port("image", "IMAGE"))
            override val outputs = emptyList<Port>()
            override fun contextKey(node: Node) = null
            override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
                runs++
                return inputs.getValue("image")
            }
        }
        val g = Graph(
            listOf(
                text(),
                sampler("s", 42),
                decoder("d", "s"),
                Node("sink", "sink", inputs = sources("image" to "d")),
            )
        )
        val ex = Executor(FakeHost(), types = NODE_TYPES + ("sink" to sideEffect))
        ex.run(g)
        val second = ex.run(g)
        assertEquals("the side-effect node was cached", 2, runs)
        // ⚠ …while everything upstream of it STAYS cached. A non-cacheable node
        // must not poison the nodes that feed it, or every render would be full
        // price for the sake of one file write.
        assertEquals(3, second.cached)
        assertEquals(1, second.ran)
    }

    // --- multi-output wiring ------------------------------------------------

    /**
     * A type with TWO outputs. No shipped node has one yet, which is exactly why
     * the wire format has to be right before one does: a workflow saved against
     * the old "an input names a node" rule cannot be migrated afterwards,
     * because nothing on disk records which output the user meant.
     */
    private val twoOut = object : NodeType {
        override val name = "split"
        override val version = "1"
        override val category = "image"
        override val inputs = listOf(Port("latent", "LATENT"))
        override val outputs = listOf(Port("a", "LATENT"), Port("b", "LATENT"))
        override fun contextKey(node: Node) = null
        override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>) =
            inputs.getValue("latent")
    }

    private fun withSplit() = Executor(FakeHost(), types = NODE_TYPES + ("split" to twoOut))

    /**
     * ⚠⚠ The case the old model could not express. A bare source means "the sole
     * output", so against a two-output node it is AMBIGUOUS — and the executor
     * must say so rather than take the first. Silently taking output `a` would
     * render a picture the user never wired, with nothing anywhere saying it had
     * guessed.
     */
    @Test
    fun aBareWireIntoATwoOutputNodeIsRefusedRatherThanGuessed() = runBlocking {
        val g = Graph(
            listOf(
                text(),
                sampler("s", 1),
                Node("sp", "split", inputs = sources("latent" to "s")),
                decoder("d", "sp"),
            )
        )
        val r = withSplit().run(g)
        val why = r.error ?: ""
        assertTrue(why, "does not say which output" in why)
        assertTrue("names the node", "\"sp\"" in why)
        assertTrue("lists what it has", "a, b" in why)
        assertTrue("nothing ran", r.runs.isEmpty())
    }

    /**
     * …and naming the FIRST output runs, because that is the one a node can
     * still produce. ⚠ This is also the shape the canvas now writes for every
     * ordinary node, so it is the common path rather than a corner.
     */
    @Test
    fun namingTheFirstOutputPortRunsTheGraph() = runBlocking {
        val g = Graph(
            listOf(
                text(),
                sampler("s", 1),
                Node("sp", "split", inputs = sources("latent" to "s:latent")),
                decoder("d", "sp:a"),
            )
        )
        val r = withSplit().run(g)
        assertNull(r.error, r.error)
        assertEquals(4, r.ran)
    }

    /**
     * ⚠⚠ …while a SECOND output is refused, loudly and before anything runs.
     * The format can address it; `NodeType.run` returns one value, so nothing
     * ever fills that port. Without this the graph would sit at BLOCKED
     * "waiting on sp" — a message that names the consumer for a limitation of
     * the producer, and reads like a bug in the wiring the user just drew.
     */
    @Test
    fun aSecondOutputIsRefusedAsUnbuiltRatherThanBlocking() = runBlocking {
        val g = Graph(
            listOf(
                text(),
                sampler("s", 1),
                Node("sp", "split", inputs = sources("latent" to "s")),
                decoder("d", "sp:b"),
            )
        )
        val r = withSplit().run(g)
        val why = r.error ?: ""
        assertTrue(why, "multi-output execution is not built" in why)
        assertTrue(why, "\"b\"" in why)
        assertTrue("nothing ran", r.runs.isEmpty())
    }

    /**
     * ⚠ A port that does not exist is refused by NAME, and before anything runs.
     * Left to the consuming node it would surface as "missing input", blaming
     * the node that read the wire for a mistake made at the other end of it.
     */
    @Test
    fun anUnknownOutputPortIsRefusedByName() = runBlocking {
        val g = Graph(listOf(text(), sampler("s", 1), decoder("d", "s:nope")))
        val r = Executor(FakeHost()).run(g)
        val why = r.error ?: ""
        assertTrue(why, "nope" in why)
        assertTrue(why, "latent" in why)
        assertTrue("nothing ran", r.runs.isEmpty())
    }

    // --- the prompt is a wire ----------------------------------------------

    /**
     * ⚠⚠ A sampler with nothing on `cond` cannot render at all, and it says so
     * with the fix in the sentence.
     *
     * It used to be the ordinary state of a sampler -- the node carried its own
     * prompt and the wire was optional. Now it is a half-drawn graph, and the
     * message has to name the node AND say where the text went, because the
     * knob a user would look for is gone from the inspector.
     */
    @Test
    fun aSamplerWithNoConditioningRefusesByName() = runBlocking {
        val host = FakeHost()
        val g = Graph(listOf(sampler("s", 42).copy(inputs = emptyMap()), decoder("d", "s")))
        val r = Executor(host).run(g)
        val why = r.error ?: ""
        assertTrue(why, "\"s\"" in why)
        assertTrue(why, "cond" in why)
        assertTrue(why, "Text Encode" in why)
        assertEquals("it must not reach the backend", 0, host.samples)
        assertEquals(listOf(Outcome.FAILED, Outcome.BLOCKED), r.runs.map { it.outcome })
    }

    /**
     * ⭐ Two prompts, one seed: the picture must change. This is the property
     * the prompt used to carry into the sampler's own cache key, and after the
     * move it has to arrive entirely through the cond handle -- if it did not,
     * every prompt would render the same latent and nothing else here would
     * notice.
     */
    @Test
    fun changingOnlyThePromptChangesTheLatent() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        val cat = ex.run(Graph(listOf(text(prompt = "a cat"), sampler("s", 42))))
        val dog = ex.run(Graph(listOf(text(prompt = "a dog"), sampler("s", 42))))
        assertNull(cat.error, cat.error)
        assertNull(dog.error, dog.error)
        assertNotEquals(cat.outputs["s"], dog.outputs["s"])
        assertEquals("the sampler was served the cached latent", 2, host.samples)
    }

    /** ⚠ A `:` in an id would be read back as a port. Refused, not truncated. */
    @Test
    fun aColonInANodeIdIsRefused() = runBlocking {
        val r = Executor(FakeHost()).run(Graph(listOf(text(), sampler("a:b", 1))))
        assertTrue(r.error ?: "", "a:b" in (r.error ?: ""))
    }

    /**
     * ⚠⚠ Editing a param through the CANVAS re-runs the node.
     *
     * Reported from a phone as "it won't rerun even when I change seed". The
     * executor's own reseed check (`graph` pass C) builds a fresh Graph, so it
     * could not have caught an edit path that failed to reach the params at all.
     * This one goes through `CanvasState.setParam`, which is what the inspector
     * calls.
     */
    @Test
    fun changingASeedThroughTheInspectorReRunsTheSampler() = runBlocking {
        val wf = com.abrah.nightmare.canvas.Workflow(
            Graph(listOf(text(), sampler("s", 42), decoder("d", "s"))),
            mapOf("text" to com.abrah.nightmare.canvas.Pt(0f, 0f),
                  "s" to com.abrah.nightmare.canvas.Pt(0f, 200f),
                  "d" to com.abrah.nightmare.canvas.Pt(0f, 400f)),
        )
        val ex = Executor(FakeHost())
        val first = ex.run(wf.graph)
        assertEquals(3, first.ran)

        // Exactly what the inspector does when a digit is typed.
        val edited = com.abrah.nightmare.canvas.CanvasState(wf).setParam("s", "seed", "43")
        val second = ex.run(edited.workflow.graph)

        assertEquals("the sampler did not re-run on a new seed", 2, second.ran)
        assertEquals("the conditioning was re-encoded for a seed change", 1, second.cached)
        assertNotEquals(
            "the same seed produced the same latent",
            first.outputs["s"], second.outputs["s"],
        )
    }

    /** …and re-running the SAME graph still caches, so the check above means something. */
    @Test
    fun runningTheSameGraphTwiceStillCaches() = runBlocking {
        val g = Graph(listOf(text(), sampler("s", 42), decoder("d", "s")))
        val ex = Executor(FakeHost())
        ex.run(g)
        val again = ex.run(g)
        assertEquals(3, again.cached)
        assertEquals(0, again.ran)
    }

    /** …and an ordinary node still is cached, so the flag means something. */
    @Test
    fun aCacheableNodeIsStillSkipped() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        ex.run(twoBranches(42, 7))
        assertEquals(5, ex.run(twoBranches(42, 7)).cached)
    }

    /** Client-side pixels survive a prune; server-side handles do not. */
    /**
     * ⚠⚠ The prune predicate covers BOTH stores. A backend handle dies when
     * that process restarts; an image handle dies when the app-side store
     * evicts it. Here the images are alive and the latents are not -- the
     * post-restart case -- and getting the two lifetimes crossed would either
     * throw the cache away or serve an id nobody holds.
     */
    @Test
    fun pruneDropsDeadHandlesAndKeepsLiveImages() {
        val c = NodeCache()
        c.put("h", Value.Handle("lat_1", "latent"))
        c.put("i", Value.Image("img_abc", 512, 512))
        val liveImages = setOf("img_abc")
        assertEquals(1, c.prune { v ->
            when (v) {
                is Value.Handle -> false
                is Value.Image -> v.id in liveImages
            }
        })
        assertNull(c.get("h"))
        assertNotNull(c.get("i"))
    }

    // ---- scheduler -------------------------------------------------------

    /**
     * ⭐⭐ A graph that names no scheduler still SENDS one, and it is the
     * selected model's.
     *
     * ⚠⚠ This is the regression that mattered: the field was never sent at
     * all, so the backend applied its own `dpm` default to every render in the
     * app's history -- including checkpoints published with another sampler.
     * Every saved workflow and both shipped recipes predate the widget and
     * carry no `scheduler` param, so if the default did not flow through
     * `effectiveParams` they would keep silently rendering under whatever the
     * backend chose.
     */
    @Test
    fun aGraphWithNoSchedulerParamStillSendsTheModelDefault() = runBlocking {
        val host = FakeHost()
        Executor(host).run(Graph(listOf(text(), sampler("s", 42), decoder("d", "s"))))
        assertEquals(SelectedModel.spec.scheduler, host.lastScheduler)
        assertNotNull(host.lastScheduler)
    }

    /** ⚠ An explicit choice on the node beats the model's default. */
    @Test
    fun anExplicitSchedulerParamIsSent() = runBlocking {
        val host = FakeHost()
        val s = sampler("s", 42).let { it.copy(params = it.params + ("scheduler" to "euler_a")) }
        Executor(host).run(Graph(listOf(text(), s, decoder("d", "s"))))
        assertEquals("euler_a", host.lastScheduler)
    }

    /**
     * ⭐ Changing ONLY the scheduler re-renders. The backend keys on it
     * (`main.cpp`), and an app cache that did not would serve the picture the
     * other sampler made -- which is the failure mode that hides the whole
     * feature: the user picks Euler A, nothing re-runs, and the image is
     * unchanged.
     */
    @Test
    fun changingOnlyTheSchedulerInvalidatesTheSampler() = runBlocking {
        val host = FakeHost()
        val ex = Executor(host)
        fun g(sched: String) = Graph(
            listOf(
                text(),
                sampler("s", 42).let { it.copy(params = it.params + ("scheduler" to sched)) },
                decoder("d", "s"),
            )
        )
        ex.run(g("dpm"))
        assertEquals(1, host.samples)
        val r = ex.run(g("euler_a"))
        // ⚠ The sampler AND the decoder below it: the decode's own params did
        // not change, but its input latent did.
        assertEquals("sampler and decoder must both re-run", 2, r.ran)
        // ⚠ …and the text encode must NOT. The scheduler is the sampler's knob;
        // invalidating the conditioning too would re-run CLIP on every change
        // to a sampling parameter, which is the cache doing the opposite of
        // its job.
        assertEquals("the conditioning is unaffected and stays cached", 1, r.cached)
        assertEquals("the backend must be asked a second time", 2, host.samples)
        assertEquals("euler_a", host.lastScheduler)
    }

    /** …and an evicted image is dropped just as a dead latent is. */
    @Test
    fun pruneDropsEvictedImages() {
        val c = NodeCache()
        c.put("i", Value.Image("img_gone", 512, 512))
        assertEquals(1, c.prune { false })
        assertNull(c.get("i"))
    }
}

/**
 * A real PNG of one colour. ⚠ Top-level, not a method: [ExecutorTest.FakeHost]
 * is a nested class, not an inner one, so it cannot reach the test's members.
 */
private fun solidPng(w: Int, h: Int, color: Int): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(color)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
