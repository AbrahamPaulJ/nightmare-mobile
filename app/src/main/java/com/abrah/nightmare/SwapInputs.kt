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
 * computed here from a photo, openpose detected from a photo ([PoseDetector])
 * or taken as-is from a skeleton, depth taking a ready-made depth map.
 *
 * ⭐⭐ The control picture follows the node's CROP WINDOW when the node has a
 * photo wired (the user's call, 2026-09-29): with nothing else chosen the photo
 * itself is the control picture, cut by the same frame, so the hint lines up
 * with the img2img base; a separately chosen picture gets the same relative
 * frame. With no photo there is no crop window, and the picture is FITTED.
 */
object SwapInputs {

    private const val TAG = "SwapInputs"

    /** The template's ControlNet is a frozen 512² graph, and so is its UNet. */
    const val SIZE = 512

    const val NONE = "none"
    const val CANNY = "canny"
    const val DEPTH = "depth"
    const val OPENPOSE = "openpose"
    /**
     * ⭐ What the ControlNet chooser offers. Depth is back since 1.6.061: its
     * ControlNet is built (v73 + v68 tiers) and hosted ([ControlNetCatalog]).
     */
    val TYPES = listOf(NONE, CANNY, DEPTH, OPENPOSE)

    /** ⭐ Canny is always computed; openpose and depth are estimated unless the picture already is one. */
    fun computes(type: String): Boolean = type == CANNY || type == OPENPOSE || type == DEPTH

    /** The node's crop window — [CropNode.render]'s params, normalised to the source. */
    data class Frame(val x: Float, val y: Float, val w: Float, val h: Float, val pad: String?)

    /**
     * What [hint] made. [bitmap] null with [missing] set: a photo whose kind
     * needs an estimator that is not installed ([OPENPOSE] or [DEPTH]) — the
     * caller offers that download.
     */
    class Hint(val bitmap: Bitmap?, val missing: String? = null)

    /** ⭐ IP-Adapter's default strength — diffusers' examples use 0.5–0.7 for Plus. */
    const val IP_SCALE_DEFAULT = 0.6

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
     * ⭐⭐ The hint the ControlNet sees, from [src], at the template's [size]².
     *
     * [frame] is the REGION of [src] the hint is cut from — the node's crop window
     * when the picture is the photo, the picture's own region otherwise
     * ([SdSampler.controlFrame]); null is the whole picture. ⭐⭐ It is placed in the
     * render's ASPECT BOX ([aspectBox]), centred on the square canvas as the backend
     * centres the aspect rectangle, contained (never stretched) and black everywhere
     * else. Black is "nothing here" in all three kinds — and the bars are cut away by
     * the decode anyway. ⚠⚠ Until 1.6.110 the frame was squared on its WIDTH, so a
     * portrait aspect lost the top and bottom of what was framed.
     *
     * ⚠⚠ The frame may hang off the picture (a crop zoomed out √2): only the part
     * that IS picture is transformed — canny's edges, the depth, the skeleton —
     * so the picture's border never becomes an edge or a surface.
     *
     * - canny: white edges ([Canny], OpenCV's 100/200 — what the AI Hub branch
     *   was built against).
     * - openpose: a skeleton picture as it is ([PoseDetector.looksLikeSkeleton],
     *   judged on the WHOLE source); a photo through [PoseDetector.skeleton].
     * - depth: a depth map as it is ([DepthEstimator.looksLikeDepthMap]); a
     *   photo through [DepthEstimator.depth].
     *
     * ⚠ Blocking (the estimators); off the main thread. [context] null = none.
     */
    fun hint(context: Context?, src: Bitmap, type: String, frame: Frame?, size: Int = SIZE, aspect: Float = 1f): Hint {
        val ready = when (type) {
            OPENPOSE -> com.abrah.nightmare.pose.PoseDetector.looksLikeSkeleton(src)
            DEPTH -> com.abrah.nightmare.pose.DepthEstimator.looksLikeDepthMap(src)
            else -> true
        }
        var missing = false
        fun transform(b: Bitmap): Bitmap? = when {
            type == CANNY -> cannyOf(b)
            ready -> b
            context == null -> null
            type == OPENPOSE -> com.abrah.nightmare.pose.PoseDetector.skeleton(context, b)
            type == DEPTH -> com.abrah.nightmare.pose.DepthEstimator.depth(context, b)
            else -> b
        }.also { if (it == null) missing = true }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawColor(android.graphics.Color.BLACK)
        val p = placement(src.width, src.height, frame ?: WHOLE, size, aspect)
            ?: return Hint(out)
        val native = Math.round(p.part.w * src.width).coerceAtLeast(1)
        val nativeH = Math.round(p.part.h * src.height).coerceAtLeast(1)
        val piece = if (type == CANNY && native < p.w) {
            upscaleEdges(cannyOf(CropNode.render(src, p.part.x, p.part.y, p.part.w, p.part.h, native, nativeH, null).first), p.w, p.h)
        } else {
            transform(CropNode.render(src, p.part.x, p.part.y, p.part.w, p.part.h, p.w, p.h, null).first)
        }
        if (piece == null || missing) return Hint(null, missing = type)
        android.graphics.Canvas(out).drawBitmap(piece, p.left.toFloat(), p.top.toFloat(), null)
        return Hint(out)
    }

    private val WHOLE = Frame(0f, 0f, 1f, 1f, null)

    /**
     * ⭐ The render's aspect rectangle on a [size]² canvas — `aspect` is width / height.
     * ⚠ Rounded like [ModelCatalog.aspectTarget] is not (it snaps to 8): a hint pixel or
     * two off the band edge is black either way.
     */
    fun aspectBox(size: Int, aspect: Float): Pair<Int, Int> =
        if (aspect <= 0f || aspect == 1f) size to size
        else if (aspect > 1f) size to Math.round(size / aspect).coerceIn(1, size)
        else Math.round(size * aspect).coerceIn(1, size) to size

    /**
     * Where the picture-covered part of [frame] lands on the [size]² hint: [part] (normalised
     * to the picture) drawn [w]x[h] at ([left], [top]). Null when the frame holds no picture.
     */
    internal class Placement(val part: Frame, val left: Int, val top: Int, val w: Int, val h: Int)

    internal fun placement(srcW: Int, srcH: Int, frame: Frame, size: Int, aspect: Float): Placement? {
        val (bw, bh) = aspectBox(size, aspect)
        val pw = frame.w * srcW
        val ph = frame.h * srcH
        if (pw <= 0f || ph <= 0f) return null
        val s = minOf(bw / pw, bh / ph)
        val ox = (size - pw * s) / 2f
        val oy = (size - ph * s) / 2f
        val l = frame.x.coerceAtLeast(0f)
        val t = frame.y.coerceAtLeast(0f)
        val r = (frame.x + frame.w).coerceAtMost(1f)
        val b = (frame.y + frame.h).coerceAtMost(1f)
        if (r <= l || b <= t) return null
        return Placement(
            Frame(l, t, r - l, b - t, null),
            Math.round(ox + (l - frame.x) * srcW * s), Math.round(oy + (t - frame.y) * srcH * s),
            Math.round((r - l) * srcW * s).coerceAtLeast(1), Math.round((b - t) * srcH * s).coerceAtLeast(1),
        )
    }

    /**
     * ⭐ The node's photo as the base will see it — its crop window, with its own pad
     * fill, at the render's [aspect]. The underlay a ControlNet / IP-Adapter picture is
     * lined up against, in a crop window of the same shape.
     */
    fun framedPhoto(src: Bitmap, frame: Frame, aspect: Float, size: Int = SIZE): Bitmap {
        val (w, h) = aspectBox(size, aspect)
        return CropNode.render(src, frame.x, frame.y, frame.w, frame.h, w, h, frame.pad).first
    }

    /** ⭐ The hint's side: SD 1.5's ControlNet is a 512² graph, SDXL's a 1024² one (backend 023). */
    fun sizeFor(family: Family): Int = if (family == Family.SDXL_SWAP) 1024 else SIZE

    /**
     * ⚠ Edges of a picture SMALLER than the hint are found at its own size and
     * then scaled up WITHOUT filtering: upscaling first blurs every step below
     * canny's threshold (a 160 px region at ×3.2 came back black, the golden).
     */
    private fun upscaleEdges(e: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(e, w, h, false)

    private fun cannyOf(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val e = Canny.edges(Canny.gray(px), w, h)
        for (i in px.indices) px[i] = if (e[i].toInt() != 0) -0x1 else -0x1000000
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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
                        "is it a LoRA for this model's family (SD 1.5 or SDXL)?",
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
        frame: Frame?,
        say: (String) -> Unit = {},
        /** ⭐ The render's shape, width / height ([SdSampler.swapAspect]) — the hint's box. */
        aspect: Float = 1f,
        /** ⭐ IP-Adapter's reference picture ([IpAdapter]); null = none. */
        reference: Bitmap? = null,
        ipAdapter: String = IpAdapter.PLUS,
        ipScale: Double = IP_SCALE_DEFAULT,
    ): Ops.TemplateInputs {
        val modelDir = spec.dir(context)
        val (loraDir, loraStrength) = if (loras.isEmpty()) null to 1.0 else {
            val (dir, s) = packDir(modelDir, loras.map { File(it.first) to it.second }, say)
            dir.absolutePath to s
        }
        val ipDir = reference?.let { ref ->
            if (!IpAdapter.supports(modelDir)) {
                throw NeedsInput(
                    if (spec in ModelCatalog.builtIn) {
                        "this copy of ${spec.label} predates IP-Adapter — delete it in Models and " +
                            "download it again to use a reference picture, or unwire the reference"
                    } else {
                        "this model was converted before IP-Adapter — convert it again with npuforge " +
                            "(SD1.5 Swap) to use a reference picture, or unwire the reference"
                    },
                )
            }
            if (!IpAdapter.isInstalled(context, ipAdapter)) {
                throw NeedsInput("download IP-Adapter first — on the node's IP-Adapter tab or in Models, Tools")
            }
            IpAdapter.ipDir(context, modelDir, ref, ipAdapter, ipScale, say).absolutePath
        }
        if (type == NONE || type.isBlank()) {
            return Ops.TemplateInputs(loraDir = loraDir, loraStrength = loraStrength, ipDir = ipDir)
        }
        require(type in TYPES) { "unknown ControlNet type \"$type\"" }
        // ⭐ SDXL Swap reads its own ControlNet (`sdxl_<type>`, [ControlNetCatalog.idFor]).
        val id = ControlNetCatalog.idFor(spec.family, type)
        val cn = controlnetFile(context, id)
        if (!cn.isFile) {
            throw NeedsInput(
                if (ControlNetCatalog.buildFor(id) == null) "the $type ControlNet is not available for this model or phone yet"
                else "download the $type ControlNet first — on the node's ControlNet tab or in Models, Tools",
            )
        }
        control ?: throw NeedsInput(
            "ControlNet $type needs a picture — pick one on the node or wire one into control, " +
                "or set ControlNet to none",
        )
        say(
            when (type) {
                CANNY -> "finding the edges"
                OPENPOSE -> "finding the pose"
                DEPTH -> "finding the depth"
                else -> "reading the $type hint"
            },
        )
        val made = hint(context, control, type, frame, sizeFor(spec.family), aspect)
        val bitmap = made.bitmap ?: throw NeedsInput(
            if (made.missing == DEPTH) {
                "depth needs the ${com.abrah.nightmare.pose.DepthEstimator.LABEL} to read a photo — " +
                    "download it on the ControlNet tab or in Models, Tools (a ready-made depth map needs nothing)"
            } else {
                "openpose needs the ${com.abrah.nightmare.pose.PoseDetector.LABEL} to read a photo — " +
                    "download it on the ControlNet tab or in Models, Tools (a ready-made skeleton needs nothing)"
            },
        )
        val png = ImageStore.encodePng(bitmap)
        return Ops.TemplateInputs(
            loraDir = loraDir, loraStrength = loraStrength,
            controlnet = cn.absolutePath, controlImage = png, controlStrength = strength,
            ipDir = ipDir,
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
