package com.abrah.nightmare

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * ⭐ Where a golden is written. A `Narrow…` subclass ([NarrowSweep]) re-runs a
 * whole golden class at a phone width, and its pictures go to
 * `screenshots/narrow/<name>-w<dp>.png` instead of over the 411dp original.
 */
fun goldenPath(test: Any, name: String): String {
    if (!test.javaClass.simpleName.startsWith("Narrow")) return "src/test/screenshots/$name.png"
    val w = ApplicationProvider.getApplicationContext<Context>().resources.configuration.screenWidthDp
    return "src/test/screenshots/narrow/$name-w$w.png"
}
