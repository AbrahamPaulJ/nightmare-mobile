package com.abrah.nightmare.ui

import androidx.compose.runtime.Composable

/**
 * ⭐⭐ Ask for Android 13's POST_NOTIFICATIONS, once it would mean something: the ONE ask, called by every
 * action whose progress or state lives in a notification (a download, the API's listening notice).
 * ⚠ Ungranted, every notification is dropped in silence: the API switch never asked, and a tester read
 * "a notification shows while it listens" and saw none (2026-10-19).
 */
@Composable
fun rememberNotifyAsk(): () -> Unit {
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
