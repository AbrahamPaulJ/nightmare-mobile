package com.abrah.nightmare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP client for the forked C++ backend.
 *
 * ⚠ The transport is deliberately boring. Per-op HTTP was MEASURED at ~70 µs
 * loopback RTT and ~175 µs carrying a latent -- 0.2% of a render
 * (docs/ARCHITECTURE.md section 3) -- so there is nothing to optimise here and
 * no reason to take a dependency on a client library.
 *
 * ⚠ What is NOT cheap is the process model: --type, --model_dir and --patch are
 * bound at backend launch, so changing checkpoint or resolution costs a kill +
 * relaunch of 2.3-5 s. v1 sidesteps it by pinning one context key (section 5.2);
 * this class must never grow a "just switch the model" convenience method.
 */
object Backend {

    /**
     * ⚠ 8189, NOT DreamUI's 8085. DreamUI may be resident on 8085 with its own
     * backend, and silently talking to the wrong server would look exactly like
     * success -- the endpoints it does share (/health) answer identically.
     * `tools/op_smoke.sh` uses the same port for the same reason.
     */
    const val PORT = 8189
    private const val BASE = "http://127.0.0.1:$PORT"

    /** Milliseconds. Generous: a cold backend takes 4-5 s to answer /health. */
    private const val TIMEOUT_MS = 6_000

    /**
     * Milliseconds BETWEEN stream frames, not for the whole response. A sample
     * emits one every ~130 ms; the slack is for the first frame, which waits on
     * CLIP, and for a cold NPU step.
     */
    private const val SSE_READ_TIMEOUT_MS = 30_000

    data class Response(val code: Int, val body: String, val millis: Long)

    suspend fun get(path: String): Response = request("GET", path, null)

    suspend fun post(path: String, json: String): Response = request("POST", path, json)

    /**
     * A binary response, for `/vae_decode?binary=1`.
     *
     * ⚠ Header names are lowercased on the way in. HTTP header names are
     * case-insensitive and cpp-httplib does not promise a casing, so a lookup
     * for "X-Rgb-Sha" would work until the day it silently did not.
     */
    data class BinaryResponse(
        val code: Int,
        val bytes: ByteArray,
        val text: String,
        val headers: Map<String, String>,
        val millis: Long,
    )

    suspend fun postForBytes(path: String, json: String): BinaryResponse =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(json.toByteArray()) }
                val code = conn.responseCode
                val heads = conn.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() }
                if (code >= 400) {
                    val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    BinaryResponse(code, ByteArray(0), err, heads,
                        (System.nanoTime() - started) / 1_000_000)
                } else {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    BinaryResponse(code, bytes, "", heads,
                        (System.nanoTime() - started) / 1_000_000)
                }
            } catch (e: Exception) {
                BinaryResponse(-1, ByteArray(0),
                    e.javaClass.simpleName + ": " + (e.message ?: "no message"),
                    emptyMap(), (System.nanoTime() - started) / 1_000_000)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * ⭐ Raw bytes up, raw bytes down, with headers carrying the metadata.
     *
     * ⚠⚠ `/upscale` is the one endpoint on this server that is NOT JSON in
     * either direction: the body is uncompressed RGB and the reply is a JPEG,
     * with width/height/duration in `X-` headers. Base64 would inflate a 1024²
     * RGB frame's 3 MB to 4 MB for a loopback socket that moves 1 MB in ~1.3 ms
     * (`docs/ARCHITECTURE.md` §3), which is why it was written that way
     * upstream and why it stays that way here.
     *
     * ⚠ `setFixedLengthStreamingMode` so a 3 MB body is streamed rather than
     * buffered into the connection's own array first.
     */
    suspend fun postBytes(
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
        contentType: String = "application/octet-stream",
        /**
         * ⚠⚠ SEPARATE from the connect timeout, and it has to be. [TIMEOUT_MS]
         * is 6 s — sized for "is the server there", which is the right question
         * for every JSON op on this client. It is the wrong question for an
         * op that LOADS A MODEL: `/upscale` builds a QNN context from the
         * weight file on the first call, and the app reported that as
         * `SocketTimeoutException` on the node — a failure that reads as "the
         * backend is broken" when the backend was working exactly as designed.
         * Reported from the phone, 2026-09-10.
         */
        readTimeoutMs: Int = TIMEOUT_MS,
    ): BinaryResponse = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val heads = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                BinaryResponse(code, ByteArray(0), err, heads,
                    (System.nanoTime() - started) / 1_000_000)
            } else {
                BinaryResponse(code, conn.inputStream.use { it.readBytes() }, "", heads,
                    (System.nanoTime() - started) / 1_000_000)
            }
        } catch (e: Exception) {
            BinaryResponse(-1, ByteArray(0),
                e.javaClass.simpleName + ": " + (e.message ?: "no message"),
                emptyMap(), (System.nanoTime() - started) / 1_000_000)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ⚠ Returns the response rather than throwing on a non-200. A harness that
     * swallows a 404 into an exception message loses the status code, and the
     * status code is usually the whole finding.
     */
    private suspend fun request(method: String, path: String, body: String?): Response =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                // errorStream, not inputStream, once the code is >= 400 -- reading
                // the wrong one throws and the real status is lost.
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                Response(code, text, (System.nanoTime() - started) / 1_000_000)
            } catch (e: Exception) {
                Response(-1, e.javaClass.simpleName + ": " + (e.message ?: "no message"),
                    (System.nanoTime() - started) / 1_000_000)
            } finally {
                conn.disconnect()
            }
        }

    /** One decoded SSE frame: the `event:` name and the `data:` payload. */
    data class SseEvent(val name: String, val data: String, val atMs: Long)

    /**
     * Read a `text/event-stream` response, calling [onEvent] as each frame
     * ARRIVES.
     *
     * ⚠⚠ The whole value of this method is that it does not buffer, and nothing
     * about its shape proves that. `HttpURLConnection` transparently requests
     * gzip and Android's stack will happily accumulate a compressed body before
     * handing any of it over, which turns a stream into one late lump that
     * decodes to byte-identical events. That failure is invisible: the caller
     * still gets every frame, still in order, still with the right contents.
     *
     * ⇒ Two defences. `Accept-Encoding: identity` stops the compression, and
     * [SseEvent.atMs] stamps each frame with its arrival so the CALLER can
     * prove the spread. tools/sample_stream.sh makes the same check on the
     * shell side; a disagreement between them is a client bug, not a server
     * one.
     *
     * ⚠ Its own read timeout. TIMEOUT_MS is 6 s, sized for /health, and a read
     * timeout on a stream applies BETWEEN frames -- but a 20-step sample is
     * ~3.5 s of frames and a cold first step can be slow, so a value tuned for
     * a health probe would abort a working render.
     */
    suspend fun postSse(
        path: String,
        json: String,
        readTimeoutMs: Int = SSE_READ_TIMEOUT_MS,
        onEvent: (SseEvent) -> Unit,
    ): Response = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun sinceMs() = (System.nanoTime() - started) / 1_000_000
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            if (code >= 400) {
                // ⚠ A streaming endpoint still answers a bad request with a
                // plain JSON 400 (the backend cannot know the body is bad until
                // it parses it, which is before the stream opens). Read it as a
                // body, not as a stream, or the error is lost.
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                return@withContext Response(code, err, sinceMs())
            }
            var name = ""
            val data = StringBuilder()
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        // A blank line terminates a frame. Emit whatever the
                        // preceding lines built and reset.
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                onEvent(SseEvent(name, data.toString(), sinceMs()))
                                data.setLength(0)
                                name = ""
                            }
                        }
                        line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                        // ⚠ Concatenated, not overwritten. SSE allows a frame to
                        // carry several `data:` lines and they join; a preview
                        // image would be exactly that kind of payload.
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        else -> Unit   // comments (`:`) and unknown fields
                    }
                }
                // A stream that ends without its trailing blank line still has
                // a frame in hand. Dropping it would lose `complete`.
                if (data.isNotEmpty()) onEvent(SseEvent(name, data.toString(), sinceMs()))
            }
            Response(code, "", sinceMs())
        } catch (e: Exception) {
            Response(-1, e.javaClass.simpleName + ": " + (e.message ?: "no message"), sinceMs())
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The op surface, as the app sees it.
 *
 * ⚠ Deliberately thin. These mirror `backend-patches/003-op-endpoints.patch`
 * one-for-one; anything cleverer belongs in the executor, not here, because a
 * client that quietly reshapes a request makes a backend bug unattributable.
 */
object Ops {

    /** What `/encode_text` returns. The tensor stays server-side; this is the handle. */
    data class Cond(
        val handle: String,
        val seqLen: Int,
        val hiddenDim: Int,
        val negHash: String,
        val posHash: String,
        val serverMs: Long,
        val wireMs: Long,
    )

    /**
     * ⚠ Returns the raw JSON on failure rather than throwing a tidy message.
     * The backend's error bodies name the actual problem (`latent size 3 !=
     * expected 16384`), and rewriting that into "encode failed" throws away the
     * only useful part.
     */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val code: Int, val body: String) : Result<Nothing>
    }

    suspend fun encodeText(prompt: String, negative: String): Result<Cond> {
        val body = JSONObject()
            .put("prompt", prompt)
            .put("negative_prompt", negative)
            .toString()
        val r = Backend.post("/encode_text", body)
        if (r.code != 200) return Result.Err(r.code, r.body)
        return try {
            val j = JSONObject(r.body)
            Result.Ok(
                Cond(
                    handle = j.getString("handle"),
                    seqLen = j.getInt("seq_len"),
                    hiddenDim = j.getInt("hidden_dim"),
                    negHash = j.optString("neg_hash"),
                    posHash = j.optString("pos_hash"),
                    serverMs = j.optLong("ms"),
                    wireMs = r.millis,
                )
            )
        } catch (e: Exception) {
            Result.Err(r.code, "unparseable: ${r.body.take(200)}")
        }
    }


    /**
     * What `/sample?stream=1` returns. The latent stays server-side -- 4x64x64
     * floats is 64 KB and a graph that moves it over the wire on every edge
     * pays for nothing (docs/ARCHITECTURE.md section 6).
     *
     * [firstProgressMs] and [lastProgressMs] are the STREAMING EVIDENCE, and
     * they are fields rather than logging because a caller cannot otherwise
     * tell a stream from a buffered lump: the frames are identical either way.
     * If first ~= last ~= serverMs, nothing streamed.
     */
    data class Sampled(
        val handle: String,
        val latentSha: String,
        val steps: Int,
        val seed: Int,
        val serverMs: Long,
        val wireMs: Long,
        val progressEvents: Int,
        val firstProgressMs: Long,
        val lastProgressMs: Long,
        /** How many progress frames actually carried an image. */
        val previewFrames: Int = 0,
    )

    /**
     * One `progress` frame: where the render is, when it said so, and — when
     * previews were asked for and this step was on the stride — the
     * partially-denoised image.
     *
     * ⚠ [preview] is decoded bytes, not base64. The wire carries base64 because
     * SSE is text; keeping it that way past this boundary would mean every
     * consumer decoded it again, and a preview is on the hot path of the only
     * loop the user watches.
     */
    data class Progress(
        val step: Int,
        val total: Int,
        val atMs: Long,
        val preview: ByteArray? = null,
        val previewFormat: String? = null,
    )

    /**
     * prompt -> latent handle, with per-step progress.
     *
     * ⚠ Always asks for `?stream=1`. The plain shape exists for the shell
     * scripts, which cannot read a stream with toybox; an app that has a
     * progress bar to feed has no reason to prefer silence.
     *
     * ⚠ [onProgress] is called on the IO dispatcher, NOT the main thread. A
     * caller updating Compose state from it must hop back itself -- doing that
     * hop in here would put a per-step dispatch in the hot path of the only
     * loop the user watches.
     */
    suspend fun sample(
        prompt: String,
        negative: String,
        steps: Int = 20,
        cfg: Double = 7.5,
        seed: Int = 42,
        width: Int = 512,
        height: Int = 512,
        /**
         * Every Nth step carries a partially-denoised image, 0 for none.
         *
         * ⚠ NOT free, and the cost is not the transport: each preview is a
         * whole VAE decode (~190 ms) against a ~130 ms sampler step, so a
         * stride of 1 more than doubles a render. The `preview` op measures it
         * rather than assuming.
         */
        previewStride: Int = 0,
        /**
         * ⚠ jpeg, not the backend's default of "raw". Raw is uncompressed RGB
         * base64 — 1 MB per 512² frame — and it would make the transport look
         * like the cost when the decode is.
         */
        previewFormat: String = "jpeg",
        /**
         * ⭐ img2img: start from this latent instead of from noise.
         *
         * ⚠ In the same VAE space `sample()` and `vaeEncode()` return, so a
         * render can be fed straight back in without a decode/encode round
         * trip — which would cost ~190 ms and 1.4% of the picture (measured)
         * for nothing.
         */
        latentHandle: String? = null,
        /**
         * How much of the starting latent to destroy, 0..1.
         *
         * ⚠ 1.0 renoises completely, which is txt2img with extra steps. The
         * backend defaults it to 0.6 rather than 1.0 for exactly that reason: a
         * caller who forgot the field would get a picture with no trace of
         * their input and no error to explain it.
         */
        denoise: Double = 0.6,
        /**
         * ⭐⭐ Which sampler the backend should use.
         *
         * ⚠⚠ **Was never sent at all until 2026-09-10.** `RequestParser.hpp`
         * reads `json.value("scheduler", "dpm")`, so every render this app has
         * ever produced used DPM -- including checkpoints published with a
         * different one. Measured on device: on a distilled SDXL model at its
         * own settings, `dpm` gives a crunchy over-sharpened picture and
         * `euler_a` a clean one, same seed and steps and cfg.
         *
         * ⚠ Sent UNCONDITIONALLY, unlike `denoise` above: the backend folds
         * `scheduler_type` into the sample handle's key (`main.cpp`), so
         * omitting it on some calls and not others would make two requests that
         * differ only in whether the field was present collide on one cached
         * latent.
         */
        scheduler: String = ModelCatalog.DEFAULT_SCHEDULER,
        /**
         * ⭐ Conditioning from an `encode_text` node, so CLIP is a wire rather
         * than a hidden step inside the sampler.
         *
         * ⚠ The prompt strings are still sent and still required — the backend
         * refuses an empty prompt, and the prompt is part of the sample
         * handle's key. When a handle is supplied the strings are NOT
         * re-encoded, so what the UNet sees is exactly what the node produced.
         */
        condHandle: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): Result<Sampled> {
        val body = JSONObject()
            .put("prompt", prompt)
            .put("negative_prompt", negative)
            .put("steps", steps)
            .put("cfg", cfg)
            .put("seed", seed)
            .put("width", width)
            .put("height", height)
            .put("scheduler", scheduler)
            .apply {
                if (condHandle != null) put("cond_handle", condHandle)
                if (latentHandle != null) {
                    put("latent_handle", latentHandle)
                    put("denoise", denoise)
                }
                if (previewStride > 0) {
                    put("show_diffusion_process", true)
                    put("show_diffusion_stride", previewStride)
                    put("preview_format", previewFormat)
                }
            }
            .toString()

        var complete: JSONObject? = null
        var streamError: String? = null
        var nProgress = 0
        var nPreviews = 0
        var firstAt = -1L
        var lastAt = -1L

        val r = Backend.postSse("/sample?stream=1", body) { ev ->
            // ⚠ Switch on the payload's own "type", not on the `event:` name.
            // Both carry it, but a proxy or a client that only understood
            // `data:` would drop the name and keep the payload -- so the
            // payload is the one that has to be sufficient.
            val j = try { JSONObject(ev.data) } catch (e: Exception) { null }
            when (j?.optString("type")) {
                "progress" -> {
                    nProgress++
                    if (firstAt < 0) firstAt = ev.atMs
                    lastAt = ev.atMs
                    // ⚠ A frame without an image is the NORMAL case: only every
                    // Nth step carries one, and the backend also sends "" when a
                    // preview decode failed. Both must read as "no picture this
                    // step", not as an error.
                    val b64 = j.optString("image")
                    val bytes = if (b64.isNullOrEmpty()) null else try {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                    if (bytes != null) nPreviews++
                    onProgress(
                        Progress(
                            j.optInt("step"), j.optInt("total_steps"), ev.atMs,
                            bytes, j.optString("format").ifEmpty { null },
                        )
                    )
                }
                "complete" -> complete = j
                "error" -> streamError = j.optString("message")
                // ⚠ Not silent. An unrecognised frame means the backend and this
                // client disagree about the contract, which is exactly the thing
                // a harness exists to surface.
                else -> streamError = streamError
                    ?: "unrecognised frame \"${ev.name}\": ${ev.data.take(120)}"
            }
        }

        if (r.code != 200) return Result.Err(r.code, r.body)
        streamError?.let { return Result.Err(200, it) }
        val j = complete
            ?: return Result.Err(200, "stream ended after $nProgress progress frames " +
                "with no complete event")

        return Result.Ok(
            Sampled(
                handle = j.getString("handle"),
                latentSha = j.optString("latent_sha"),
                steps = j.optInt("steps", steps),
                seed = j.optInt("seed", seed),
                serverMs = j.optLong("ms"),
                wireMs = r.millis,
                progressEvents = nProgress,
                firstProgressMs = firstAt,
                lastProgressMs = lastAt,
                previewFrames = nPreviews,
            )
        )
    }

    /**
     * What the backend still holds -- conditionings AND latents, as ids.
     *
     * ⭐ The executor's residency check (Executor.kt). A remembered handle is
     * only usable while the process that owns it is still the process that owns
     * it; a restart, an idle release or an eviction invalidates every one of
     * them at once, and the app cannot know that from its own memory.
     *
     * ⚠ Returns null for "could not ask", NOT an empty set. Collapsing the two
     * makes a down backend look like a cold cache, which turns one clear
     * failure into a graph's worth of node failures.
     *
     * ⚠ Ignores the `kind` field here on purpose: the ids are already disjoint
     * (`lat_`/`cond_`) and the executor checks the kind it EXPECTED against the
     * value it remembered, which is the check that actually matters. `/handles`
     * omitting a whole kind is a real bug that happened once
     * (backend-patches/003-op-endpoints.patch), and it shows up as a prune, not
     * as a wrong kind.
     */
    suspend fun handles(): Set<String>? {
        val r = Backend.get("/handles")
        if (r.code != 200) return null
        return try {
            val arr = JSONObject(r.body).getJSONArray("handles")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("handle") }.toSet()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * pixels -> latent. The img2img half of the VAE, and the way a picture gets
     * back INTO a graph.
     *
     * ⚠⚠ The latent it returns is in the SAME space `sample()` returns, so the
     * two are interchangeable: a decoded render can be re-encoded, blended with
     * a sampled latent, and decoded again. A handle that sometimes meant one
     * space and sometimes the other would decode to plausible garbage with
     * nothing to say which it was.
     *
     * ⚠ [seed] is not decoration: a VAE latent is `mean + std * noise`, so the
     * seed is what makes the same image encode to the same latent twice — and a
     * content-addressed handle over a non-deterministic op is a cache that
     * never hits.
     */
    suspend fun vaeEncode(
        png: ByteArray,
        seed: Int = 42,
        width: Int = 512,
        height: Int = 512,
    ): Result<Sampled> {
        val body = JSONObject()
            .put("image", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
            .put("seed", seed)
            .put("width", width)
            .put("height", height)
            .toString()
        val r = Backend.post("/vae_encode", body)
        if (r.code != 200) return Result.Err(r.code, r.body)
        return try {
            val j = JSONObject(r.body)
            Result.Ok(
                Sampled(
                    handle = j.getString("handle"),
                    latentSha = j.optString("latent_sha"),
                    steps = 0,
                    seed = j.optInt("seed", seed),
                    serverMs = j.optLong("ms"),
                    wireMs = r.millis,
                    progressEvents = 0,
                    firstProgressMs = -1,
                    lastProgressMs = -1,
                )
            )
        } catch (e: Exception) {
            Result.Err(r.code, "unparseable: ${r.body.take(200)}")
        }
    }

    /** What `/latent_blend` returns: a new latent, keyed on both inputs and the mask. */
    data class Blended(
        val handle: String,
        val latentSha: String,
        val maskSha: String,
        val wireMs: Long,
    )

    /**
     * latent + latent + mask -> latent. RePaint-style blending, which is what
     * makes inpainting a Tier 0 node (docs/ARCHITECTURE.md §2).
     *
     * ⚠⚠ **mask = 1 takes [b]**, matching `/generate`'s own per-step blend where
     * the mask means "repaint". The two must agree: blending the other way
     * round produces a plausible picture with the wrong region replaced, and
     * nothing raises an error.
     *
     * ⚠ [maskPng] is a whole PNG, base64'd, and the BACKEND downsamples it to
     * latent resolution. Doing the downsample here would be a second copy of a
     * rule that already exists in `RequestParser` -- and the two disagreeing
     * would mean a mask drawn for `/generate` meant something different in a
     * graph.
     */
    suspend fun latentBlend(a: String, b: String, maskPng: ByteArray): Result<Blended> {
        val body = JSONObject()
            .put("a", a)
            .put("b", b)
            .put("mask", android.util.Base64.encodeToString(maskPng, android.util.Base64.NO_WRAP))
            .toString()
        val r = Backend.post("/latent_blend", body)
        if (r.code != 200) return Result.Err(r.code, r.body)
        return try {
            val j = JSONObject(r.body)
            Result.Ok(
                Blended(
                    handle = j.getString("handle"),
                    latentSha = j.optString("latent_sha"),
                    maskSha = j.optString("mask_sha"),
                    wireMs = r.millis,
                )
            )
        } catch (e: Exception) {
            Result.Err(r.code, "unparseable: ${r.body.take(200)}")
        }
    }

    data class Decoded(val png: ByteArray, val rgbSha: String, val serverMs: Long, val wireMs: Long)

    /**
     * ⭐ Uses `?binary=1`, so the PNG arrives as `image/png` rather than base64.
     * Base64 inflates an image 33% and the device-side extraction of it is not
     * reliable (see backend-patches/README.md).
     */
    suspend fun vaeDecode(
        seed: Int? = null,
        latentHandle: String? = null,
        width: Int = 512,
        height: Int = 512,
    ): Result<Decoded> {
        val body = JSONObject()
            .put("width", width)
            .put("height", height)
            // A handle names a latent the sampler already produced; a seed asks
            // the backend to decode fresh noise. Sending both would let the
            // backend pick, and which it picked would not be visible here.
            .apply {
                if (latentHandle != null) put("latent_handle", latentHandle)
                else if (seed != null) put("seed", seed)
            }
            .toString()
        val r = Backend.postForBytes("/vae_decode?binary=1", body)
        if (r.code != 200) return Result.Err(r.code, r.text)
        return Result.Ok(
            Decoded(
                png = r.bytes,
                rgbSha = r.headers["x-rgb-sha"] ?: "?",
                serverMs = r.headers["x-ms"]?.toLongOrNull() ?: -1L,
                wireMs = r.millis,
            )
        )
    }

    /**
     * ⚠ Generous on purpose. The FIRST `/upscale` of a process pays a QNN
     * context init off a 8-24 MB weight file before any pixels are touched, and
     * a 4x of a 1024² frame is 16x the pixels of the input. Sized to fail on a
     * genuine hang rather than on a cold load.
     */
    private const val UPSCALE_TIMEOUT_MS = 300_000

    data class Upscaled(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val serverMs: Long,
        val wireMs: Long,
    )

    /**
     * ⭐⭐ 4x a picture on the NPU, through the upscaler at [upscalerPath].
     *
     * ⚠⚠ **This op does NOT join the context key, and that is the whole reason
     * an upscaler can live in the same graph as a sampler.** `/upscale` is
     * registered in `main.cpp` OUTSIDE the `if (pipeline)` guards, and its
     * handler builds the QNN model per request from the path in the header and
     * frees it when the request ends. So it runs inside whichever backend
     * process happens to be up, needs no relaunch, and costs no 2.3-5 s process
     * transition (`docs/ARCHITECTURE.md` §4). ⚠ `--upscaler_mode` is unrelated:
     * it means "a server with NO diffusion model", for upscale-only use.
     *
     * ⚠⚠ **Raw RGB up, JPEG down.** Not a typo and not JSON: the body is
     * `3 * width * height` uncompressed bytes and the reply is a JPEG. A 1024²
     * frame is 3 MB on a loopback socket that moves 1 MB in ~1.3 ms.
     *
     * ⚠ The path is a DEVICE path the backend opens itself, so the weights
     * never cross the wire -- the same handle-not-buffer rule the rest of the
     * op surface follows.
     */
    suspend fun upscale(
        rgb: ByteArray,
        width: Int,
        height: Int,
        upscalerPath: String,
    ): Result<Upscaled> {
        val want = 3L * width * height
        // ⚠ Checked HERE, not left to the server. The backend throws a 400 with
        // a good message, but this is a programming error on our side and the
        // stack that produced the wrong buffer is long gone by then.
        if (rgb.size.toLong() != want) {
            return Result.Err(
                -1,
                "upscale: image is ${rgb.size} bytes, expected $want for ${width}x$height RGB",
            )
        }
        val r = Backend.postBytes(
            "/upscale",
            rgb,
            mapOf(
                "X-Image-Width" to width.toString(),
                "X-Image-Height" to height.toString(),
                "X-Upscaler-Path" to upscalerPath,
            ),
            readTimeoutMs = UPSCALE_TIMEOUT_MS,
        )
        if (r.code != 200) return Result.Err(r.code, r.text)
        return Result.Ok(
            Upscaled(
                jpeg = r.bytes,
                // ⚠ From the RESPONSE, never assumed to be 4x the input. The
                // scale factor is a property of the weight file, and a 2x
                // upscaler would otherwise be reported as 4x by a node that
                // never looked.
                width = r.headers["x-output-width"]?.toIntOrNull() ?: 0,
                height = r.headers["x-output-height"]?.toIntOrNull() ?: 0,
                serverMs = r.headers["x-duration-ms"]?.toLongOrNull() ?: -1L,
                wireMs = r.millis,
            )
        )
    }
}
