package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ The process scheduler — `docs/ARCHITECTURE.md` §4.
 *
 * ⚠⚠ Every transition costs a kill + relaunch of the backend, **2.3–5 s**, about
 * as much as a whole render. So the thing worth testing is not that the order is
 * valid — `topoSort` guarantees that — but that it does not switch checkpoints
 * more often than it has to, and that it never emits a node before something it
 * depends on just to save a switch.
 */
class SchedulerTest {

    private fun n(id: String, vararg from: String) =
        Node(id, "t", emptyMap(), sources(*from.map { "in$it" to it }.toTypedArray()))

    private val a = ContextKey("sd15npu", "absolutereality", 512, 512)
    private val b = ContextKey("sdxl", "epicrealism", 1024, 1024)

    private fun keyed(vararg pairs: Pair<String, ContextKey?>): (Node) -> ContextKey? {
        val m = pairs.toMap()
        return { node -> m[node.id] }
    }

    /** ⚠ The order must still be a valid topological one — that is not negotiable. */
    private fun assertTopological(order: List<Node>) {
        val seen = mutableSetOf<String>()
        for (node in order) {
            for (src in node.inputs.values) {
                assertTrue("${node.id} ran before ${src.node}", src.node in seen)
            }
            seen += node.id
        }
    }

    @Test
    fun oneKeyIsLeftAlone() {
        val order = listOf(n("a"), n("b", "a"))
        assertEquals(order, scheduleByKey(order, keyed("a" to a, "b" to a)))
    }

    /**
     * ⭐⭐ The case the whole thing exists for: two independent branches on two
     * checkpoints, interleaved by the user. Two switches would be four
     * relaunches' worth of waiting; grouping them costs ONE.
     */
    @Test
    fun interleavedBranchesAreGrouped() {
        val order = listOf(n("a1"), n("b1"), n("a2"), n("b2"))
        val keyOf = keyed("a1" to a, "b1" to b, "a2" to a, "b2" to b)
        val out = scheduleByKey(order, keyOf)
        assertTopological(out)
        assertEquals(1, keyTransitions(out, keyOf))
        // ⚠ …and the UNSCHEDULED order really was worse, or this proves nothing.
        assertEquals(3, keyTransitions(order, keyOf))
    }

    /**
     * ⚠⚠ A CHAIN across checkpoints cannot be grouped — `a → b → a` genuinely
     * needs the first checkpoint twice. The scheduler must not "optimise" it by
     * running the second `a` early, which would feed it a picture that does not
     * exist yet.
     */
    @Test
    fun aDependencyChainIsNotReordered() {
        val order = listOf(n("a1"), n("b1", "a1"), n("a2", "b1"))
        val keyOf = keyed("a1" to a, "b1" to b, "a2" to a)
        val out = scheduleByKey(order, keyOf)
        assertTopological(out)
        assertEquals(listOf("a1", "b1", "a2"), out.map { it.id })
        assertEquals(2, keyTransitions(out, keyOf))
    }

    /**
     * ⭐ A node with NO key runs under whatever is loaded and never forces a
     * switch — that is what makes a mixed graph tractable (`image.upscale`, the
     * video path, every app-side node).
     */
    @Test
    fun keylessNodesNeverCauseATransition() {
        val order = listOf(n("a1"), n("free"), n("a2"))
        val keyOf = keyed("a1" to a, "free" to null, "a2" to a)
        val out = scheduleByKey(order, keyOf)
        assertTopological(out)
        assertEquals(0, keyTransitions(out, keyOf))
    }

    /**
     * ⚠⚠ The user's node ORDER breaks a tie — §4's rule for batch axes, and the
     * same instinct here. With one node ready on each key, the one they listed
     * first goes first.
     */
    @Test
    fun aTieFollowsTheUsersOrder() {
        val order = listOf(n("b1"), n("a1"))
        val out = scheduleByKey(order, keyed("b1" to b, "a1" to a))
        assertEquals("b1", out.first().id)
    }

    /** ⚠ A permutation, never a filter: losing a node here would lose a render. */
    @Test
    fun everyNodeSurvives() {
        val order = listOf(n("a1"), n("b1"), n("a2", "a1"), n("b2", "b1"), n("free", "a2"))
        val out = scheduleByKey(
            order, keyed("a1" to a, "b1" to b, "a2" to a, "b2" to b, "free" to null),
        )
        assertEquals(order.map { it.id }.toSet(), out.map { it.id }.toSet())
        assertEquals(order.size, out.size)
        assertTopological(out)
    }

    /**
     * ⭐⭐ Work under the key that is ALREADY loaded runs first — a two-checkpoint
     * graph whose second checkpoint is up does not open by switching away.
     */
    @Test
    fun theLoadedKeyGoesFirst() {
        val order = listOf(n("a1"), n("b1"))
        val keyOf = keyed("a1" to a, "b1" to b)
        assertEquals(listOf("b1", "a1"), scheduleByKey(order, keyOf, start = b).map { it.id })
    }

    /**
     * ⚠⚠ The pre-run launch is for the NODE's checkpoint, never a global one.
     * Launching for the selection made every Run start twice — once for the
     * selected model, once more for the sampler's. Reported 2026-09-16.
     */
    @Test
    fun theLaunchKeyIsTheFirstTheScheduleReaches() {
        val keyed = object : NodeType {
            override val name = "t"
            override val version = "1"
            override val category = "misc"
            override val inputs = emptyList<Port>()
            override val outputs = listOf(Port("out", "IMAGE"))
            override fun contextKey(node: Node) = when (node.id) { "a1" -> a; "b1" -> b; else -> null }
            override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
                throw UnsupportedOperationException("schedule only")
        }
        val types = mapOf("t" to keyed)
        val g = Graph(listOf(n("p"), n("b1", "p"), n("a1", "p")))
        assertEquals(b, launchKeyFor(g, types, loaded = null))
        assertEquals(a, launchKeyFor(g, types, loaded = a))
        assertEquals(null, launchKeyFor(Graph(listOf(n("p"))), types, loaded = null))
    }
}

