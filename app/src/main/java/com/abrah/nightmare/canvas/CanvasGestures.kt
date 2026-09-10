package com.abrah.nightmare.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.abrah.nightmare.NodeType

/**
 * The only part of the canvas that cannot be unit-tested: raw pointer events.
 *
 * ⚠⚠ Which is exactly why it does no thinking. Every decision — what a press
 * starts, whether a wire may land, how far a node moved — is in [CanvasState],
 * which is Compose-free and covered by JVM tests. This file converts pointer
 * positions to world coordinates and forwards them, and if it ever grows an
 * `if` about *what* a gesture means, that `if` belongs in the state machine.
 *
 * ⚠ Written with `awaitEachGesture` rather than `detectTransformGestures` or
 * `detectDragGestures`, because neither can express "one finger might be a pan,
 * a node drag or a wire depending on what is underneath, but two fingers are
 * always a pinch". Composing the two detectors makes them fight for the same
 * events.
 */
fun Modifier.canvasGestures(
    state: () -> CanvasState,
    types: () -> Map<String, NodeType>,
    density: Float,
    onGesture: (CanvasState) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // ⚠ The device viewport, so a tap lands where the node was DRAWN. The
        // drawing applies the same conversion; using the logical viewport here
        // would put every hit-box off by the display density.
        fun world(screenX: Float, screenY: Float): Pt =
            state().viewport.forDevice(density).toWorld(Pt(screenX, screenY))

        // ⚠⚠ The gesture OWNS its state for its whole duration.
        //
        // Every step used to be computed from `state()` — the state read back
        // out of the composition. That is a frame behind: `press` publishes a
        // new state, but the recomposition that would make it readable has not
        // run by the time the next pointer event arrives, so `drag` was applied
        // to the state as it was BEFORE the press, whose gesture is still Idle,
        // and `drag` on Idle does nothing. The result on a real phone was a
        // canvas that could not be panned, zoomed or dragged at all, while
        // `press` itself worked perfectly — which is why the node highlighted
        // under a finger and then refused to move.
        //
        // ⇒ Thread it locally and publish each step. Correct by construction and
        // independent of when Compose decides to recompose. Measured 2026-09-08.
        var current = state().press(world(down.position.x, down.position.y), types())
        onGesture(current)
        down.consume()

        var lastCentroid = down.position
        // ⚠⚠ TOUCH SLOP, and without it the long press is unreachable on a real
        // phone. A finger resting on glass jitters, `positionChanged()` is true
        // for a fraction of a pixel, and every one of those used to disarm the
        // timer -- so the gesture worked in a test that moved nothing and never
        // once for a person holding the device. The same slop is why a tap that
        // wobbles still opens the inspector: below it the finger has not moved
        // AT ALL, so nothing drags and `moved` stays false.
        val downAt = down.position
        var slopBroken = false
        // ⭐⭐ A long press starts a multi-selection, and it has to fire WHILE
        // the finger is down -- a selection that only appeared on release would
        // give the user nothing to hold onto and no way to learn the gesture.
        //
        // ⚠⚠ Which means waiting on a TIMEOUT, not on an event: a finger held
        // perfectly still produces no pointer events at all, so there is nothing
        // to check the clock against. `withTimeoutOrNull` returning null IS the
        // long press. ⚠ Armed once -- after it fires, the loop goes back to
        // blocking, or every subsequent still moment would re-fire it.
        var longFired = false
        // ⚠⚠ A DEADLINE measured from the press, not a fresh timeout on each
        // await. `withTimeoutOrNull(500)` around a single `awaitPointerEvent`
        // restarts the clock every time an event arrives -- and a finger held on
        // glass delivers a jitter event every few milliseconds, so the 500 ms
        // was never once reached and the long press could not fire at all.
        val deadline = System.nanoTime() / 1_000_000 + viewConfiguration.longPressTimeoutMillis
        while (true) {
            val remaining = deadline - System.nanoTime() / 1_000_000
            val event = when {
                longFired -> awaitPointerEvent()
                remaining <= 0 -> null
                else -> withTimeoutOrNull(remaining) { awaitPointerEvent() }
            }
            if (event == null) {
                longFired = true
                // ⚠ `longPress` is a no-op unless the gesture is a node press
                // that has not moved, so a held finger on empty space or on a
                // port stays what it was.
                val next = current.longPress()
                if (next !== current) { current = next; onGesture(current) }
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                // ⚠⚠ Two fingers are ALWAYS a pinch, even if the gesture began
                // on a node. A second finger arriving mid-drag means the user
                // wants to zoom, not to fling the node they were holding.
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f && zoom > 0f) {
                    current = current.zoom(Pt(centroid.x, centroid.y), zoom)
                }
                val pan = event.calculatePan()
                if (pan.x != 0f || pan.y != 0f) {
                    // ⚠⚠ Through `CanvasState.pan`, not `viewport.panned`. This
                    // line used to move the viewport itself, which is a DECISION
                    // in the one file whose rule is that it makes none -- and the
                    // pan lock would then have applied to one finger and silently
                    // not to two.
                    current = current.pan(Pt(pan.x, pan.y))
                }
                onGesture(current)
                lastCentroid = centroid
            } else {
                val change = pressed.first()
                if (!slopBroken) {
                    val travel = (change.position - downAt).getDistance()
                    if (travel > viewConfiguration.touchSlop) slopBroken = true
                }
                if (slopBroken && change.positionChanged()) {
                    // ⚠ Real movement cancels a long press that has not fired
                    // yet. Without this, a slow drag turns into a multi-selection.
                    longFired = true
                    val delta = change.position - change.previousPosition
                    // ⚠ World coordinates from the CURRENT viewport, not the one
                    // the gesture started with: a pinch mid-gesture moves it.
                    val vp = current.viewport.forDevice(density)
                    current = current.drag(
                        vp.toWorld(Pt(change.position.x, change.position.y)),
                        Pt(delta.x, delta.y),
                        types(),
                    )
                    onGesture(current)
                    lastCentroid = change.position
                }
            }
            event.changes.forEach { it.consume() }
        }

        val vp = current.viewport.forDevice(density)
        onGesture(current.release(vp.toWorld(Pt(lastCentroid.x, lastCentroid.y)), types()))
    }
}
