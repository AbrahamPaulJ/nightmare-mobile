package com.abrah.nightmare

/** A JS error, with the plugin's own stack in the message. */
class JsException(message: String) : RuntimeException(message)

/**
 * One QuickJS runtime: the thing a plugin's `index.js` runs inside.
 *
 * ⭐ Decided in CLAUDE.md: nodes are JS calling host ops. No toolchain, no
 * compile step, hot-reloadable, and the same language as the manifests. The
 * engine is quickjs-ng, MIT, fetched by `tools/fetch_quickjs.ps1` and built by
 * `app/src/main/cpp/CMakeLists.txt`.
 *
 * ⚠⚠ NOT thread-safe, and not defensively so. A JSContext belongs to one thread
 * at a time; two coroutines evaluating in the same runtime corrupts it in ways
 * that surface as arbitrary crashes far from the cause. The executor runs nodes
 * sequentially (docs/ARCHITECTURE.md §4) so one runtime per executor is
 * correct -- but the moment nodes run concurrently this needs a lock or a
 * runtime each, and "it seemed to work" will not be evidence either way.
 *
 * ⚠ [close] is mandatory. The native side holds a JSRuntime, a JSContext and a
 * global JNI ref; none of them are reachable by the garbage collector.
 */
class JsRuntime(private val host: HostOps) : AutoCloseable {

    /**
     * What a plugin is allowed to ask the host to do.
     *
     * ⭐ This interface IS the sandbox boundary. `host.call` is the only native
     * global the JS side gets, so the set of things a plugin can do to the
     * device is exactly the set of op names accepted here -- which is what
     * makes the manifest's `permissions` list checkable rather than decorative
     * (docs/ARCHITECTURE.md §7).
     */
    fun interface HostOps {
        /** @return the op's JSON result. Throwing is reported to JS as an error. */
        fun call(op: String, argsJson: String): String
    }

    private var ptr: Long = run {
        // ⚠ Loaded HERE, on the first instance, not from the companion object.
        // `quote` lives on the companion and is pure Kotlin; a loadLibrary in
        // the companion's initialiser made merely *calling* it drag in an
        // arm64 .so, so its JVM tests died with UnsatisfiedLinkError before
        // asserting anything. Measured 2026-09-08. ⇒ Native loading belongs on
        // the path that needs native code.
        NativeLib.ensure()
        nativeNew()
    }

    init {
        if (ptr == 0L) throw JsException("could not create a QuickJS runtime")
        // The prelude the host relies on. ⚠ Kept to the minimum that makes the
        // string-in/string-out contract usable from JS: everything else a
        // plugin needs is a host op, not a builtin.
        eval(PRELUDE, "<prelude>")
    }

    /**
     * ⚠ Called from native code -- do not rename without changing nmjs.c, which
     * looks the method up by name and signature and will fail at runtime, not
     * at build time, if it disagrees.
     */
    @Suppress("unused")
    fun hostCall(op: String, argsJson: String): String = host.call(op, argsJson)

    /**
     * Evaluate [code]. Returns the value as a string; throws [JsException] on a
     * JS error.
     *
     * ⚠ [budgetMs] is a real stop, not a hint: an interrupt handler ends the
     * evaluation when the budget passes, so a plugin's `while(1)` costs one
     * node rather than the app. 0 disables it, which is for the prelude only.
     */
    fun eval(code: String, name: String = "<eval>", budgetMs: Int = DEFAULT_BUDGET_MS): String {
        check(ptr != 0L) { "JsRuntime is closed" }
        return nativeEval(ptr, code, name, budgetMs) ?: "undefined"
    }

    /**
     * Call a node implementation registered by a plugin, passing and receiving
     * JSON.
     *
     * ⚠ The JSON is built and parsed IN JS, by the prelude, not in Kotlin and
     * not in C. That keeps the native bridge to strings only (see nmjs.c) and
     * means a change to the calling convention is a change to one JS function.
     */
    fun invoke(node: String, argsJson: String, budgetMs: Int = DEFAULT_BUDGET_MS): String {
        val call = "__nm.invoke(${quote(node)}, ${quote(argsJson)})"
        return eval(call, "<invoke $node>", budgetMs)
    }

    override fun close() {
        if (ptr != 0L) {
            nativeClose(ptr)
            ptr = 0L
        }
    }

    private external fun nativeNew(): Long
    private external fun nativeClose(ptr: Long)
    private external fun nativeEval(ptr: Long, code: String, name: String, budgetMs: Int): String?

    /** Loads libnmjs.so exactly once, and only when a runtime is created. */
    private object NativeLib {
        init { System.loadLibrary("nmjs") }
        fun ensure() = Unit
    }

    companion object {
        /**
         * ⚠ Generous, because a Tier 1 node's host op (a segmenter on CPU) can
         * legitimately take seconds and the budget covers the whole call
         * including the time spent inside the host. It is a runaway-loop guard,
         * not a performance target.
         */
        const val DEFAULT_BUDGET_MS = 30_000

        /**
         * ⚠ A JS string literal, not a JSON one. They differ (JSON has no
         * U+2028 / U+2029 rule, JS source does), and a prompt containing a line
         * separator would otherwise end the statement mid-string -- the classic
         * way a string-built call silently becomes a syntax error on somebody
         * else's input.
         */
        internal fun quote(s: String): String {
            val sb = StringBuilder(s.length + 16).append('"')
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u2028' -> sb.append("\\u2028")
                    '\u2029' -> sb.append("\\u2029")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /**
         * The host-facing half of the plugin API, in JS.
         *
         * ⚠ `host.call` takes and returns strings because the native bridge
         * does; this wraps it so a node author writes `ctx.host("image.resize",
         * {...})` and gets an object back. Doing that conversion here rather
         * than in C is the whole reason nmjs.c can stay 250 lines.
         */
        private val PRELUDE = """
            var __nm = {
              nodes: {},
              // A plugin registers its implementations here.
              register: function (name, impl) { __nm.nodes[name] = impl; },
              // The host op door, object in / object out.
              host: function (op, args) {
                var res = host.call(op, JSON.stringify(args === undefined ? {} : args));
                return res === null || res === "" ? null : JSON.parse(res);
              },
              invoke: function (name, argsJson) {
                var impl = __nm.nodes[name];
                if (!impl) throw new Error("no node registered as \"" + name + "\"");
                var args = JSON.parse(argsJson);
                var out = impl.run(
                  { host: __nm.host },
                  args.inputs || {},
                  args.widgets || {}
                );
                return JSON.stringify(out === undefined ? null : out);
              }
            };
        """.trimIndent()
    }
}
