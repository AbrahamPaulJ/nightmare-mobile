package com.abrah.nightmare

/**
 * ⭐⭐ `mask.segment_model` — tap to select, for inpainting (`docs/SEGMENTER.md`).
 *
 * ⚠ Renamed from `mask.select_object` / id `select` the day after it shipped, at
 * the user's call (2026-09-17): the node IS the model, so it is named for it and
 * shows the model in its body. `WorkflowIo.RENAMED` carries the old type.
 *
 * ⚠⚠ **Its whole job is to be wired.** No inputs, no knobs: connected to an
 * inpaint node's `segmenter` port, it makes the **Tap** tool appear in that
 * node's mask editor, beside the brush. Unwired, the inpaint node is exactly
 * what it was. The user's call, 2026-09-16 — the tapping happens where the
 * painting happens, not on a sheet of its own.
 *
 * ⚠ It earns a node by §5.7's test only barely, and says why: it is the
 * visible, wireable form of a CAPABILITY a person installs, and a second
 * producer of the same port (text selection, CLIPSeg) is the planned way to
 * grow it. The model itself runs app-side on the CPU (`segment.Segmenter`), so
 * this is the first node whose job is a MODEL running in the app — Tier 1's
 * first inhabitant, though built in (`docs/ARCHITECTURE.md` §3).
 *
 * ⚠ In the `com.abrah.nightmare` package, like every built-in type, so the
 * registry in `Executor.kt` names it without an import.
 */
object SelectObjectNode : NodeType {
    const val PORT_TYPE = "SEGMENTER"

    override val name = "mask.segment_model"
    override val version = "1"
    override val inputs = emptyList<Port>()
    override val outputs = listOf(Port("segmenter", PORT_TYPE))
    override val category = "inpaint"
    override val paletteName = "Segment model"
    override val defaultId = "segment_model"
    override val about = "wire into Inpaint, then tap objects in its mask editor to select them"

    const val MODEL = "model"

    /**
     * ⭐ The model, drawn IN the node's body like a prompt's text, and LOCKED:
     * there is one segmenter, so this is a statement rather than a choice — the
     * canvas draws a locked prose value greyed out. ⚠ A real widget rather than
     * a label, so a second model later is an option added here, not a new node.
     */
    override val prose = listOf(MODEL)
    override val widgets = listOf(
        Widget(
            MODEL, "string", com.abrah.nightmare.segment.Segmenter.LABEL,
            locked = "the only segmenter there is",
            options = listOf(com.abrah.nightmare.segment.Segmenter.LABEL),
        ),
    )

    /** ⚠ App-side and free: it produces a capability, not a computation. */
    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        Value.Capability(PORT_TYPE)
}
