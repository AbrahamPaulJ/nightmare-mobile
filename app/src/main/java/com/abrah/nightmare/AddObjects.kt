package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * ⭐⭐⭐ **Add Objects** — a poor man's VTON (the user's design, 2026-09-23).
 *
 * Objects are cut out of a SECOND photo with the mask tools, pasted onto the
 * inpaint node's photo where the person drags them, and the model repaints only
 * a feathered BAND along each pasted edge. The middle of each object stays
 * pixel-exact — which is the whole point: a real garment's print and texture
 * survive, and the model's job shrinks to blending edges, light and shadow.
 *
 * ⚠⚠ Pure pixels BEFORE the encode. The NPU graph is frozen and untouched: the
 * sampler is simply handed a photo that already has the object on it, the same
 * mechanism upstream's sketch tool uses (local-dream PR #302).
 *
 * ⚠ Stored on the node ([PARAM]), so it is saved with the flow and re-editable.
 * Each object carries the source photo, the mask chosen on it (as ops — strokes,
 * taps, picks — never pixels), the region of that mask it is, and where it sits
 * on the node's photo.
 */
object AddObjects {

    /** ⭐ The checkbox, "Enable Add Objects". Inpaint only. */
    const val ENABLE = "add_objects"

    /** The placed objects, [encode]d — sorted by [Placed.layer], which is the draw order. */
    const val PARAM = "objects"

    /**
     * ⭐⭐ The objects exactly as they were when ✓ kept them — what Reset puts
     * a layer back to (the user's call, 2026-09-26: *"reset btn (initial
     * object load state)"*). Same record as [PARAM]; a layer's entries leave
     * only when the LAYER is deleted, so Reset also brings back an object
     * removed since.
     */
    const val ORIGINAL = "objects_placed"

    /**
     * ⚠ LEGACY — the 2026-09-24 Layer 2 mask (its rings, and strokes painted
     * there). Object layers take no brush since 2026-09-26: a ring is drawn
     * round every object, always. [migrated] folds what was painted here into
     * the image layer; nothing reads it after that.
     */
    const val LAYER = "objects_mask"

    /**
     * ⚠ LEGACY — the one ring width every object shared (2026-09-24). Each
     * object carries its own now ([Placed.ring]), which is what lets a layer
     * be deleted and the next renumbered without a width changing hands.
     */
    const val RING = "object_ring"

    /** 24 px across at 512 — Size reads `radius * 2 * 512`. */
    const val RING_DEFAULT = 12f / 512f

    /**
     * ⭐⭐⭐ **The layers.** Layer 1 is the image — all the masking happens
     * there. Every Add object makes ONE object layer holding everything cut in
     * that go, numbered from [FIRST_LAYER]; [MAX_LAYERS] in all (the user's
     * call, 2026-09-26: *"allow for upto 3 layers (layer 2 and 3 for objs)"*).
     */
    const val IMAGE_LAYER = 1
    const val FIRST_LAYER = 2
    const val MAX_LAYERS = 3

    /**
     * ⚠⚠⚠ How far the ring reaches INSIDE the object — barely: just enough to
     * close the seam, whatever the ring's width. The user, 2026-09-24: *"you
     * are not allowed to paint that much on the inside, just very very little
     * to fix seams"*. A fraction of the object's longer edge: ~2 px at the
     * ring's working size.
     */
    const val BAND_IN_FRAC = 0.01f

    /** ⚠ A connected region smaller than this share of the mask is a stray speck. */
    private const val MIN_REGION = 0.004f

    /** The longest edge the object mask is split and cut at. */
    private const val WORK_EDGE = 1024

    /**
     * One object. [bx]..[bh] is its region of the SOURCE photo (normalised);
     * [x], [y] is where the top-left of its UNTURNED box sits on the node's
     * photo and [w] that box's width, both normalised to that photo. Its height
     * follows from the region's shape.
     *
     * ⭐⭐ [rot] (degrees, clockwise) and [flip] (mirrored left to right) turn
     * it about the box's CENTRE, so turning never moves it — the user's call,
     * 2026-09-26. [ring] is its ring's width, a radius as a fraction of the
     * photo's width, like a stroke's `radiusFrac`.
     *
     * ⭐ [id] is what its ring op on the image layer names
     * ([MaskOp.ObjectRing]) — stable across moves, removals, Reset and a
     * deleted layer, which an index into the list is not.
     */
    data class Placed(
        val uri: String,
        val ops: String,
        val bx: Float, val by: Float, val bw: Float, val bh: Float,
        val x: Float, val y: Float, val w: Float,
        val layer: Int = FIRST_LAYER,
        val rot: Float = 0f,
        val flip: Boolean = false,
        val ring: Float = RING_DEFAULT,
        val id: Int = 0,
    )

    // ⚠ Unit and record separators: a mask string already uses ; : , | ~ !
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun encode(list: List<Placed>): String = list.joinToString(RECORD.toString()) { o ->
        listOf(
            o.uri, o.ops, o.bx, o.by, o.bw, o.bh, o.x, o.y, o.w,
            o.layer, o.rot, if (o.flip) 1 else 0, o.ring, o.id,
        ).joinToString(FIELD.toString())
    }

    /**
     * ⚠ Older records, read as they were meant: 9 fields (before layers held
     * objects, 2026-09-24) land on Layer 2, unturned, with [legacyRing] — the
     * width every object shared then ([RING]); 13 fields (1.6.034) have no
     * [Placed.id]. Both get their position + 1 as an id until [migrated]
     * writes real ones.
     */
    fun decode(s: String?, legacyRing: Float = RING_DEFAULT): List<Placed> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(RECORD).mapIndexedNotNull { i, r ->
            val f = r.split(FIELD)
            if (f.size != 9 && f.size != 13 && f.size != 14) return@mapIndexedNotNull null
            val n = f.drop(2).map { it.toFloatOrNull() ?: return@mapIndexedNotNull null }
            val base = Placed(f[0], f[1], n[0], n[1], n[2], n[3], n[4], n[5], n[6], ring = legacyRing, id = i + 1)
            if (f.size == 9) base
            else base.copy(
                layer = n[7].toInt().coerceIn(FIRST_LAYER, MAX_LAYERS),
                rot = n[8],
                flip = n[9] != 0f,
                ring = n[10].takeIf { it > 0f } ?: RING_DEFAULT,
                id = if (f.size == 14) n[11].toInt() else i + 1,
            )
        }
    }

    /** ⚠ Whether every record carries its own id — false for anything before 1.6.035. */
    private fun hasIds(s: String?): Boolean =
        s.isNullOrBlank() || s.split(RECORD).all { it.split(FIELD).size == 14 }

    private fun legacyRing(params: Map<String, String>): Float =
        params[RING]?.toFloatOrNull()?.takeIf { it > 0f } ?: RING_DEFAULT

    private fun stored(params: Map<String, String>): List<Placed> = decode(params[PARAM], legacyRing(params))

    /** The node's objects — only while Enable Add Objects is ticked. */
    fun of(params: Map<String, String>): List<Placed> =
        if (params[ENABLE].equals("true", ignoreCase = true)) stored(params) else emptyList()

    /** The objects as they were first placed ([ORIGINAL]). */
    fun originalsOf(params: Map<String, String>): List<Placed> = decode(params[ORIGINAL], legacyRing(params))

    /** ⭐ The object layers in use, in order — contiguous from [FIRST_LAYER]. */
    fun layers(objects: List<Placed>): List<Int> = objects.map { it.layer }.distinct().sorted()

    /** ⭐ Where the next Add object goes, or null when every layer is taken. */
    fun nextLayer(objects: List<Placed>): Int? =
        ((objects.maxOfOrNull { it.layer } ?: IMAGE_LAYER) + 1).takeIf { it <= MAX_LAYERS }

    /** ⚠ Stable, so objects keep their order inside a layer. */
    private fun sorted(list: List<Placed>) = list.sortedBy { it.layer }

    /** ⭐ [o] with an id no object — placed now or remembered for Reset — has. */
    fun withNewId(params: Map<String, String>, o: Placed): Placed =
        o.copy(id = ((stored(params) + originalsOf(params)).maxOfOrNull { it.id } ?: 0) + 1)

    /**
     * ⭐ The params after [o] is kept: on its layer, and remembered as placed.
     * ⚠ [o] carries its id already ([withNewId]); its ring goes on the image
     * layer through [freshRings], which the caller applies to the mask.
     */
    fun added(params: Map<String, String>, o: Placed): Map<String, String> = mapOf(
        PARAM to encode(sorted(stored(params) + o)),
        ORIGINAL to encode(sorted(originalsOf(params) + o)),
    )

    /** ⭐ Object [i] (an index into [PARAM]) moved, turned, flipped or resized. */
    fun changed(params: Map<String, String>, i: Int, o: Placed): Map<String, String> {
        val list = stored(params).toMutableList()
        if (i !in list.indices) return emptyMap()
        list[i] = o
        return mapOf(PARAM to encode(sorted(list)))
    }

    /**
     * ⭐ Object [i] taken off its layer. ⚠ Its ORIGINAL stays: Reset brings it
     * back, and Undo does too.
     */
    fun removed(params: Map<String, String>, i: Int): Map<String, String> =
        mapOf(PARAM to encode(stored(params).filterIndexed { j, _ -> j != i }))

    /** ⭐ Every object on [layer] given ring width [ring]. */
    fun ringed(params: Map<String, String>, layer: Int, ring: Float): Map<String, String> =
        mapOf(PARAM to encode(stored(params).map { if (it.layer == layer) it.copy(ring = ring) else it }))

    /** ⭐⭐ [layer] put back exactly as ✓ left it — its objects, nothing else. */
    fun reset(params: Map<String, String>, layer: Int): Map<String, String> {
        val others = stored(params).filter { it.layer != layer }
        return mapOf(PARAM to encode(sorted(others + originalsOf(params).filter { it.layer == layer })))
    }

    /**
     * ⭐⭐ [layer] deleted — its objects and what Reset would restore — and
     * every layer above it moved down one, so the layers stay 2, 3 with no gap.
     */
    fun layerDeleted(params: Map<String, String>, layer: Int): Map<String, String> {
        fun drop(list: List<Placed>) = list.filter { it.layer != layer }
            .map { if (it.layer > layer) it.copy(layer = it.layer - 1) else it }
        return mapOf(
            PARAM to encode(drop(stored(params))),
            ORIGINAL to encode(drop(originalsOf(params))),
        )
    }

    /**
     * ⚠⚠ A node saved by an older build, brought to today's shape:
     *  - 2026-09-24's Layer 2 (`objects_mask`, `object_ring`): every object
     *    gets the one ring width it was drawn with, and the strokes PAINTED on
     *    that layer move onto the image layer. Its rings, erases and inverts
     *    are dropped — they only ever acted on that layer.
     *  - 1.6.034 (and older): objects get real ids, a Reset point if they had
     *    none, and their RINGS go onto the image layer as [MaskOp.ObjectRing]s
     *    (2026-09-26 — the rings used to be drawn beside the mask, untouchable).
     * Null when there is nothing to do.
     */
    fun migrated(params: Map<String, String>, opsKey: String): Map<String, String>? {
        val legacyLayer = LAYER in params || RING in params
        if (!legacyLayer && hasIds(params[PARAM]) && hasIds(params[ORIGINAL])) return null
        val out = params.toMutableMap()
        out.remove(LAYER)
        out.remove(RING)
        val list = stored(params)
        var image = MaskState.decode(params[opsKey])
        if (params[PARAM] != null) {
            out[PARAM] = encode(list)
            // ⚠ The reset point keeps the ids of the objects it matches, by
            // the source region they were cut from.
            var next = (list.maxOfOrNull { it.id } ?: 0) + 1
            val originals = if (ORIGINAL in params) originalsOf(params) else list
            out[ORIGINAL] = encode(
                originals.map { o ->
                    list.firstOrNull { it.uri == o.uri && it.ops == o.ops && it.bx == o.bx && it.by == o.by && it.bw == o.bw && it.bh == o.bh }
                        ?.let { o.copy(id = it.id) } ?: o.copy(id = next++)
                },
            )
        }
        val painted = MaskState.decode(params[LAYER]).ops.filterIsInstance<MaskOp.Stroke>()
        image = image.copy(ops = image.ops + painted)
        if (!hasIds(params[PARAM])) image = freshRings(image, list.map { it.id })
        if (painted.isNotEmpty() || !hasIds(params[PARAM])) out[opsKey] = image.encode()
        return out
    }

    /**
     * ⭐⭐ The rings of [ids] at the END of the image layer — clean rings over
     * everything painted so far. Called whenever an object is kept, moved,
     * turned, flipped, resized, re-ringed or Reset: the user's call
     * (2026-09-26), a moved object gets a NEW ring and the edits made to the
     * old one stay where they were painted. A ring a Clear removed comes back.
     */
    fun freshRings(image: MaskState, ids: Collection<Int>): MaskState = image.copy(
        ops = image.ops.filterNot { it is MaskOp.ObjectRing && it.id in ids } + ids.map { MaskOp.ObjectRing(it) },
    )

    /** ⭐ The rings of [ids] taken off the image layer — their objects are gone. */
    fun dropRings(image: MaskState, ids: Collection<Int>): MaskState = image.copy(
        ops = image.ops.filterNot { it is MaskOp.ObjectRing && it.id in ids },
    )

    /**
     * ⭐⭐⭐ **Every ring op on the image layer turned into its ring**, round
     * where its object sits NOW and as wide as its own [Placed.ring], in the
     * photo's coordinates. The sampler, the node's preview, the inspector's
     * tile and the mask window all call this, so a ring is drawn one way.
     * ⚠ A ring whose object is gone (Add Objects unticked, the object removed)
     * or whose cut is not ready draws nothing.
     */
    fun resolveRings(
        image: MaskState,
        objects: List<Placed>,
        cuts: List<Pair<Placed, Bitmap>>,
        photoW: Int,
        photoH: Int,
    ): MaskState {
        if (image.ops.none { it is MaskOp.ObjectRing }) return image
        return image.copy(
            ops = image.ops.mapNotNull { op ->
                if (op !is MaskOp.ObjectRing) op
                else {
                    val o = objects.firstOrNull { it.id == op.id } ?: return@mapNotNull null
                    val cut = cutFor(o, cuts) ?: return@mapNotNull null
                    band(o, cut, photoW, photoH, o.ring)
                }
            },
        )
    }

    /** ⚠ [image] without the rings of objects that are not there — for "is anything masked". */
    fun withoutDeadRings(image: MaskState, objects: List<Placed>): MaskState = image.copy(
        ops = image.ops.filterNot { op -> op is MaskOp.ObjectRing && objects.none { it.id == op.id } },
    )

    /**
     * ⚠ The cut for [o] among [cuts] — matched on the SOURCE REGION, not the
     * whole record, so an object moved, turned or on another layer still finds
     * the pixels it was cut from.
     */
    fun cutFor(o: Placed, cuts: List<Pair<Placed, Bitmap>>): Bitmap? =
        cuts.firstOrNull { (c, _) ->
            c.uri == o.uri && c.ops == o.ops && c.bx == o.bx && c.by == o.by && c.bw == o.bw && c.bh == o.bh
        }?.second


    // ---- turning -----------------------------------------------------------

    /** Degrees either side of a right angle that snap to it. */
    const val SNAP = 5f

    /**
     * ⭐ [deg] snapped to a right angle when within [SNAP] of one, so a pinch
     * that only meant to resize leaves a straight object straight. In [0, 360).
     */
    fun snapAngle(deg: Float): Float {
        val a = ((deg % 360f) + 360f) % 360f
        val right = Math.round(a / 90f) * 90f
        return (if (kotlin.math.abs(a - right) <= SNAP) right else a) % 360f
    }

    /**
     * ⭐⭐ Whether ([u], [v]) — normalised to a [picW]×[picH] picture — lands on
     * [o]'s turned box. The mask window's tap-to-select; pure arithmetic.
     */
    fun contains(o: Placed, cutW: Int, cutH: Int, picW: Int, picH: Int, u: Float, v: Float): Boolean {
        val w = o.w * picW
        val h = w * cutH / cutW.coerceAtLeast(1)
        val dx = u * picW - (o.x * picW + w / 2f)
        val dy = v * picH - (o.y * picH + h / 2f)
        val r = Math.toRadians(-o.rot.toDouble())
        val lx = dx * kotlin.math.cos(r) - dy * kotlin.math.sin(r)
        val ly = dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)
        return kotlin.math.abs(lx) <= w / 2f && kotlin.math.abs(ly) <= h / 2f
    }

    /**
     * ⭐⭐⭐ THE transform from [cut]'s pixels to where they land on a
     * [photoW]×[photoH] picture: scaled into the box, mirrored if [Placed.flip],
     * turned by [Placed.rot] about the box's centre. [composite] draws with it,
     * and the mask window draws the same three steps in Compose.
     */
    fun matrix(o: Placed, cut: Bitmap, photoW: Int, photoH: Int): android.graphics.Matrix {
        val r = placedRect(o, cut, photoW, photoH)
        return android.graphics.Matrix().apply {
            setTranslate(-cut.width / 2f, -cut.height / 2f)
            postScale(
                r.width() / cut.width.coerceAtLeast(1) * (if (o.flip) -1f else 1f),
                r.height() / cut.height.coerceAtLeast(1),
            )
            postRotate(o.rot)
            postTranslate(r.centerX(), r.centerY())
        }
    }

    private fun turned(o: Placed) = o.flip || o.rot % 360f != 0f

    /**
     * ⭐ A source photo, decoded as `core.image` decodes one — the same
     * [LoadImageNode.MAX_EDGE], EXIF-upright. Null when it can no longer be
     * read (a revoked or deleted photo). ⚠ Blocking; off the main thread.
     */
    fun load(context: android.content.Context, uri: String): Bitmap? = runCatching {
        val bytes = if (uri.startsWith("content://")) {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        } else java.io.File(uri).readBytes()
        bytes?.let { ImageStore().decode(it, LoadImageNode.MAX_EDGE) }
    }.getOrNull()

    /**
     * ⭐ A chosen mask's taps and picks turned into regions on [source], by the
     * same two models the mask editor uses. ⚠ Blocking; off the main thread.
     */
    fun resolveOn(context: android.content.Context, source: Bitmap, mask: MaskState): MaskState =
        MaskTaps.resolve(
            mask,
            pick = { t -> com.abrah.nightmare.segment.Parser.pick(context, source, t) },
        ) { x, y -> com.abrah.nightmare.segment.Segmenter.segment(context, source, x, y)?.candidates }

    // ---- pixels ------------------------------------------------------------

    /**
     * The object mask on [source] at [WORK_EDGE], white = chosen. [resolved] is
     * the chosen mask with its taps and picks already turned into regions
     * ([MaskTaps.resolve]).
     */
    fun raster(source: Bitmap, resolved: MaskState): Bitmap {
        val s = (WORK_EDGE.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
        return MaskRaster.rasterise(
            resolved.copy(growFrac = 0f, featherFrac = 0f),
            (source.width * s).toInt().coerceAtLeast(1),
            (source.height * s).toInt().coerceAtLeast(1),
        )
    }

    /**
     * ⭐ Each separate region of [mask] as its bounding box, normalised —
     * the user's call, 2026-09-23: several objects picked at once are placed
     * EACH ON ITS OWN. Largest first; specks dropped.
     */
    fun regions(mask: Bitmap): List<RectF> {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h).also { mask.getPixels(it, 0, w, 0, 0, w, h) }
        val on = BooleanArray(w * h) { (px[it] shr 16 and 0xFF) > 127 }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Pair<Int, RectF>>()
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var n = 0
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (sp > 0) {
                val i = stack[--sp]
                n++
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && on[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && on[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && on[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && on[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (n < w * h * MIN_REGION) continue
            out += n to RectF(
                x0.toFloat() / w, y0.toFloat() / h, (x1 + 1f) / w, (y1 + 1f) / h,
            )
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * ⭐ The object itself: [source]'s pixels inside its region, transparent
     * wherever [mask] did not choose them.
     */
    fun cutout(source: Bitmap, mask: Bitmap, o: Placed): Bitmap {
        val l = (o.bx * source.width).toInt().coerceIn(0, source.width - 1)
        val t = (o.by * source.height).toInt().coerceIn(0, source.height - 1)
        val cw = (o.bw * source.width).toInt().coerceIn(1, source.width - l)
        val ch = (o.bh * source.height).toInt().coerceIn(1, source.height - t)
        val out = Bitmap.createBitmap(source, l, t, cw, ch).copy(Bitmap.Config.ARGB_8888, true)
        // ⚠⚠⚠ **The white box.** A JPEG decodes with `hasAlpha = false`, the
        // copy inherits it, and a bitmap that says it has no alpha is DRAWN
        // OPAQUE whatever its pixels hold — so the whole rectangle around the
        // object, background and all, was pasted (reported twice, 2026-09-23).
        out.setHasAlpha(true)
        // The mask's matching region, scaled to the cut.
        val ml = (o.bx * mask.width).toInt().coerceIn(0, mask.width - 1)
        val mt = (o.by * mask.height).toInt().coerceIn(0, mask.height - 1)
        val mw = (o.bw * mask.width).toInt().coerceIn(1, mask.width - ml)
        val mh = (o.bh * mask.height).toInt().coerceIn(1, mask.height - mt)
        val m = Bitmap.createScaledBitmap(Bitmap.createBitmap(mask, ml, mt, mw, mh), cw, ch, true)
        val a = IntArray(cw * ch).also { m.getPixels(it, 0, cw, 0, 0, cw, ch) }
        val c = IntArray(cw * ch).also { out.getPixels(it, 0, cw, 0, 0, cw, ch) }
        for (i in c.indices) {
            val alpha = a[i] shr 16 and 0xFF
            c[i] = (c[i] and 0x00FFFFFF) or (alpha shl 24)
        }
        out.setPixels(c, 0, cw, 0, 0, cw, ch)
        return out
    }

    /** Where [o] lands on a [photoW]×[photoH] picture, in pixels. */
    fun placedRect(o: Placed, cut: Bitmap, photoW: Int, photoH: Int): RectF {
        val left = o.x * photoW
        val top = o.y * photoH
        val width = o.w * photoW
        val height = width * cut.height / cut.width.coerceAtLeast(1)
        return RectF(left, top, left + width, top + height)
    }

    /**
     * ⭐ [photo] with every cut-out drawn where it was placed. A new bitmap.
     *
     * ⚠⚠ The node's mask never touches the objects: the eraser takes MASK
     * away and nothing else — the user's call, 2026-09-24, reversing the
     * 2026-09-23 rule that an Erase stroke cut the object too
     * (`docs/LEGACY.md`). An object comes off with the bin on its row, or Undo.
     *
     * [alpha] < 1 draws the objects see-through — the mask window's Layer 1,
     * so the photo under them can be painted (2026-09-24). The render is 1.
     */
    fun composite(photo: Bitmap, placed: List<Pair<Placed, Bitmap>>, alpha: Float = 1f): Bitmap {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        if (placed.isEmpty()) return out
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        for ((o, cut) in placed) {
            // ⚠ An unturned object keeps the plain rect draw it always had.
            if (turned(o)) c.drawBitmap(cut, matrix(o, cut, photo.width, photo.height), paint)
            else c.drawBitmap(cut, null, placedRect(o, cut, photo.width, photo.height), paint)
        }
        return out
    }

    /**
     * ⭐⭐⭐ The RING round [cut], placed where the object sits — what the model
     * repaints of it. A hard-edged band, no feathering (the user's call,
     * 2026-09-24: *"apply thick brush with 0 feathering"*):
     *  - OUTSIDE, onto the photo, as wide as [ring] says — the Size slider on
     *    the object's layer, a radius in photo-width fractions, so the ring is
     *    one brush diameter across;
     *  - INSIDE, only [BAND_IN_FRAC] of the object — a couple of pixels, to
     *    close the seam and nothing more.
     *
     * ⚠ [MaskOp.Placed.exact], so the node's grow and feather cannot carry it
     * any further in. ⚠ Cached per (cut, width, turn): a preview pass asks
     * again for the same ring on every edit.
     *
     * ⭐⭐ A TURNED object ([Placed.rot], [Placed.flip]) gets its ring turned
     * the same way about the same centre, on the box the turn sweeps out —
     * [MaskOp.Placed] is an upright rectangle, so the turn lives in its pixels.
     */
    fun band(o: Placed, cut: Bitmap, photoW: Int, photoH: Int, ring: Float = o.ring): MaskOp.Placed {
        val rect = placedRect(o, cut, photoW, photoH)
        // ⚠ Worked small: a hard ring does not need the cut's full resolution.
        val s = (RING_EDGE.toFloat() / maxOf(cut.width, cut.height)).coerceAtMost(1f)
        val cw = (cut.width * s).toInt().coerceAtLeast(1)
        val ch = (cut.height * s).toInt().coerceAtLeast(1)
        // The ring's outer width, photo px -> work px.
        val outPx = (2f * ring * photoW) * cw / rect.width().coerceAtLeast(1f)
        val inPx = (maxOf(cw, ch) * BAND_IN_FRAC).coerceAtLeast(1.5f)
        val pad = kotlin.math.ceil(outPx).toInt() + 2
        val key = RingKey(cut, cw, ch, Math.round(outPx * 4f), Math.round(inPx * 4f))
        val alpha = synchronized(ringCache) { ringCache[key] } ?: ringAlpha(cut, cw, ch, pad, outPx, inPx).also {
            synchronized(ringCache) { ringCache[key] = it }
        }
        // The padded ring covers the placed rect grown by the padding (in cut pixels).
        val gx = rect.width() * pad / cw
        val gy = rect.height() * pad / ch
        if (!turned(o)) return MaskOp.Placed(
            alpha,
            (rect.left - gx) / photoW,
            (rect.top - gy) / photoH,
            (rect.width() + 2 * gx) / photoW,
            (rect.height() + 2 * gy) / photoH,
            exact = true,
        )
        val tkey = TurnKey(key, Math.round(o.rot * 10f), o.flip)
        val t = synchronized(turnCache) { turnCache[tkey] } ?: turn(alpha, o.rot, o.flip).also {
            synchronized(turnCache) { turnCache[tkey] = it }
        }
        // Photo px per ring px — one number: the cut keeps its shape in the box.
        val px = (rect.width() + 2 * gx) / alpha.width
        val hw = t.width * px / 2f
        val hh = t.height * px / 2f
        return MaskOp.Placed(
            t,
            (rect.centerX() - hw) / photoW,
            (rect.centerY() - hh) / photoH,
            2f * hw / photoW,
            2f * hh / photoH,
            exact = true,
        )
    }

    /** [a] mirrored if [flip], then turned [rot] degrees clockwise, on the box that holds it. */
    private fun turn(a: Bitmap, rot: Float, flip: Boolean): Bitmap {
        val m = android.graphics.Matrix().apply {
            setTranslate(-a.width / 2f, -a.height / 2f)
            postScale(if (flip) -1f else 1f, 1f)
            postRotate(rot)
        }
        val box = RectF(0f, 0f, a.width.toFloat(), a.height.toFloat()).also { m.mapRect(it) }
        val out = Bitmap.createBitmap(
            kotlin.math.ceil(box.width()).toInt().coerceAtLeast(1),
            kotlin.math.ceil(box.height()).toInt().coerceAtLeast(1),
            Bitmap.Config.ALPHA_8,
        )
        m.postTranslate(out.width / 2f, out.height / 2f)
        Canvas(out).drawBitmap(a, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private data class TurnKey(val ring: RingKey, val rot10: Int, val flip: Boolean)

    private val turnCache = object : LinkedHashMap<TurnKey, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TurnKey, Bitmap>) = size > 16
    }

    /** The longest edge a ring is worked at. */
    private const val RING_EDGE = 384

    private data class RingKey(val cut: Bitmap, val cw: Int, val ch: Int, val out4: Int, val in4: Int)

    /** ⚠ Keyed on the cut OBJECT: [cuts] hands back the same one while it is cached. */
    private val ringCache = object : LinkedHashMap<RingKey, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RingKey, Bitmap>) = size > 16
    }

    private fun ringAlpha(cut: Bitmap, cw: Int, ch: Int, pad: Int, outPx: Float, inPx: Float): Bitmap {
        val pw = cw + 2 * pad
        val ph = ch + 2 * pad
        val small = Bitmap.createScaledBitmap(cut, cw, ch, true)
        val src = IntArray(cw * ch).also { small.getPixels(it, 0, cw, 0, 0, cw, ch) }
        val a = FloatArray(pw * ph)
        for (y in 0 until ch) for (x in 0 until cw) {
            a[(y + pad) * pw + x + pad] = (src[y * cw + x] ushr 24) / 255f
        }
        fillHoles(a, pw, ph)
        val inside = BooleanArray(a.size) { a[it] >= 0.5f }
        // Distance to the object from outside, and to the photo from inside.
        val toObject = distance(inside, pw, ph)
        val toPhoto = distance(BooleanArray(a.size) { !inside[it] }, pw, ph)
        val ring = ByteArray(pw * ph)
        for (i in a.indices) {
            val on = if (inside[i]) toPhoto[i] <= inPx else toObject[i] <= outPx
            if (on) ring[i] = 0xFF.toByte()
        }
        return com.abrah.nightmare.segment.SegmentModel.alphaBitmap(ring, pw, ph)
    }

    /**
     * Distance, in pixels, from every pixel to the nearest [target] pixel — a
     * two-pass 3-4 chamfer, within a few percent of Euclidean, which a hard
     * ring a few pixels wide cannot show.
     */
    private fun distance(target: BooleanArray, w: Int, h: Int): FloatArray {
        val far = Int.MAX_VALUE / 4
        val d = IntArray(w * h) { if (target[it]) 0 else far }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = minOf(v, d[i - 1] + 3)
            if (y > 0) {
                v = minOf(v, d[i - w] + 3)
                if (x > 0) v = minOf(v, d[i - w - 1] + 4)
                if (x < w - 1) v = minOf(v, d[i - w + 1] + 4)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = minOf(v, d[i + 1] + 3)
            if (y < h - 1) {
                v = minOf(v, d[i + w] + 3)
                if (x < w - 1) v = minOf(v, d[i + w + 1] + 4)
                if (x > 0) v = minOf(v, d[i + w - 1] + 4)
            }
            d[i] = v
        }
        return FloatArray(d.size) { d[it] / 3f }
    }

    /**
     * ⭐⭐ Every transparent pixel the OUTSIDE cannot reach becomes solid, so the
     * ring follows the object's outer outline only — the user's call,
     * 2026-09-24: *"only mask the outside edges, do not draw mask on the inner
     * edges"*. A hole inside a cut (the gap under a strap, a speck the brush
     * missed) is not a seam with the photo worth repainting.
     *
     * ⚠ Flooded from the border, which [band]'s padding guarantees is empty.
     * Anti-aliased edge pixels under 0.5 that touch the outside stay as they
     * are, so the outline keeps its softness.
     */
    private fun fillHoles(a: FloatArray, w: Int, h: Int) {
        val outside = BooleanArray(a.size)
        val stack = IntArray(a.size)
        var sp = 0
        fun seed(i: Int) {
            if (!outside[i] && a[i] < 0.5f) { outside[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (i >= w) seed(i - w)
            if (i < a.size - w) seed(i + w)
        }
        for (i in a.indices) if (!outside[i]) a[i] = 1f
    }

    /**
     * ⭐⭐⭐ **The one render of a node's objects**: the photo with them on it,
     * and the cuts [resolveRings] draws their rings from. The sampler, the canvas preview and the mask
     * window all call this, so what is seen is what is rendered.
     *
     * [load] decodes a source photo; [resolve] turns a chosen mask's taps and
     * picks into regions on that photo. An object whose source cannot be read
     * is skipped by [load] returning null, which the caller reports.
     */
    class Render(
        val photo: Bitmap,
        /** Each object with its cut — what [resolveRings] draws the rings from. */
        val cuts: List<Pair<Placed, Bitmap>>,
        val missing: List<String>,
    )

    /**
     * ⚠ The decoded source and its object mask, per (uri, ops) — the sampler,
     * the canvas preview and the mask window all ask for the same ones, and a
     * decode plus a segment pass is up to a second each time. Small: four.
     */
    private val sources = object : LinkedHashMap<Pair<String, String>, Pair<Bitmap, Bitmap>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Pair<Bitmap, Bitmap>>) =
            size > 4
    }

    /**
     * ⭐ Each object cut out of its source. Objects whose source cannot be
     * read are left out and named in the second list.
     */
    fun cuts(
        objects: List<Placed>,
        load: (String) -> Bitmap?,
        resolve: (Bitmap, MaskState) -> MaskState,
    ): Pair<List<Pair<Placed, Bitmap>>, List<String>> {
        val out = ArrayList<Pair<Placed, Bitmap>>()
        val missing = ArrayList<String>()
        for (o in objects) {
            val key = o.uri to o.ops
            var pair = synchronized(sources) { sources[key] }
            if (pair == null) {
                val source = load(o.uri)
                if (source == null) {
                    missing += o.uri
                    continue
                }
                pair = source to raster(source, resolve(source, MaskState.decode(o.ops)))
                synchronized(sources) { sources[key] = pair }
            }
            val ck = CutKey(o.uri, o.ops, o.bx, o.by, o.bw, o.bh)
            val cut = synchronized(cutCache) { cutCache[ck] }
                ?: cutout(pair.first, pair.second, o).also { synchronized(cutCache) { cutCache[ck] = it } }
            out += o to cut
        }
        return out to missing.distinct()
    }

    /**
     * ⚠ A cut depends on its source region, never on where it is placed — so
     * moving an object, or a preview pass on an unrelated edit, reuses it
     * rather than cutting the object out again. It is also what lets
     * [ringCache] key on the cut itself.
     */
    private data class CutKey(val uri: String, val ops: String, val bx: Float, val by: Float, val bw: Float, val bh: Float)

    private val cutCache = object : LinkedHashMap<CutKey, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CutKey, Bitmap>) = size > 12
    }

    fun render(
        photo: Bitmap,
        objects: List<Placed>,
        load: (String) -> Bitmap?,
        resolve: (Bitmap, MaskState) -> MaskState,
    ): Render {
        val (cuts, missing) = cuts(objects, load, resolve)
        return Render(
            composite(photo, cuts),
            cuts,
            missing,
        )
    }
}
