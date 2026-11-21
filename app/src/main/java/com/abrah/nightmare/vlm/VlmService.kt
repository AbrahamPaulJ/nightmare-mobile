package com.abrah.nightmare.vlm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⭐⭐ The vision model's own process (`android:process=":vlm"`, GitHub #120). The app sends a picture and an
 * instruction ([VisionDescriber]); this process loads the engine and the model once, answers in a stream, and ENDS
 * ITSELF when idle or told to ([MSG_EXIT]), which is the only sure way to give ~4 GB back before a render needs it.
 *
 * Why a process of its own:
 * - `ADSP_LIBRARY_PATH` is read once per process by fastrpc, and the app's in-process Neodragon runner sets it to its
 *   own skels (`nmqnn.cpp`);
 * - a crash in llama.cpp (an NPU session that will not open, an out-of-memory) costs one describe, not the app.
 *
 * Messenger, not AIDL: a few message kinds, bundles of strings. ⚠ The picture goes as a FILE path: a 1536² RGB is
 * 7 MB, past a binder transaction's 1 MB.
 */
class VlmService : Service() {

    private lateinit var inbox: Messenger

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("vlm-inbox").apply { start() }
        inbox = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_DESCRIBE -> {
                        main.removeCallbacks(idleExit)
                        stop.set(false)
                        val data = Bundle(msg.data)
                        val reply = msg.replyTo
                        worker.execute { describe(data, reply) }
                    }
                    MSG_STOP -> stop.set(true)
                    MSG_EXIT -> {
                        stop.set(true)
                        worker.execute { exitNow() }
                    }
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = inbox.binder

    companion object {
        private const val TAG = "VlmService"

        /** ⭐ Kept loaded this long after an answer, so a second picture does not pay the load again. */
        const val IDLE_MS = 90_000L

        const val MSG_DESCRIBE = 1
        const val MSG_PART = 2
        const val MSG_DONE = 3
        const val MSG_FAIL = 4
        const val MSG_STOP = 5
        const val MSG_EXIT = 6

        const val K_ENGINE_DIR = "engine"
        const val K_DEVICES = "devices"
        const val K_MODEL = "model"
        const val K_MMPROJ = "mmproj"
        const val K_CTX = "ctx"
        const val K_THREADS = "threads"
        const val K_NPU_LAYERS = "npu_layers"
        const val K_VISION_NPU = "vision_npu"
        const val K_IMAGE_TOKENS = "image_tokens"
        const val K_RGB = "rgb"
        const val K_W = "w"
        const val K_H = "h"
        const val K_SYSTEM = "system"
        const val K_USER = "user"
        const val K_MAX_TOKENS = "max_tokens"
        const val K_TEMP = "temp"
        const val K_SEED = "seed"
        const val K_TEXT = "text"

        // ⚠⚠ Process-wide, not the instance's: the app unbinds after each answer, so the next describe meets a NEW
        // VlmService in the SAME process with the model still loaded. On the instance, the handle was forgotten and
        // the ~4 GB model would load a second time beside the first.
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "vlm").apply { priority = Thread.MAX_PRIORITY } }
        private val main = Handler(Looper.getMainLooper())
        private val stop = AtomicBoolean(false)
        @Volatile private var handle = 0L
        @Volatile private var loadedKey: String? = null
        @Volatile private var inited = false

        private val idleExit = Runnable {
            Log.i(TAG, "idle, ending the process")
            worker.execute { exitNow() }
        }

        private fun describe(d: Bundle, reply: Messenger?) {
            val t0 = System.nanoTime()
            try {
                val engine = d.getString(K_ENGINE_DIR)!!
                if (!inited) {
                    VlmNative.load(File(engine, VlmEngine.MAIN).path)
                    VlmNative.nativeInit(engine, d.getString(K_DEVICES, "none"))
                    inited = true
                }
                val key = d.getString(K_MODEL) + "|" + d.getString(K_MMPROJ) + "|" + d.getBoolean(K_VISION_NPU) + "|" + d.getInt(K_IMAGE_TOKENS)
                if (handle != 0L && key != loadedKey) {
                    VlmNative.nativeFree(handle)
                    handle = 0L
                }
                if (handle == 0L) {
                    handle = VlmNative.nativeLoad(
                        d.getString(K_MODEL)!!, d.getString(K_MMPROJ)!!, d.getInt(K_CTX, 4096), d.getInt(K_THREADS, 4),
                        d.getInt(K_NPU_LAYERS, 0), d.getBoolean(K_VISION_NPU, false), d.getInt(K_IMAGE_TOKENS, 0),
                    )
                    if (handle == 0L) throw IllegalStateException(VlmNative.nativeLastError())
                    loadedKey = key
                    Log.i(TAG, "loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
                }
                val w = d.getInt(K_W)
                val h = d.getInt(K_H)
                // ⭐ No picture (w or h 0): a text-only turn, the prompt enhancer ([VisionDescriber.rewrite]).
                val rgb = if (w > 0 && h > 0) File(d.getString(K_RGB)!!).readBytes() else ByteArray(0)
                require(rgb.size == maxOf(w, 0) * maxOf(h, 0) * 3) { "the picture is ${rgb.size} bytes, expected ${w * h * 3}" }
                val t1 = System.nanoTime()
                val out = VlmNative.nativeDescribe(
                    handle, rgb, w, h, d.getString(K_SYSTEM).orEmpty(), d.getString(K_USER).orEmpty(),
                    d.getInt(K_MAX_TOKENS, 300), d.getFloat(K_TEMP, 0.3f), 40, 0.9f, d.getInt(K_SEED, 42),
                ) { bytes ->
                    send(reply, MSG_PART, String(bytes, Charsets.UTF_8))
                    !stop.get()
                } ?: throw IllegalStateException(VlmNative.nativeLastError())
                val text = String(out, Charsets.UTF_8)
                Log.i(TAG, "described in ${(System.nanoTime() - t1) / 1_000_000} ms, ${text.length} chars")
                send(reply, MSG_DONE, text)
            } catch (e: Throwable) {
                Log.w(TAG, "describe failed", e)
                send(reply, MSG_FAIL, e.message ?: e.javaClass.simpleName)
            } finally {
                main.postDelayed(idleExit, IDLE_MS)
            }
        }

        private fun send(to: Messenger?, what: Int, text: String) {
            to ?: return
            runCatching { to.send(Message.obtain(null, what).apply { data = Bundle().apply { putString(K_TEXT, text) } }) }
        }

        /** ⚠ The process, not only the model: the native heap does not shrink after a free, and the NPU sessions close with it. */
        private fun exitNow() {
            if (handle != 0L) runCatching { VlmNative.nativeFree(handle) }
            handle = 0L
            Process.killProcess(Process.myPid())
        }
    }
}
