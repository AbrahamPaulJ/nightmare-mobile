package com.abrah.nightmare.npu

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Write the generated frames out as a real H.264 MP4.
 *
 * `HANDOFF.md` listed "nothing writes an MP4" as known polish -- playback was a Compose
 * frame stepper, so the video existed only inside the app and could not be shared, sent
 * or kept. A video generator that cannot hand you a video file is not finished.
 *
 * Encoding goes through MediaCodec with an **input Surface** rather than by packing YUV
 * buffers by hand: the frames are already Bitmaps, drawing them onto the encoder's
 * Surface lets the driver do the RGB->YUV conversion, and it avoids reimplementing a
 * colour conversion whose bugs look like model artefacts.
 *
 * The file lands in MediaStore under Movies/Neodragon so it shows up in the gallery,
 * rather than in the app's private directory where the user cannot reach it.
 */
object VideoWriter {

    private const val MIME = "video/avc"
    private const val I_FRAME_INTERVAL = 1          // seconds
    private const val TIMEOUT_US = 10_000L

    /**
     * @param frames all frames, all the same size.
     * @param fps    playback rate. The pipeline generates 49 frames for a 2 s clip, so
     *               24 is the rate the clip is meant to be seen at.
     * @return the MediaStore uri of the finished file.
     */
    fun write(
        ctx: Context,
        frames: List<Bitmap>,
        fps: Int = 24,
        displayName: String = "nightmare_${System.currentTimeMillis()}.mp4",
        log: (String) -> Unit = {},
    ): Uri = publish(ctx, encode(ctx, frames, fps, log = log), displayName, log)

    /**
     * ⭐⭐ Encode to a FILE and stop there.
     *
     * ⚠ Split out of [write] for this app: a clip is a graph value that may be
     * looked at, re-run against, or discarded, and publishing every one of them
     * into the user's gallery is a side effect a node must ask for
     * (`image.output` has the same `save` switch and the same reason).
     */
    fun encode(
        ctx: Context,
        frames: List<Bitmap>,
        fps: Int = 24,
        out: File = File(ctx.cacheDir, "nd_encode.mp4"),
        log: (String) -> Unit = {},
    ): File {
        require(frames.isNotEmpty()) { "no frames to write" }
        val w = frames[0].width
        val h = frames[0].height
        // H.264 wants even dimensions; 320x512 and 640x1024 both qualify, but a future
        // resolution might not and the failure would be an opaque codec error.
        require(w % 2 == 0 && h % 2 == 0) { "odd frame size ${w}x$h" }

        // Encode to a temp file first. MediaMuxer needs a seekable FileDescriptor, and a
        // MediaStore uri opened for write is not reliably seekable across OEMs.
        val tmp = out
        if (tmp.exists()) tmp.delete()
        tmp.parentFile?.mkdirs()

        val fmt = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                       MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // ~0.15 bits per pixel per frame is generous for this content and keeps a
            // 2 s clip small enough to share.
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.15).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        var outFrame = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxing) { "format changed twice" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxing = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        // CODEC_CONFIG carries SPS/PPS, which addTrack already took.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            // OVERRIDE the timestamp. With a Surface input MediaCodec
                            // stamps each frame with when it was SUBMITTED, and the
                            // frames are drawn as fast as the loop runs -- the first
                            // version produced a 62 fps file from a 24 fps request,
                            // because KEY_FRAME_RATE only hints at bitrate allocation
                            // and does not set presentation time.
                            //
                            // The container's timestamps are what a player obeys, so
                            // writing an exact 1/fps cadence here fixes playback
                            // regardless of how fast the encoder was fed. Output order
                            // equals input order because H.264 baseline here has no
                            // B-frames, so a simple counter is correct.
                            info.presentationTimeUs = outFrame * 1_000_000L / fps
                            outFrame++
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val dst = Rect(0, 0, w, h)
        try {
            frames.forEachIndexed { i, bmp ->
                // Even with the muxer override, feeding the encoder a sane cadence keeps
                // its rate control sensible rather than treating the clip as 62 fps.
                val canvas = surface.lockCanvas(dst)
                canvas.drawBitmap(bmp, null, dst, null)
                surface.unlockCanvasAndPost(canvas)
                drain(false)
                if ((i + 1) % 16 == 0) log("      encoded ${i + 1}/${frames.size}")
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        log("      encoded ${tmp.length() / 1024} KB, ${frames.size} frames at $fps fps")
        return tmp
    }

    /**
     * Copy a finished MP4 into the gallery.
     *
     * ⚠ MediaStore rather than a path under Movies/: since Android 10 an app
     * cannot write there directly, and a file that exists but never appears in
     * the gallery is indistinguishable to the user from one that was never
     * written.
     */
    fun publish(
        ctx: Context,
        tmp: File,
        displayName: String,
        log: (String) -> Unit = {},
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Nightmare")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create the entry")
        resolver.openOutputStream(uri).use { os ->
            checkNotNull(os) { "could not open $uri for writing" }
            tmp.inputStream().use { it.copyTo(os) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        log("      saved ${tmp.length() / 1024} KB -> Movies/Nightmare/$displayName")
        // ⚠ NOT deleted. The caller owns the file: in this app it is a graph
        // value that the node still points at, and deleting it here left the
        // canvas holding a path to nothing the moment anyone ticked `save`.
        return uri
    }
}
