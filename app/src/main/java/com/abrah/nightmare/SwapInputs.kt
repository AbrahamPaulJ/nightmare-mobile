package com.abrah.nightmare

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * ⭐⭐ What an **SD 1.5 Swap** render sends besides the prompt: the packed LoRA
 * directory and the ControlNet hint ([Ops.TemplateInputs], `backend-patches/015`).
 *
 * ⚠ Everything here is per REQUEST — the model is launched once and a new LoRA
 * mix or hint costs a pack (cached) or a ControlNet pass, never a relaunch.
 * The user's design, 2026-09-29 (`notes/PROGRESS.md`): several LoRAs merged into
 * the template's one rank-64 slot; ControlNet canny / depth / openpose, canny
 * computed here from a photo, depth and openpose taking a ready-made hint.
 */
object SwapInputs {

    private const val TAG = "SwapInputs"

    /** The template's ControlNet is a frozen 512² graph, and so is its UNet. */
    const val SIZE = 512

    const val NONE = "none"
    const val CANNY = "canny"
    const val DEPTH = "depth"
    const val OPENPOSE = "openpose"
    val TYPES = listOf(NONE, CANNY, DEPTH, OPENPOSE)

    /** ⭐ Only canny is computed; the others ARE the hint the user brings. */
    fun computes(type: String): Boolean = type == CANNY

    /** ⚠ How many LoRA packs one model keeps; each is ~100 MB at rank 64. */
    private const val KEEP_PACKS = 4

    /**
     * ⭐ Where a ControlNet context lives: one per TYPE, shared by every Swap
     * model — the branch reads the latent, not the checkpoint, so the same
     * `controlnet.bin` drives any template UNet. Beside the models, never inside
     * one, like `_loras` and `_dit_shared`.
     */
    fun controlnetFile(context: Context, type: String): File =
        File(File(ModelCatalog.root(context), "_controlnet"), "$type.bin")

    fun installedTypes(context: Context): List<String> =
        TYPES.filter { it != NONE && controlnetFile(context, it).isFile }

    /**
     * ⭐⭐ The hint the ControlNet sees, from [src]: FITTED into [SIZE]² — the
     * template's canvas — and padded black, then for canny reduced to white
     * edges ([Canny], OpenCV's `Canny(img, 100, 200)`, what the AI Hub canny
     * branch was built against).
     *
     * ⚠⚠ Fitted, never centre-cropped: a portrait pose skeleton cropped square
     * lost its head and its feet (2026-09-29, the first openpose hint tried).
     * Black is "nothing here" in all three kinds — no edge, no limb, far away.
     * ⚠ Canny runs on the picture BEFORE the padding, or the pad's border
     * would be found as an edge.
     */
    fun hint(src: Bitmap, type: String): Bitmap {
        val scale = SIZE.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt().coerceIn(1, SIZE)
        val h = (src.height * scale).toInt().coerceIn(1, SIZE)
        var fitted = Bitmap.createScaledBitmap(src, w, h, true)
        if (computes(type)) {
            val px = IntArray(w * h)
            fitted.getPixels(px, 0, w, 0, 0, w, h)
            val edges = Canny.edges(Canny.gray(px), w, h)
            for (i in px.indices) px[i] = if (edges[i].toInt() != 0) -0x1 else -0x1000000
            fitted = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
        if (w == SIZE && h == SIZE) return fitted
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(fitted, ((SIZE - w) / 2).toFloat(), ((SIZE - h) / 2).toFloat(), null)
        return out
    }

    /**
     * ⭐⭐ The pack directory for [loras] beside [modelDir], packed on first use.
     *
     * ⚠⚠ Named after its CONTENTS — each LoRA's name, size and mtime, the
     * target list, and the strengths when they are baked — because the backend
     * keys a cached latent by `lora_dir`'s PATH (patch 015). A directory reused
     * for a different mix would hand back the previous mix's picture.
     *
     * ⭐ One LoRA at a non-negative strength is packed at 1.0 and the strength is
     * sent per request, so moving its slider costs nothing. Several (or a
     * negative strength, which `lora_S` cannot carry) are merged with their
     * strengths baked ([TemplateLora.packMerged]) and sent at 1.0.
     *
     * @return the directory and the `lora_strength` to send with it.
     */
    fun packDir(
        modelDir: File,
        loras: List<Pair<File, Double>>,
        say: (String) -> Unit = {},
    ): Pair<File, Double> {
        val targetsFile = File(modelDir, TemplateLora.TARGETS_FILE)
        val single = loras.size == 1 && loras[0].second >= 0
        val md = MessageDigest.getInstance("SHA-256")
        md.update(targetsFile.readBytes())
        for ((f, strength) in loras) {
            md.update("${f.name}|${f.length()}|${f.lastModified()}".toByteArray())
            if (!single) md.update("|$strength".toByteArray())
        }
        val key = md.digest().take(8).joinToString("") { "%02x".format(it) }
        val root = File(modelDir, "swap_lora")
        val dir = File(root, key)
        val done = File(dir, ".done")
        if (!done.isFile) {
            say("packing ${if (loras.size == 1) "the LoRA" else "${loras.size} LoRAs"}")
            val t0 = System.nanoTime()
            val targets = TemplateLora.readTargets(targetsFile.readText())
            val packed = TemplateLora.packMerged(
                if (single) listOf(loras[0].first to 1.0) else loras, targets, dir,
            )
            done.writeText(packed.toString())
            Log.i(TAG, "packed ${loras.map { it.first.name to it.second }} -> ${dir.name} in " +
                "${(System.nanoTime() - t0) / 1_000_000} ms: $packed")
            if (packed.matched == 0) {
                dir.deleteRecursively()
                throw IllegalStateException(
                    "no layer of this model matched ${loras.joinToString { it.first.name }} — " +
                        "is it an SD 1.5 LoRA?",
                )
            }
        }
        dir.setLastModified(System.currentTimeMillis())
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP_PACKS)
            .forEach { it.deleteRecursively() }
        return dir to if (single) loras[0].second else 1.0
    }

    /**
     * ⭐ Everything a Swap sample sends, or throws a message that names what is
     * missing. [control] is the picture the hint is made from (the wired
     * `control` port, else the image picked on the node).
     */
    fun resolve(
        context: Context,
        spec: ModelSpec,
        loras: List<Pair<String, Double>>,
        type: String,
        strength: Double,
        control: Bitmap?,
        say: (String) -> Unit = {},
    ): Ops.TemplateInputs {
        val modelDir = spec.dir(context)
        val (loraDir, loraStrength) = if (loras.isEmpty()) null to 1.0 else {
            val (dir, s) = packDir(modelDir, loras.map { File(it.first) to it.second }, say)
            dir.absolutePath to s
        }
        if (type == NONE || type.isBlank()) {
            return Ops.TemplateInputs(loraDir = loraDir, loraStrength = loraStrength)
        }
        require(type in TYPES) { "unknown ControlNet type \"$type\"" }
        val cn = controlnetFile(context, type)
        if (!cn.isFile) {
            throw IllegalStateException("the $type ControlNet is not installed (models/_controlnet/$type.bin)")
        }
        control ?: throw NeedsInput(
            "ControlNet $type needs a picture — pick one on the node or wire one into control, " +
                "or set ControlNet to none",
        )
        say(if (computes(type)) "finding the edges" else "reading the $type hint")
        val png = ImageStore.encodePng(hint(control, type))
        return Ops.TemplateInputs(
            loraDir = loraDir, loraStrength = loraStrength,
            controlnet = cn.absolutePath, controlImage = png, controlStrength = strength,
        )
    }
}

/**
 * ⭐ Canny edges in plain Kotlin — OpenCV's `Canny(img, low, high)` with its
 * defaults: 3×3 Sobel, L1 gradient magnitude `|gx| + |gy|`, non-maximum
 * suppression in four directions, hysteresis from the strong pixels. No blur,
 * as in OpenCV (the caller's downscale already smooths).
 */
object Canny {

    /** ARGB pixels to luma 0..255 (BT.601, as OpenCV's `COLOR_RGB2GRAY`). */
    fun gray(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }

    /** @return 255 on an edge, 0 elsewhere, row-major [w]×[h]. */
    fun edges(gray: FloatArray, w: Int, h: Int, low: Float = 100f, high: Float = 200f): ByteArray {
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)
        fun at(x: Int, y: Int) = gray[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)) -
                (at(x - 1, y - 1) + 2 * at(x - 1, y) + at(x - 1, y + 1))
            val sy = (at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)) -
                (at(x - 1, y - 1) + 2 * at(x, y - 1) + at(x + 1, y - 1))
            val i = y * w + x
            gx[i] = sx; gy[i] = sy
            mag[i] = kotlin.math.abs(sx) + kotlin.math.abs(sy)
        }
        // 0 = suppressed, 1 = weak, 2 = strong
        val state = ByteArray(w * h)
        val tan22 = 0.4142135f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m <= low) continue
            val ax = kotlin.math.abs(gx[i])
            val ay = kotlin.math.abs(gy[i])
            val (a, b) = when {
                ay <= ax * tan22 -> mag[i - 1] to mag[i + 1]                       // horizontal gradient
                ax <= ay * tan22 -> mag[i - w] to mag[i + w]                       // vertical gradient
                (gx[i] > 0) == (gy[i] > 0) -> mag[i - w - 1] to mag[i + w + 1]     // 45°
                else -> mag[i - w + 1] to mag[i + w - 1]                           // 135°
            }
            // OpenCV's tie rule: strictly greater than one side, not less than the other.
            if (m > a && m >= b) state[i] = if (m > high) 2 else 1
        }
        val out = ByteArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in state.indices) if (state[i].toInt() == 2) { out[i] = -1; stack[sp++] = i }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (state[j].toInt() == 1 && out[j].toInt() == 0) { out[j] = -1; stack[sp++] = j }
            }
        }
        return out
    }
}
