package com.abrah.nightmare.portrait

/**
 * ⭐ LivePortrait's JNI surface (`nmportrait.cpp`, inside libnmqnn.so). One session per process, behind one lock.
 * ⚠ [com.abrah.nightmare.npu.NativeQnn.init] first ([LivePortrait.open] does it). Pixels are ARGB ints, as a
 * Bitmap's getPixels gives them; every call that returns null has said why in [lastError].
 */
object NativePortrait {

    init {
        com.abrah.nightmare.npu.NativeQnn.ensureLoaded()
    }

    /** The six files of [tier] (`yoloface_<tier>.bin` …) from [dir]. */
    @JvmStatic external fun open(dir: String, tier: String): Boolean

    /** The 14 sliders ([LivePortrait.SLIDERS] order, -1..1) on every face, or the largest. Null: no face, or a failure. */
    @JvmStatic external fun edit(argb: IntArray, w: Int, h: Int, sliders: FloatArray, largestOnly: Boolean): IntArray?

    /** The animator's photo: its largest face is the one that moves. */
    @JvmStatic external fun setPortrait(argb: IntArray, w: Int, h: Int): Boolean

    /** One driver frame: the portrait (its own size) moved like the driver's face moved since its FIRST frame. */
    @JvmStatic external fun animate(argb: IntArray, w: Int, h: Int): IntArray?

    /** Milliseconds the last [edit] or [animate] took. */
    @JvmStatic external fun lastMs(): Double

    @JvmStatic external fun lastError(): String

    /** Frees the five contexts (~300 MB resident). */
    @JvmStatic external fun close()
}
