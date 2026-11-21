package com.abrah.nightmare.vlm

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.CpuFeatures
import com.abrah.nightmare.DescribeModel
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.ModelFiles
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ⭐⭐ Picture to prompt with a vision language model (GitHub #120): Qwen3-VL 4B Instruct, prithivMLmods' "Unredacted
 * MAX" abliteration (Apache-2.0, the reporter's pick, the user's 2026-11-21), our Q4_0 of it with their q8_0 vision projector, run
 * by our llama.cpp bridge in a process of its own ([VlmService]). The describe dialog's fourth chip; what it writes
 * follows the checkpoint the prompt feeds ([VisionPrompts.Style]).
 *
 * ⚠ Q4_0, not their Q8_0: Q8_0 held 6.2 GB of NPU memory and the low-memory killer took it (and the app) on a used
 * 12 GB S25; Q4_0 holds ~4 GB and writes the same sentences (`docs/FLORENCE.md` §6). 2.8 GB of downloads, offered
 * only where it can run ([offered]), and the checkpoint is unloaded first when free memory is short ([describe]).
 */
object VisionDescriber : DescribeModel {

    private const val TAG = "VisionDescriber"

    override val id = "qwen3vl4b"
    override val label = "Qwen3-VL 4B"
    override val labelRes = R.string.describe_vlm_label

    private const val BASE =
        "https://huggingface.co/prithivMLmods/Qwen3-VL-4B-Instruct-Unredacted-MAX-GGUF/resolve/beb6b720c111f05aa0d33bdb8a80e9cdc4f5a7b9/"
    /** Ours (`llama-quantize` Q4_0 of their F16, sha256 `c26fac69…81b8`): the NPU runs Q4_0 natively. */
    private const val MODEL =
        "https://huggingface.co/AbrahamPJ/qwen3-vl-4b-unredacted-max-q4_0-gguf/resolve/cbb71fe03b897d11e552318d743357dfdc00d59e/Qwen3-VL-4B-Instruct-Unredacted-MAX.Q4_0.gguf"
    private const val MMPROJ = "Qwen3-VL-4B-Instruct-Unredacted-MAX.mmproj-q8_0.gguf"

    internal val FILES = ModelFiles("qwen3vl4b", BASE, listOf(MODEL to 2_369_548_000L, MMPROJ to 453_974_368L))

    override val bytes: Long get() = FILES.bytes + VlmEngine.BYTES

    /**
     * ⭐ Where it is offered: cores with i8mm (the engine is built for them, as llama.cpp's Snapdragon preset is) and
     * 10 GB of RAM or an unknown amount. ⚠ An 8 GB phone holds the ~4 GB model only with nothing else loaded, and
     * Android kills the process that asked first.
     */
    val offered: Boolean by lazy {
        val ram = runCatching { DeviceProbe.caps().ramBytes }.getOrDefault(0L)
        CpuFeatures.hasI8mm && (ram == 0L || ram >= MIN_RAM)
    }
    private const val MIN_RAM = 10_000_000_000L

    /** ⭐ The NPU sessions: two on v75 and newer (each its own 3.5 GB window), one on v73, none below or off Snapdragon. */
    internal fun devicesFor(caps: DeviceProbe.Caps): String = when {
        caps.supportsArch(75) -> "HTP0:0,HTP0:1"
        caps.supportsArch(73) -> "HTP0"
        else -> "none"
    }

    /**
     * ⚠ Harness only (`describe_probe`'s A/B): the vision encoder on the CPU. On the NPU it reads a picture in 1.4 s;
     * on the CPU (S25, 6 threads) it took over a minute and pushed the phone into the low-memory killer.
     */
    @Volatile
    internal var visionOnCpu = false

    /** ⚠ Harness only: a model file in place of the installed weights (a quant A/B). */
    @Volatile
    internal var modelOverride: String? = null

    /** ⚠ Harness only: the NPU sessions, overriding [devicesFor] ("none" = the CPU alone). */
    @Volatile
    internal var devicesOverride: String? = null

    override fun isInstalled(context: Context): Boolean = FILES.isInstalled(context) && VlmEngine.isInstalled(context)

    override fun bytesOnDisk(context: Context): Long = FILES.bytesOnDisk(context) + VlmEngine.bytesOnDisk(context)

    @Volatile
    override var installed: Boolean = false
        private set

    override fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    override fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        val total = bytes
        VlmEngine.install(context, { p -> onProgress(ModelInstaller.Progress(p.phase, p.done, total)) }, isCancelled)
        FILES.install(context, context.getString(labelRes), { p ->
            onProgress(ModelInstaller.Progress(p.phase, VlmEngine.BYTES + p.done, total))
        }, isCancelled)
        refresh(context)
        Log.i(TAG, "installed (${bytesOnDisk(context)} bytes)")
    }

    override fun delete(context: Context) {
        trim()
        FILES.dir(context).deleteRecursively()
        VlmEngine.delete(context)
        refresh(context)
    }

    /**
     * ⭐ The answer so far, as it streams: the Describing dialog shows it, because a picture takes the 4B model tens
     * of seconds and a spinner that long reads as a hang. Empty between describes.
     */
    val live = kotlinx.coroutines.flow.MutableStateFlow("")

    @Volatile
    private var appContext: Context? = null

    /** When the last answer came; the `:vlm` process ends itself [VlmService.IDLE_MS] after it. */
    @Volatile
    private var lastUse = 0L

    /** ⭐ Ends the `:vlm` process now, if it is up ([com.abrah.nightmare.PictureModels.releaseAll] before a render). */
    override fun trim() {
        val ctx = appContext ?: return
        if (System.currentTimeMillis() - lastUse > VlmService.IDLE_MS + 5_000) return
        lastUse = 0L
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching { Messenger(service).send(Message.obtain(null, VlmService.MSG_EXIT)) }
                runCatching { ctx.unbindService(this) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        // ⚠ Flags 0: reach a running service, never start one just to end it.
        runCatching { ctx.bindService(Intent(ctx, VlmService::class.java), conn, 0) }
    }

    /**
     * ⭐ The prompt for [photo] in [style], or null. Blocking (the describe paths call it off the main thread).
     * [onPart] sees the answer as it streams.
     */
    fun describe(context: Context, photo: Bitmap, style: VisionPrompts.Style, onPart: (String) -> Unit = {}): String? {
        val ctx = context.applicationContext
        val rgbFile = File(ctx.cacheDir, "vlm-in.rgb")
        val (w, h) = writeRgb(photo, rgbFile)
        val raw = try {
            run(ctx, VisionPrompts.ask(style), rgbFile, w, h, onPart)
        } finally {
            rgbFile.delete()
        }
        return VisionPrompts.finish(style, raw)
    }

    /**
     * ⭐ A text-only answer to [ask], or the engine's error: the prompt enhancer ([PromptEnhance]), the same model, process and
     * NPU sessions as [describe], with no picture. Blocking. ⚠ Needs engine 3: engine 2 always tokenized a picture.
     */
    fun rewrite(context: Context, ask: VisionPrompts.Ask, onPart: (String) -> Unit = {}): String =
        run(context.applicationContext, ask, null, 0, 0, onPart)

    private fun run(ctx: Context, ask: VisionPrompts.Ask, rgbFile: File?, w: Int, h: Int, onPart: (String) -> Unit): String {
        appContext = ctx
        freeMemoryFor(ctx)
        val caps = runCatching { DeviceProbe.caps() }.getOrNull()
        val devices = devicesOverride ?: caps?.let { devicesFor(it) } ?: "none"
        val req = Bundle().apply {
            putString(VlmService.K_ENGINE_DIR, VlmEngine.dir(ctx).path)
            putString(VlmService.K_DEVICES, devices)
            putString(VlmService.K_MODEL, modelOverride ?: FILES.file(ctx, MODEL).path)
            putString(VlmService.K_MMPROJ, FILES.file(ctx, MMPROJ).path)
            putInt(VlmService.K_CTX, 4096)
            putInt(VlmService.K_THREADS, 6)
            putInt(VlmService.K_NPU_LAYERS, if (devices == "none") 0 else 99)
            putBoolean(VlmService.K_VISION_NPU, !visionOnCpu && devices != "none")
            putInt(VlmService.K_IMAGE_TOKENS, IMAGE_TOKENS)
            putString(VlmService.K_RGB, rgbFile?.path.orEmpty())
            putInt(VlmService.K_W, w)
            putInt(VlmService.K_H, h)
            putString(VlmService.K_SYSTEM, ask.system)
            putString(VlmService.K_USER, ask.user)
            putInt(VlmService.K_MAX_TOKENS, ask.maxTokens)
            putFloat(VlmService.K_TEMP, ask.temp)
            putInt(VlmService.K_SEED, ask.seed)
        }
        live.value = ""
        val raw = try {
            call(ctx, req) { part -> live.value += part; onPart(part) }
        } finally {
            live.value = ""
        }
        lastUse = System.currentTimeMillis()
        // Whole, in pieces under logcat's line limit: a layout that does not repair is read from here.
        raw.chunked(900).forEachIndexed { i, part -> Log.i(TAG, "raw answer ${i + 1}: $part") }
        return raw
    }

    /**
     * ⚠ The checkpoint out when memory is short: a loaded DiT (6 to 10 GB) and this ~4 GB model do not fit together on
     * a 12 GB phone, and the low-memory killer takes whichever it likes. The next render relaunches it (2 to 5 s).
     */
    private fun freeMemoryFor(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mi.availMem < 5L shl 30 && BackendProcess.launchedKey != null) {
            Log.i(TAG, "free ${mi.availMem shr 20} MB: unloading the checkpoint first")
            BackendProcess.stop()
        }
    }

    /**
     * ⭐ Every picture becomes about this many tokens (the engine resizes to it): Qwen-VL places boxes well only from
     * 1024 up, and a fixed budget caps the projector's working memory (it reserved for 1472x1472 without one).
     */
    private const val IMAGE_TOKENS = 1024

    /**
     * The picture as packed RGB, the long side at most [MAX_SIDE]. Qwen3-VL reads 32 px a token, so 1024 tokens is
     * about 1024x1024: a wide picture is sent larger and resized down by the engine, not up.
     */
    private fun writeRgb(photo: Bitmap, out: File): Pair<Int, Int> {
        val s = minOf(1f, MAX_SIDE.toFloat() / maxOf(photo.width, photo.height))
        val w = maxOf(32, (photo.width * s).toInt())
        val h = maxOf(32, (photo.height * s).toInt())
        val src = if (w != photo.width || h != photo.height) Bitmap.createScaledBitmap(photo, w, h, true) else photo
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (src !== photo) src.recycle()
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
        }
        out.writeBytes(rgb)
        return w to h
    }
    private const val MAX_SIDE = 1536

    private fun call(ctx: Context, req: Bundle, onPart: (String) -> Unit): String {
        val thread = HandlerThread("vlm-client").apply { start() }
        val done = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        val replies = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val text = msg.data?.getString(VlmService.K_TEXT).orEmpty()
                when (msg.what) {
                    VlmService.MSG_PART -> onPart(text)
                    VlmService.MSG_DONE -> { result = text; done.countDown() }
                    VlmService.MSG_FAIL -> { error = text; done.countDown() }
                }
            }
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching {
                    Messenger(service).send(Message.obtain(null, VlmService.MSG_DESCRIBE).apply { data = req; replyTo = replies })
                }.onFailure { error = it.message; done.countDown() }
            }
            // ⚠ The process died (a native crash, or the low-memory killer): say so rather than wait forever.
            override fun onServiceDisconnected(name: ComponentName?) {
                if (error == null && result == null) error = "the vision model's process ended"
                done.countDown()
            }
        }
        try {
            if (!ctx.bindService(Intent(ctx, VlmService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                throw IOException("cannot start the vision model's process")
            }
            if (!done.await(10, TimeUnit.MINUTES)) throw IOException("the vision model did not answer in 10 minutes")
        } finally {
            runCatching { ctx.unbindService(conn) }
            thread.quitSafely()
        }
        error?.let { throw IOException(it) }
        return result.orEmpty()
    }
}
