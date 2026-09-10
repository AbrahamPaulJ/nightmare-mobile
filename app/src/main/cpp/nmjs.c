// The JNI bridge between Kotlin and QuickJS.
//
// ⭐ The contract is deliberately tiny: STRINGS IN, STRINGS OUT. A plugin node
// receives JSON and returns JSON, and every tensor, image and latent it touches
// is named by an opaque HANDLE inside that JSON (docs/ARCHITECTURE.md §6).
// Nothing here ever copies pixels: marshalling a 512x512x3 array through a JS
// engine on every node would dominate the runtime and make the sandbox
// pointless.
//
// ⚠ So this file must stay boring. The moment it grows a typed-array fast path
// or a direct buffer, the "JS orchestrates, the host moves the bytes" rule has
// been broken and the cost model in §6 no longer holds.

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

#include "quickjs.h"

#define TAG "nmjs"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ⚠ Sandbox limits, set at construction rather than left to the engine's
// defaults (docs/ARCHITECTURE.md §7). Plugins are downloaded scripts, so
// "a node hangs the app" and "a node eats the heap" are ordinary failures to
// expect, not attacks to imagine.
#define NMJS_MEMORY_LIMIT (64 * 1024 * 1024)
#define NMJS_STACK_SIZE   (1024 * 1024)

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    // Global ref to the Kotlin JsRuntime that owns us. It is what host.call()
    // dispatches to, and it is global because the JS side may call back on any
    // thread that entered eval().
    jobject owner;
    // Wall-clock deadline for the call in flight, or 0 for "no call in
    // flight". ⚠ Checked by the interrupt handler: a plugin's `while(1)` must
    // end the NODE, not the app.
    int64_t deadline_ms;
} NmJs;

static JavaVM *g_vm = NULL;
static jmethodID g_host_call = NULL;   // String JsRuntime.hostCall(String, String)

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// ⚠ Returns JNI_TRUE if this thread had to be attached, so the caller can
// detach it again. A thread attached and never detached keeps the whole VM
// pinned; QuickJS calls back on whatever thread called eval, which for us is a
// coroutine's IO thread and therefore not the same one twice.
static JNIEnv *get_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) {
        *attached = 1;
        return env;
    }
    return NULL;
}

static int interrupt_handler(JSRuntime *rt, void *opaque) {
    NmJs *js = (NmJs *)opaque;
    if (js->deadline_ms == 0) return 0;
    return now_ms() > js->deadline_ms ? 1 : 0;
}

/**
 * A Java throwable as "ClassName: message", malloc'd for the caller to free.
 *
 * ⚠ toString(), not getMessage(): it keeps the class name, so an exception with
 * no message at all still says what KIND it was -- and
 * "java.lang.NullPointerException" is far more useful to a plugin author than
 * "host op threw".
 */
static char *jthrowable_text(JNIEnv *env, jthrowable t) {
    if (!t) return NULL;
    jclass cls = (*env)->GetObjectClass(env, t);
    jmethodID to_string = (*env)->GetMethodID(env, cls, "toString", "()Ljava/lang/String;");
    if (!to_string) return NULL;
    jstring js = (jstring)(*env)->CallObjectMethod(env, t, to_string);
    // ⚠ toString() on a broken exception can itself throw; a pending exception
    // here would poison every later JNI call on this thread.
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    if (!js) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, js, NULL);
    char *out = chars ? strdup(chars) : NULL;
    if (chars) (*env)->ReleaseStringUTFChars(env, js, chars);
    (*env)->DeleteLocalRef(env, js);
    return out;
}

// host.call(op, argsJson) -> resultJson
//
// ⚠ The ONE door out of the sandbox. Everything a plugin can do to the device
// goes through this function and is therefore enumerable on the Kotlin side --
// which is what makes a permission model possible at all. Adding a second
// native global would quietly make that list a lie.
static JSValue js_host_call(JSContext *ctx, JSValueConst this_val,
                            int argc, JSValueConst *argv) {
    NmJs *js = (NmJs *)JS_GetContextOpaque(ctx);
    if (argc < 2) return JS_ThrowTypeError(ctx, "host.call(op, argsJson) needs two arguments");

    const char *op = JS_ToCString(ctx, argv[0]);
    const char *args = JS_ToCString(ctx, argv[1]);
    if (!op || !args) {
        if (op) JS_FreeCString(ctx, op);
        if (args) JS_FreeCString(ctx, args);
        return JS_ThrowTypeError(ctx, "host.call takes two strings");
    }

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env) {
        JS_FreeCString(ctx, op);
        JS_FreeCString(ctx, args);
        return JS_ThrowInternalError(ctx, "host.call: no JNI environment");
    }

    jstring jop = (*env)->NewStringUTF(env, op);
    jstring jargs = (*env)->NewStringUTF(env, args);
    JS_FreeCString(ctx, op);
    JS_FreeCString(ctx, args);

    jstring jres = (jstring)(*env)->CallObjectMethod(env, js->owner, g_host_call, jop, jargs);

    JSValue out;
    if ((*env)->ExceptionCheck(env)) {
        // ⚠ The Kotlin exception is CLEARED and rethrown as a JS error, not
        // left pending. A pending JNI exception makes every subsequent JNI call
        // in this thread undefined behaviour, and the JS code has a legitimate
        // reason to want to catch a failed host op.
        //
        // ⚠⚠ And it carries the MESSAGE across. The first version threw a bare
        // "host op threw", which cost a wrong verdict on 2026-09-08: a
        // permission denial reached the executor as `InternalError: host op
        // threw` and a check looking for the word "permission" reported that
        // the plugin had NOT been denied -- when it had. An error that loses
        // its reason is worse here than elsewhere, because the reader is a
        // plugin author with no debugger.
        jthrowable t = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        char *text = jthrowable_text(env, t);
        // ⚠ "%s", never the message as the format itself: it comes from a Java
        // exception whose text a plugin can influence, and a stray %n in a
        // format string is a memory bug rather than a bad log line.
        out = JS_ThrowInternalError(ctx, "%s", text ? text : "host op threw");
        free(text);
        if (t) (*env)->DeleteLocalRef(env, t);
    } else if (jres == NULL) {
        out = JS_NULL;
    } else {
        const char *res = (*env)->GetStringUTFChars(env, jres, NULL);
        out = JS_NewString(ctx, res ? res : "");
        if (res) (*env)->ReleaseStringUTFChars(env, jres, res);
    }

    (*env)->DeleteLocalRef(env, jop);
    (*env)->DeleteLocalRef(env, jargs);
    if (jres) (*env)->DeleteLocalRef(env, jres);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return out;
}

// console.log / console.warn -> logcat.
//
// ⚠ Present because a plugin author debugging a node otherwise has NO output at
// all: there is no REPL on a phone, and a node that silently returns the wrong
// thing is the hardest kind of thing to diagnose remotely.
static JSValue js_console(JSContext *ctx, JSValueConst this_val,
                          int argc, JSValueConst *argv, int magic) {
    for (int i = 0; i < argc; i++) {
        const char *s = JS_ToCString(ctx, argv[i]);
        if (!s) continue;
        if (magic) LOGW("js: %s", s); else LOGI("js: %s", s);
        JS_FreeCString(ctx, s);
    }
    return JS_UNDEFINED;
}

// The JS error, with its stack, as one string for a Java exception message.
static char *take_exception(JSContext *ctx) {
    JSValue e = JS_GetException(ctx);
    const char *msg = JS_ToCString(ctx, e);
    char *out = NULL;

    JSValue stack = JS_GetPropertyStr(ctx, e, "stack");
    const char *st = JS_IsUndefined(stack) ? NULL : JS_ToCString(ctx, stack);

    size_t n = (msg ? strlen(msg) : 7) + (st ? strlen(st) + 2 : 0) + 1;
    out = malloc(n);
    if (out) {
        snprintf(out, n, "%s%s%s", msg ? msg : "unknown", st ? "\n" : "", st ? st : "");
    }
    if (st) JS_FreeCString(ctx, st);
    JS_FreeValue(ctx, stack);
    if (msg) JS_FreeCString(ctx, msg);
    JS_FreeValue(ctx, e);
    return out;
}

static void throw_js_error(JNIEnv *env, JSContext *ctx) {
    char *msg = take_exception(ctx);
    jclass cls = (*env)->FindClass(env, "com/abrah/nightmare/JsException");
    if (cls) (*env)->ThrowNew(env, cls, msg ? msg : "javascript error");
    free(msg);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_abrah_nightmare_JsRuntime_nativeNew(JNIEnv *env, jobject self) {
    if (!g_host_call) {
        jclass cls = (*env)->GetObjectClass(env, self);
        g_host_call = (*env)->GetMethodID(env, cls, "hostCall",
                                          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (!g_host_call) return 0;
    }

    NmJs *js = calloc(1, sizeof(NmJs));
    if (!js) return 0;

    js->rt = JS_NewRuntime();
    if (!js->rt) { free(js); return 0; }
    JS_SetMemoryLimit(js->rt, NMJS_MEMORY_LIMIT);
    JS_SetMaxStackSize(js->rt, NMJS_STACK_SIZE);
    JS_SetInterruptHandler(js->rt, interrupt_handler, js);

    js->ctx = JS_NewContext(js->rt);
    if (!js->ctx) { JS_FreeRuntime(js->rt); free(js); return 0; }
    JS_SetContextOpaque(js->ctx, js);
    js->owner = (*env)->NewGlobalRef(env, self);

    JSValue global = JS_GetGlobalObject(js->ctx);

    JSValue host = JS_NewObject(js->ctx);
    JS_SetPropertyStr(js->ctx, host, "call",
                      JS_NewCFunction(js->ctx, js_host_call, "call", 2));
    JS_SetPropertyStr(js->ctx, global, "host", host);

    JSValue console = JS_NewObject(js->ctx);
    JS_SetPropertyStr(js->ctx, console, "log",
                      JS_NewCFunctionMagic(js->ctx, js_console, "log", 1, JS_CFUNC_generic_magic, 0));
    JS_SetPropertyStr(js->ctx, console, "warn",
                      JS_NewCFunctionMagic(js->ctx, js_console, "warn", 1, JS_CFUNC_generic_magic, 1));
    JS_SetPropertyStr(js->ctx, console, "error",
                      JS_NewCFunctionMagic(js->ctx, js_console, "error", 1, JS_CFUNC_generic_magic, 1));
    JS_SetPropertyStr(js->ctx, global, "console", console);

    JS_FreeValue(js->ctx, global);
    return (jlong)(intptr_t)js;
}

JNIEXPORT void JNICALL
Java_com_abrah_nightmare_JsRuntime_nativeClose(JNIEnv *env, jobject self, jlong ptr) {
    NmJs *js = (NmJs *)(intptr_t)ptr;
    if (!js) return;
    if (js->owner) (*env)->DeleteGlobalRef(env, js->owner);
    JS_FreeContext(js->ctx);
    JS_FreeRuntime(js->rt);
    free(js);
}

// Evaluate a script; return the result as a string ("undefined" when there is
// none), or throw JsException.
//
// ⚠ JS_EVAL_TYPE_GLOBAL, not MODULE. A plugin's index.js is loaded as a script
// so its top-level assignments land on the global object where the host can
// find them; ES module semantics would put every declaration in a private scope
// and require an import machinery this runtime does not have.
JNIEXPORT jstring JNICALL
Java_com_abrah_nightmare_JsRuntime_nativeEval(JNIEnv *env, jobject self, jlong ptr,
                                              jstring jcode, jstring jname, jint budgetMs) {
    NmJs *js = (NmJs *)(intptr_t)ptr;
    if (!js) return NULL;

    const char *code = (*env)->GetStringUTFChars(env, jcode, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    js->deadline_ms = budgetMs > 0 ? now_ms() + budgetMs : 0;

    JSValue v = JS_Eval(js->ctx, code, strlen(code), name, JS_EVAL_TYPE_GLOBAL);

    js->deadline_ms = 0;
    (*env)->ReleaseStringUTFChars(env, jcode, code);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    if (JS_IsException(v)) {
        JS_FreeValue(js->ctx, v);
        throw_js_error(env, js->ctx);
        return NULL;
    }

    const char *s = JS_ToCString(js->ctx, v);
    jstring out = (*env)->NewStringUTF(env, s ? s : "undefined");
    if (s) JS_FreeCString(js->ctx, s);
    JS_FreeValue(js->ctx, v);
    return out;
}
