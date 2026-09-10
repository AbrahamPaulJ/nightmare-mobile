# ⚠ R8 cannot see a call made from C. Every entry point below is reached only
# through JNI, so nothing in the bytecode references it and the shrinker is
# right, by its own rules, to delete it. The failure mode is a release-only
# UnsatisfiedLinkError or NoSuchMethodError that no debug build ever shows.
#
# ⚠⚠ The native side looks these up BY STRING (app/src/main/cpp/nmjs.c), so
# renaming is as fatal as removing. -keepclasseswithmembernames from
# proguard-android-optimize.txt already covers `native <methods>`; it does NOT
# cover a Kotlin method the C calls back into, nor a class the C constructs.

# hostCall: nmjs.c caches it with GetMethodID on the JsRuntime instance, and
# every ctx.host(...) a plugin makes arrives through it.
-keepclassmembers class com.abrah.nightmare.JsRuntime {
    native <methods>;
    java.lang.String hostCall(java.lang.String, java.lang.String);
}

# JsException: found with FindClass("com/abrah/nightmare/JsException") and
# raised with ThrowNew, which needs the (String) constructor.
-keep class com.abrah.nightmare.JsException {
    <init>(java.lang.String);
}
