package com.abrah.nightmare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * ⭐ The credit strip under a SHARED picture (the user, 2026-11-14, on by default: [Prefs.shareCredit]): a slim
 * near-black band added BELOW the picture, so no pixel of it is covered, with the app icon and "Made with Nightmare
 * Mobile", or "Created by <name> with Nightmare Mobile" when [Prefs.shareName] is set. The names are bold.
 * ⚠ Shares only: a gallery save, a clip and a shared flow go out as they are; the PNG's info chunks are written
 * after this, so they still describe the picture.
 */
object ShareCredit {

    const val APP = "Nightmare Mobile"

    /** The band's height for a [w] x [h] picture: ~4.5 % of its geometric mean, at least 32 px. */
    fun bandFor(w: Int, h: Int): Int = maxOf(32, Math.round(Math.sqrt(w.toDouble() * h) * 0.045).toInt())

    /**
     * ⭐ [format] (a string resource with `%1$s` / `%2$s`) cut into runs, each marked bold when it is an argument.
     * Pure, so the JVM tests read the strip's words without drawing it.
     */
    fun runs(format: String, args: List<String>): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var at = 0
        for (m in Regex("%(\\d)\\\$s").findAll(format)) {
            if (m.range.first > at) out += format.substring(at, m.range.first) to false
            args.getOrNull(m.groupValues[1].toInt() - 1)?.let { out += it to true }
            at = m.range.last + 1
        }
        if (at < format.length) out += format.substring(at) to false
        return out
    }

    /** The runs for [name] (blank: the app alone), in this phone's language. */
    fun runsFor(context: Context, name: String): List<Pair<String, Boolean>> {
        val n = name.trim().take(Prefs.SHARE_NAME_MAX)
        return if (n.isEmpty()) runs(context.getString(R.string.share_credit_plain), listOf(APP))
        else runs(context.getString(R.string.share_credit_named), listOf(n, APP))
    }

    /** ⭐ [src] with the strip under it; [src] is left as it was. */
    fun apply(context: Context, src: Bitmap, name: String): Bitmap {
        val w = src.width
        val h = src.height
        val band = bandFor(w, h)
        val out = Bitmap.createBitmap(w, h + band, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(BAND)
        c.drawBitmap(src, 0f, 0f, null)
        val pad = band * 0.3f
        val iconSize = Math.round(band * 0.62f)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { icon ->
            val top = h + (band - iconSize) / 2
            icon.setBounds(Math.round(pad), top, Math.round(pad) + iconSize, top + iconSize)
            icon.draw(c)
        }
        val regular = font(context, R.font.inter_regular, Typeface.NORMAL)
        val bold = font(context, R.font.inter_semibold, Typeface.BOLD)
        val runs = runsFor(context, name)
        val x0 = pad + iconSize + band * 0.25f
        val room = w - x0 - pad
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var size = band * 0.36f
        fun width(): Float = runs.sumOf { (t, b) -> paint.apply { typeface = if (b) bold else regular }.measureText(t).toDouble() }.toFloat()
        paint.textSize = size
        // ⚠ A narrow picture with a long name: the text shrinks to fit rather than run off the edge.
        val wide = width()
        if (wide > room && room > 0) { size *= room / wide; paint.textSize = size }
        val baseline = h + band / 2f - (paint.ascent() + paint.descent()) / 2f
        var x = x0
        for ((t, b) in runs) {
            paint.typeface = if (b) bold else regular
            paint.color = if (b) STRONG else SOFT
            c.drawText(t, x, baseline, paint)
            x += paint.measureText(t)
        }
        return out
    }

    private fun font(context: Context, id: Int, style: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, style)

    // ⚠ Literal colours: drawn INTO a picture that leaves the app, not themed UI (the UI skill's "on a picture" case).
    private const val BAND = 0xFF0E0D12.toInt()
    private const val SOFT = 0xFFC8C4D6.toInt()
    private const val STRONG = 0xFFFFFFFF.toInt()
}
