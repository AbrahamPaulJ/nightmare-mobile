package com.abrah.nightmare.agent

import android.graphics.Bitmap
import com.abrah.nightmare.Graph
import com.abrah.nightmare.HarnessViewModel
import com.abrah.nightmare.LoraSources
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.canvas.CanvasState
import com.abrah.nightmare.canvas.Workflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ What the agent may do to the app (`docs/AGENT-API.md` §4b) — the CANVAS the person is
 * looking at, its Run, and the LoRA files. One interface so [AgentTools] is tested against a
 * fake, and [VmCanvasHost] is the only code that touches the view model.
 *
 * ⚠ Called from the agent's IO thread; every method is blocking.
 */
interface CanvasHost {
    val types: Map<String, NodeType>
    fun workflow(): Workflow
    /** The flow's saved name (null = never saved) and whether it has unsaved edits. */
    fun flowState(): Pair<String?, Boolean>
    /** ⚠ Unguarded — the agent asked the person first ([AgentTools] `new_flow`). */
    fun replace(w: Workflow, recipeId: String?)
    /** A transform on the LIVE canvas (`HarnessViewModel.editCanvas`'s rule). */
    fun edit(change: (CanvasState) -> CanvasState)
    /** Saves under [name], or the flow's own name, or the name the save dialog would offer. Returns it. */
    fun save(name: String?): String
    /** ⭐ Presses Run and waits. Null = started and finished; else why it did not start. */
    fun run(): String?
    fun cancelRun()
    /** What the last Run left: its error (null = none), the run log, and each output node's picture. */
    fun lastRun(): Triple<String?, List<String>, Map<String, Bitmap>>
    fun loras(): List<String>
    /** Downloads [version] and waits; null = done, else why not. */
    fun downloadLora(label: String, version: LoraSources.Version): String?
    fun deleteLora(name: String)
}

/** ⭐ [CanvasHost] over the view model — every call hops to the main thread, as a tap would. */
class VmCanvasHost(
    private val vm: HarnessViewModel,
    /**
     * ⚠ Runs [block] on the main thread and returns its value. Identity in a Robolectric test.
     * ⚠⚠ Directly when ALREADY on it: the chat's Stop calls [cancelRun] from a tap, and
     * `runBlocking(Main)` on the main thread waits for itself. The Cancel did nothing and the app froze
     * (a tester, 2026-10-16).
     */
    private val onMain: (() -> Any?) -> Any? = { block ->
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else runBlocking(Dispatchers.Main) { block() }
    },
) : CanvasHost {

    @Suppress("UNCHECKED_CAST")
    private fun <T> main(block: () -> T): T = onMain(block) as T

    override val types get() = vm.nodeTypes
    override fun workflow() = main { vm.canvas.workflow }
    override fun flowState() = main { vm.activeFlow.let { it.name to it.dirty } }
    override fun replace(w: Workflow, recipeId: String?) = main { vm.openForAgent(w, recipeId) }
    override fun edit(change: (CanvasState) -> CanvasState) = main { vm.editCanvas(change) }

    override fun save(name: String?): String = main {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: vm.activeFlow.name ?: vm.suggestedFlowName()
        vm.saveWorkflowAs(n)
        vm.workflowError?.let { throw IllegalArgumentException(it) }
        n
    }

    override fun run(): String? {
        vm.agentRunning = true
        try {
            val refused = main { vm.runCanvas() }
            if (refused != null) return refused
            runBlocking { withContext(Dispatchers.Main) { vm.runJobNow() }?.join() }
            return null
        } finally {
            vm.agentRunning = false
        }
    }

    /** ⭐ True while [downloadLora] waits: the chat's Stop then stops the download too. */
    @Volatile private var downloading = false
    @Volatile private var stopped = false

    override fun cancelRun() {
        main { vm.cancelRun() }
        // ⚠ Stop used to leave the agent's LoRA download running, the chat busy until the file finished.
        if (downloading) {
            stopped = true
            main { vm.cancelModelInstall() }
        }
    }

    override fun lastRun() = main {
        val g = vm.canvas.workflow.graph
        val pictures = g.nodes.filter { it.type == com.abrah.nightmare.MediaOutputNode.name }
            .mapNotNull { n -> vm.canvas.previews[n.id]?.first?.let { id -> vm.imageOf(id)?.let { n.id to it } } }
            .toMap()
        Triple(vm.runError, vm.runLog.lines.map { (if (it.bad) "⚠ " else "") + "${it.id}: ${it.text}" }, pictures)
    }

    override fun loras() = main { vm.loraRows.map { it.name } }

    override fun downloadLora(label: String, version: LoraSources.Version): String? {
        if (main { vm.installingNow }) return "another download is running. Try again when it finishes"
        stopped = false
        downloading = true
        try {
            main { vm.downloadLora(label, version) }
            while (main { vm.installingNow }) Thread.sleep(500)
        } finally {
            downloading = false
        }
        if (stopped) return "the person stopped the download"
        return main { vm.loraFetchError?.let { it.second.message ?: "the download failed" } }
    }

    override fun deleteLora(name: String) = main { vm.deleteLora(name) }
}

/** The graph a canvas edit produced — for a tool that edits and then reads back. */
internal fun CanvasHost.graph(): Graph = workflow().graph
