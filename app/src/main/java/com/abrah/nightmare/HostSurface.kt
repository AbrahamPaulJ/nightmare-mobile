package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.json.JSONObject

/**
 * Everything a plugin can ask the host to do — the whole of it.
 *
 * ⭐⭐ This class IS the sandbox boundary (docs/ARCHITECTURE.md §7). QuickJS
 * gives a script exactly one native global, `host.call`, and it lands here, so
 * the set of things a downloaded plugin can do to the device is the `when`
 * below. That is what makes the manifest's `permissions` list checkable instead
 * of decorative — and it is why a new capability must be added *here*, as a
 * named op, rather than by handing JS another object.
 *
 * ⚠ Ops are `group.verb` and take/return JSON objects. Images are named by
 * handle, never carried: see [ImageStore].
 */
/**
 * The backend ops a plugin may reach, as a SYNCHRONOUS interface.
 *
 * ⚠⚠ Synchronous is not a preference, it is what QuickJS gives us: `host.call`
 * returns a string, and there is no event loop behind it to await a promise on.
 * So a backend-touching host op **blocks the node's thread for the whole HTTP
 * round trip** — a `latent.blend` is ~50 ms, but a future op that sampled would
 * block for seconds.
 *
 * That is sound only because the executor runs nodes sequentially and off the
 * main thread (docs/ARCHITECTURE.md §4). ⇒ Two things follow, and they are
 * written here because the day they matter it will not be obvious: a
 * long-running op must stay cancellable through the same route the sampler uses
 * (hanging up the stream), and nodes running concurrently would need this
 * interface to become genuinely async, which means promises in the prelude.
 */
interface LatentOps {
    /** @return the blended latent's handle. Throws with the backend's own words. */
    fun blend(a: String, b: String, maskPng: ByteArray): String
}

class HostSurface(
    val images: ImageStore,
    /**
     * ⚠ Nullable so the surface can be constructed — and unit-tested — with no
     * backend at all. A `latent.*` call without one is an error naming the
     * reason, not a null pointer.
     */
    private val latents: LatentOps? = null,
) : JsRuntime.HostOps {

    /** Every op call, for the harness to count. A plugin that says it did no work and made 40 host calls is worth knowing about. */
    var calls = 0
        private set

    /**
     * Whose node is running right now, or null between nodes.
     *
     * ⚠⚠ The HOST tracks this; the script is never asked who it is. A plugin
     * that could name itself in the call could name any other plugin, so a
     * permission check built on that would be decoration. This is ambient state
     * and that is a real cost — it is only sound because the executor runs
     * nodes strictly sequentially on one thread (docs/ARCHITECTURE.md §4). ⇒ If
     * nodes ever run concurrently, this becomes wrong before it becomes slow.
     */
    private var caller: Plugin? = null

    /** Run [body] as [plugin], for the permission check below. */
    fun <T> asPlugin(plugin: Plugin, body: () -> T): T {
        check(caller == null) { "re-entrant host call: ${caller?.id} is already running" }
        caller = plugin
        try {
            return body()
        } finally {
            caller = null
        }
    }

    class Denied(message: String) : SecurityException(message)

    override fun call(op: String, argsJson: String): String {
        calls++
        // ⚠ Checked BEFORE the args are even parsed. A denied op must not be
        // able to reach a single line of the implementation, including its
        // argument validation -- that is what makes the permission list an
        // upper bound on what a plugin can do rather than a description of what
        // it usually does.
        permit(op)
        val a = JSONObject(argsJson)
        return when (op) {
            "image.info" -> {
                val bmp = image(a, "image")
                JSONObject()
                    .put("width", bmp.width)
                    .put("height", bmp.height)
                    .toString()
            }

            /**
             * The first Tier 0 op. Pure data, app-side, no backend.
             */
            "image.resize" -> {
                val src = image(a, "image")
                val w = a.getInt("width")
                val h = a.getInt("height")
                // ⚠ Bounded, because the argument comes from a script. A plugin
                // asking for 60000x60000 would otherwise be an OOM kill with no
                // attribution to the plugin that caused it.
                requireSize(w, h, "image.resize")
                // `filter = true` is bilinear. ⚠ Named rather than passed
                // positionally: Bitmap.createScaledBitmap's last argument
                // silently changes a downscale from smooth to aliased.
                val out = Bitmap.createScaledBitmap(src, w, h, /* filter = */ true)
                JSONObject().put("image", images.put(out)).toString()
            }

            /**
             * Crop, with the rectangle checked against the source.
             *
             * ⚠⚠ Every bound is checked HERE, because the numbers come from a
             * script. `Bitmap.createBitmap` with an out-of-range rectangle
             * throws an IllegalArgumentException whose message names x, y and
             * dimensions but not the PLUGIN -- so the check exists to make the
             * error say whose fault it is, not to prevent a crash.
             */
            "image.crop" -> {
                val src = image(a, "image")
                val x = a.getInt("x")
                val y = a.getInt("y")
                val w = a.getInt("width")
                val h = a.getInt("height")
                require(w > 0 && h > 0) { "image.crop needs a positive size, got ${w}x$h" }
                require(x >= 0 && y >= 0 && x + w <= src.width && y + h <= src.height) {
                    "image.crop ${w}x$h at ($x,$y) does not fit in ${src.width}x${src.height}"
                }
                val out = Bitmap.createBitmap(src, x, y, w, h)
                JSONObject().put("image", images.put(out)).toString()
            }

            /**
             * A new solid image — something to composite onto.
             *
             * ⚠ Bounded like every other size that comes from a script, and
             * more tightly than it looks: `width * height * 4` bytes are
             * allocated immediately, so 8192² is already 256 MB.
             */
            "image.new" -> {
                val w = a.getInt("width")
                val h = a.getInt("height")
                requireSize(w, h, "image.new")
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(color(a.optString("color", "#000000")))
                JSONObject().put("image", images.put(bmp)).toString()
            }

            /**
             * Paste `overlay` onto `base` at (x, y), optionally through a mask.
             *
             * ⚠⚠ Mask semantics are stated once, here, and everything else
             * must follow them: **a mask is a grayscale image, and WHITE KEEPS
             * the overlay**. Backwards, this produces a plausible picture with
             * the wrong half of the image in it — a failure that never raises
             * an error and is easy to "fix" in the wrong place.
             */
            "image.composite" -> {
                val base = image(a, "base")
                val overlay = image(a, "overlay")
                val x = a.optInt("x", 0)
                val y = a.optInt("y", 0)
                val mask = if (a.has("mask")) image(a, "mask") else null
                if (mask != null) {
                    require(mask.width == overlay.width && mask.height == overlay.height) {
                        "image.composite mask is ${mask.width}x${mask.height} but the " +
                            "overlay is ${overlay.width}x${overlay.height}"
                    }
                }
                // ⚠ A COPY. The store is content-addressed, so mutating a
                // bitmap in place would change the pixels behind an id that
                // other nodes have already cached under their own keys.
                val out = base.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(out).drawBitmap(
                    if (mask == null) overlay else maskedCopy(overlay, mask),
                    x.toFloat(), y.toFloat(), null,
                )
                JSONObject().put("image", images.put(out)).toString()
            }

            /** Linear cross-fade. `alpha` is how much of `b` shows. */
            "image.blend" -> {
                val bmpA = image(a, "a")
                val bmpB = image(a, "b")
                require(bmpA.width == bmpB.width && bmpA.height == bmpB.height) {
                    "image.blend needs matching sizes, got ${bmpA.width}x${bmpA.height} " +
                        "and ${bmpB.width}x${bmpB.height}"
                }
                val alpha = a.getDouble("alpha")
                require(alpha in 0.0..1.0) { "image.blend alpha must be 0..1, got $alpha" }
                val out = bmpA.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(out).drawBitmap(bmpB, 0f, 0f, Paint().apply {
                    // ⚠ Rounded, not truncated. alpha 1.0 must be entirely b,
                    // and rounding says that outright instead of relying on
                    // 255.0 landing exactly.
                    this.alpha = Math.round(alpha * 255).toInt()
                })
                JSONObject().put("image", images.put(out)).toString()
            }

            /** Luminance, for building masks. */
            "image.grayscale" ->
                filtered(image(a, "image"), ColorMatrix().apply { setSaturation(0f) })

            /** ⚠ Inverts RGB and leaves alpha alone — inverting a mask must not erase it. */
            "image.invert" -> filtered(
                image(a, "image"),
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                ),
            )

            /**
             * ⭐ The op that lets a CONTRIBUTOR write inpainting.
             *
             * `a` and `b` are latent handles; `mask` is an IMAGE handle, and the
             * host turns it into the bytes the backend wants — so a plugin
             * never sees base64 and never learns the wire format.
             *
             * ⚠⚠ mask = 1 takes `b`, the same convention as everywhere else in
             * this project (`backend-patches/README.md`). Stated at every layer
             * because getting it backwards replaces the wrong region and raises
             * no error.
             */
            "latent.blend" -> {
                val ops = latents
                    ?: throw IllegalStateException(
                        "latent.blend needs a backend and this host has none"
                    )
                val maskId = a.optString("mask")
                require(maskId.isNotEmpty()) { "latent.blend needs a \"mask\" image handle" }
                val png = images.png(maskId)
                    ?: throw IllegalArgumentException(
                        "unknown mask image handle \"$maskId\""
                    )
                val handle = ops.blend(a.getString("a"), a.getString("b"), png)
                JSONObject().put("latent", handle).toString()
            }

            // ⚠ Not a silent no-op and not a null. An unknown op means the
            // plugin and the host disagree about the API version, and a plugin
            // that gets `null` back would fail somewhere else entirely.
            else -> throw IllegalArgumentException("unknown host op \"$op\"")
        }
    }

    /**
     * ⚠ Default DENY, including when no plugin is running. A host call with no
     * caller is a bug in the executor rather than a policy question, and
     * allowing it "because it is probably ours" is exactly the hole a plugin
     * would find.
     */
    private fun permit(op: String) {
        val plugin = caller
            ?: throw Denied("host op \"$op\" called with no plugin running")
        val group = op.substringBefore('.')
        if (group !in plugin.permissions) {
            throw Denied(
                "plugin ${plugin.id} called \"$op\" without the \"$group\" permission -- " +
                    "add it to \"permissions\" in node.json"
            )
        }
    }

    private fun filtered(src: Bitmap, matrix: ColorMatrix): String {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return JSONObject().put("image", images.put(out)).toString()
    }

    /**
     * `src` with its alpha replaced by `mask`'s LUMINANCE.
     *
     * ⚠⚠ Luminance, not the mask's alpha channel. Masks here come from
     * `image.grayscale` and from decoded PNGs, both fully opaque — so a DST_IN
     * against their alpha would keep everything and the mask would appear to be
     * ignored. That is a silent wrong picture, which is why the conversion is
     * explicit rather than relying on the mask carrying transparency.
     */
    private fun maskedCopy(src: Bitmap, mask: Bitmap): Bitmap {
        val alphaOnly = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(alphaOnly).drawBitmap(mask, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        // Rec. 601 luma -> alpha.
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                    )
                )
            )
        })
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(alphaOnly, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        return out
    }

    private fun requireSize(w: Int, h: Int, op: String) {
        require(w in 1..8192 && h in 1..8192) { "$op to ${w}x$h is out of range (1..8192)" }
    }

    /**
     * ⚠ Parsed from a string rather than taken as an int: a plugin author
     * writes "#ff0088", and a colour written as a decimal int in a manifest is
     * unreadable. An unparseable colour is an error naming the value, not
     * silent black.
     */
    private fun color(spec: String): Int = try {
        Color.parseColor(if (spec.startsWith("#")) spec else "#" + spec)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("not a colour: \"" + spec + "\" (use #rrggbb or #aarrggbb)")
    }

    private fun image(args: JSONObject, field: String): Bitmap {
        val id = args.optString(field)
        require(id.isNotEmpty()) { "missing \"$field\" handle" }
        return images.get(id)
            ?: throw IllegalArgumentException(
                "unknown image handle \"$id\" -- it may have been evicted from the store"
            )
    }

    companion object {
        /**
         * Every permission group this app knows how to enforce, which is
         * exactly the set of op prefixes above.
         *
         * ⚠⚠ A manifest asking for anything else is REFUSED at load
         * (`Plugin.parse`). Ignoring an unknown permission would mean the app
         * had loaded a plugin whose intentions it cannot reason about, and the
         * first time this list grew, old builds would silently under-enforce.
         */
        val GROUPS = setOf("image", "latent")
    }
}
