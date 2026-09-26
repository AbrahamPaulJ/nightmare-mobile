package com.abrah.nightmare.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * ⭐⭐⭐ **Undo AND redo** — the mask window's and the object chooser's, the
 * user's ask 2026-09-24: *"a redo btn next to the undo btn. undo/redo should
 * register the object additions as well"*.
 *
 * ⚠⚠ It WATCHES the value rather than being told about each edit. A mask
 * changes from a stroke, a tap that lands a second later, Invert, Clear, and
 * now an object kept, moved or removed — five writers, and a history that each
 * of them had to remember to feed would be missing whichever one was added
 * next. Every change seen here is one step; the one an undo or redo itself
 * causes is recognised and not recorded.
 *
 * ⚠ In memory, for as long as the window is open. The mask window falls back
 * to dropping the last stroke once this runs out ([undo]'s `fallback`), which
 * is what Undo did before there was a history at all.
 */
internal class EditHistory<T : Any> {
    private var last: T? = null
    private var expecting: T? = null
    private val undos = mutableStateListOf<T>()
    private val redos = mutableStateListOf<T>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** The value as it is now — called after every change, by [rememberEditHistory]. */
    fun observe(now: T) {
        val before = last
        last = now
        if (before == null || before == now) return
        val own = now == expecting
        expecting = null
        if (own) return
        undos += before
        redos.clear()
    }

    /**
     * Back one step. [fallback] is where to go when nothing was recorded —
     * null for nowhere.
     */
    fun undo(now: T, fallback: T? = null, apply: (T) -> Unit) {
        val target = undos.removeLastOrNull() ?: fallback ?: return
        redos += now
        expecting = target
        apply(target)
    }

    fun redo(now: T, apply: (T) -> Unit) {
        val target = redos.removeLastOrNull() ?: return
        undos += now
        expecting = target
        apply(target)
    }
}

/** ⭐ One history per [key] — a node, a source photo — watching [now]. */
@Composable
internal fun <T : Any> rememberEditHistory(key: Any?, now: T): EditHistory<T> {
    val history = remember(key) { EditHistory<T>() }
    LaunchedEffect(history, now) { history.observe(now) }
    return history
}
