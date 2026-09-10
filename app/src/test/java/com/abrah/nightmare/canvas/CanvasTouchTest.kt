package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⚠⚠ The gesture MODIFIER, driven by real touch events.
 *
 * `CanvasStateTest` calls `press`/`drag`/`release` directly, so it proves the
 * state machine and nothing about whether a finger ever reaches it. That gap is
 * how a canvas that "responds to touch" in the runbook could ship unable to move
 * a node: every JVM test passed, every golden passed, and the only thing never
 * exercised was the wiring between a pointer event and the state machine.
 *
 * ⚠ These tests must fail if the modifier is rewired wrongly, so they assert on
 * the STATE the modifier produced, not on pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CanvasTouchTest {

    @get:Rule
    val rule = createComposeRule()

    private val types = NODE_TYPES

    /** Filled in by the composition; the phone reports ~3. */
    private var density = 1f

    /**
     * ⚠⚠ Drives the REAL `CanvasScreen`, not a hand-rolled Box.
     *
     * The first version of this file built its own Box and passed
     * `state = { state }` where `state` was a delegated local `var`. That
     * closure reads the MutableState live, so it passed while the app was
     * broken: in `CanvasScreen` the same expression captures a function
     * PARAMETER by value, and `pointerInput(Unit)` never restarts, so the
     * gesture loop read the state from first composition forever.
     *
     * ⇒ A touch test that does not go through the production composable tests
     * its own wiring. Measured 2026-09-08: four green tests over a canvas that
     * could not move a node on a real phone.
     */
    private fun canvas(initial: CanvasState): () -> CanvasState {
        var state by mutableStateOf(initial)
        rule.setContent {
            density = LocalDensity.current.density
            CanvasScreen(
                state = state,
                types = types,
                status = emptyMap(),
                busy = false,
                image = null,
                onGesture = { state = it },
                onRun = {},
                onBack = {},
            )
        }
        return { state }
    }

    private val oneNode = CanvasState(
        Workflow(
            com.abrah.nightmare.Graph(listOf(Node("s", "sd.sample"))),
            mapOf("s" to Pt(40f, 60f)),
        )
    )

    /**
     * ⭐ Drag a node and it MOVES, and keeps moving for the whole gesture.
     *
     * ⚠ The multi-step drag is the point. A single move can succeed while the
     * gesture is being restarted under it; only a sustained drag shows whether
     * the handler survives the recompositions its own updates cause.
     */
    @Test
    fun aNodeFollowsTheFinger() {
        val state = canvas(oneNode)
        // World (40,60)+ is the node; touch is in PIXELS, so scale by density.
        val start = Offset(60f * density, 80f * density)

        rule.onRoot().performTouchInput {
            down(start)
            moveTo(start + Offset(20f, 20f) * density)
            moveTo(start + Offset(40f, 40f) * density)
            moveTo(start + Offset(60f, 60f) * density)
            up()
        }
        rule.waitForIdle()

        val at = state().workflow.positions.getValue("s")
        assertNotEquals("the node did not move at all", Pt(40f, 60f), at)
        // Moved by the full gesture, not just the first step.
        // The drag was 60x60 DEVICE px, which is 60/density world units.
        assertEquals("did not follow the whole drag", 40f + 60f, at.x, 2f)
        assertEquals("did not follow the whole drag", 60f + 60f, at.y, 2f)
    }

    /** ⭐ One finger on empty space pans the viewport. */
    @Test
    fun draggingEmptySpacePansTheCanvas() {
        val state = canvas(oneNode)
        val start = Offset(320f * density, 700f * density)   // nowhere near the node

        rule.onRoot().performTouchInput {
            down(start)
            moveTo(start + Offset(-30f, -40f) * density)
            moveTo(start + Offset(-60f, -80f) * density)
            up()
        }
        rule.waitForIdle()

        val vp = state().viewport
        assertTrue("the canvas did not pan: offset ${vp.offset}", vp.offset.x != 0f || vp.offset.y != 0f)
        // ⚠ In DEVICE pixels, not world units: `toScreen` is world*scale+offset
        // with the density already inside scale, so the pan delta is applied raw.
        assertEquals(-60f * density, vp.offset.x, 2f)
        assertEquals(-80f * density, vp.offset.y, 2f)
    }

    /** ⭐ Two fingers zoom. */
    @Test
    fun pinchingZoomsTheCanvas() {
        val state = canvas(oneNode)

        rule.onRoot().performTouchInput {
            down(0, Offset(180f, 400f) * density)
            down(1, Offset(220f, 400f) * density)
            moveTo(0, Offset(120f, 400f) * density)
            moveTo(1, Offset(280f, 400f) * density)
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertTrue(
            "the canvas did not zoom: scale ${state().viewport.scale}",
            state().viewport.scale > 1.1f,
        )
    }

    /** ⭐ A tap on a node opens its inspector — how a user edits anything. */
    @Test
    fun tappingANodeOpensTheInspector() {
        val state = canvas(oneNode)

        rule.onRoot().performTouchInput {
            down(Offset(60f * density, 80f * density))
            up()
        }
        rule.waitForIdle()

        assertEquals("the inspector did not open", "s", state().editing)
        // ⚠⚠ …and the tap did NOT select it. On the phone this was the whole
        // bug: the node window opened and the canvas silently entered
        // multi-select behind it, run bar and all.
        assertTrue("a tap entered multi-select", state().selection.isEmpty())
    }

}
