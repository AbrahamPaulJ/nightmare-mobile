package com.abrah.nightmare.canvas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.abrah.nightmare.ui.ConfirmDelete

/** ⚠ Long enough to swallow a double tap, short enough not to block a retry. */
private const val DOWNLOAD_COOLDOWN_MS = 1200L

/**
 * ⭐⭐⭐ The actions on ONE rendered picture — bin · keep · download · share ·
 * star — and the only thing that draws them, on the canvas and in Results.
 *
 * ⚠⚠⚠ **The disk and the star swapped jobs, 2026-09-15.** Reported from the
 * phone: *"the save icon is confusing. what it currently does is a download."*
 * It was exactly that — a floppy that wrote a PNG to the gallery, while the
 * star did the keeping. Two glyphs, both meaning "save", neither meaning what
 * it looked like.
 *
 * | | before | now |
 * |---|---|---|
 * | 💾 [onKeep] | wrote to the gallery | **keeps in Results** |
 * | ⬇ [onDownload] | — | **writes to the gallery** |
 * | ⭐ [onStar] | kept in Results | **keeps in Results AND flags it Favourite** |
 *
 * ⚠ Two buttons that both keep is deliberate, not an oversight: the star is the
 * one-tap way to keep something you already know you want back, and Results
 * filters on the flag it sets. Starring a picture that is already kept just
 * toggles the flag.
 *
 * ⚠ Each action is nullable so a surface offers only what applies; the ORDER of
 * whatever is offered never changes — destructive first, then keep, download,
 * share, star. The caller puts the seed or a primary action AFTER this.
 */
@Composable
fun PictureActions(
    /** ⚠ White over a picture, the theme's colour on a surface. */
    tint: Color,
    deleteTint: Color,
    /** ⚠ A clip downloads and shares as the MP4, so the labels must not promise a picture. */
    isClip: Boolean,
    onDelete: (() -> Unit)?,
    /** ⭐ 💾 — keep it in Results, unflagged. Null when it cannot be kept. */
    onKeep: (() -> Unit)?,
    /** ⭐ ⬇ — export to the gallery. */
    onDownload: (() -> Unit)?,
    onShare: (() -> Unit)?,
    /** ⭐ ⭐ — keep it AND flag it. */
    onStar: (() -> Unit)?,
    /** Whether this is already in Results at all — the disk's tick state. */
    kept: Boolean,
    /** Whether it is flagged — the star's amber/grey state. */
    favourite: Boolean,
    starKeptTint: Color,
    starIdleTint: Color,
    /**
     * ⭐⭐ Why the keep button is disabled, or null when it is live.
     *
     * ⚠⚠ The output node's **autosave** keeps every Run on its own, so keeping
     * by hand would be a second copy of a picture that is already there. A
     * disabled button that SAYS why is the honest version — silently doing
     * nothing, or quietly making a duplicate, are both worse.
     */
    keepDisabledReason: String? = null,
    onDisabledKeep: ((String) -> Unit)? = null,
) {
    var downloaded by remember { mutableStateOf(false) }
    // ⚠ Survives recomposition, resets with the surface — which is right: a new
    // viewer on a new picture is a new download.
    var lastDownload by remember { mutableStateOf(0L) }
    var confirming by remember { mutableStateOf(false) }
    val what = if (isClip) "clip" else "picture"

    // ⚠⚠ **CLEAR, not delete** (the user's call, 2026-09-17): on a node this
    // empties the node; the picture is gone only if nothing else holds it. An
    // AUTOSAVED History copy goes with it; a hand-kept one stays.
    onDelete?.let {
        IconButton(onClick = { confirming = true }) {
            Icon(Icons.Filled.Delete, contentDescription = "clear this $what", tint = deleteTint)
        }
    }
    // ⭐ 💾 Keep — into Results, with the flow that made it.
    onKeep?.let { keep ->
        IconButton(
            onClick = {
                if (keepDisabledReason != null) onDisabledKeep?.invoke(keepDisabledReason)
                else keep()
            },
        ) {
            Icon(
                if (kept) Icons.Filled.Check else com.abrah.nightmare.ui.SaveIcon,
                contentDescription = when {
                    keepDisabledReason != null -> keepDisabledReason
                    kept -> "kept in Results"
                    else -> "keep this $what in Results"
                },
                // ⚠ Dimmed rather than hidden: the row must not change shape
                // depending on a setting on another node.
                tint = if (keepDisabledReason != null) tint.copy(alpha = 0.38f) else tint,
            )
        }
    }
    // ⭐ ⬇ Download — the export, and the only thing that touches the gallery.
    //
    // ⚠⚠ **It does NOT turn into a tick.** The user's call, 2026-09-15: a
    // download is repeatable, and a button that becomes a tick reads as done —
    // so a second copy, or a copy after deleting the first from the gallery,
    // looked impossible. ⇒ The glyph never changes and the toast is the
    // feedback. ⚠ The KEEP button still ticks, because being in Results is a
    // STATE with two values, not an action you can repeat.
    onDownload?.let { download ->
        IconButton(
            onClick = {
                // ⚠⚠ A COOLDOWN, not a one-shot. The glyph never becomes a tick
                // — a download is repeatable — but an unresponsive moment makes
                // people tap twice, and two taps must not mean two files in the
                // gallery. ⇒ Repeats are allowed; repeats WITHIN a second are
                // the same tap. The user's call, 2026-09-15.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastDownload > DOWNLOAD_COOLDOWN_MS) {
                    lastDownload = now
                    downloaded = true
                    download()
                }
            },
        ) {
            Icon(
                com.abrah.nightmare.ui.DownloadIcon,
                contentDescription = "save this $what to the gallery",
                tint = tint,
            )
        }
    }
    onShare?.let { share ->
        IconButton(onClick = share) {
            Icon(com.abrah.nightmare.ui.ShareIcon, contentDescription = "share this $what", tint = tint)
        }
    }
    onStar?.let { star ->
        IconButton(onClick = star) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (favourite) "remove from favourites" else "keep and favourite",
                // ⚠⚠ The TINT carries the state, and it follows FAVOURITE rather
                // than kept: the star's own job is the flag now, and one that lit
                // up because the disk had been tapped would say the wrong thing.
                tint = if (favourite) starKeptTint else starIdleTint,
            )
        }
    }

    if (confirming && onDelete != null) {
        ConfirmDelete(
            title = "Clear this $what?",
            confirmLabel = "Clear",
            // ⚠ Says whether there is another copy — the render is often the only one.
            body = "The node goes back to empty. " + when {
                downloaded -> "You saved it to the gallery, so that copy stays."
                kept -> "If autosave kept it, it goes from History too; a copy you kept or starred stays."
                else -> "It has NOT been saved to the gallery, and Run will make a " +
                    "different one unless the seed is locked."
            },
            onConfirm = onDelete,
            onDismiss = { confirming = false },
        )
    }
}
