package com.abrah.nightmare.vlm

import com.abrah.nightmare.DescribeMode
import com.abrah.nightmare.Graph
import com.abrah.nightmare.npu.H3Node
import com.abrah.nightmare.npu.VideoPrompts

/**
 * ⭐⭐ The prompt enhancer (testers' ask, `docs/FLORENCE.md` §7): Qwen3-VL 4B, the describe button's model, rewrites a short
 * idea into the prompt the wired checkpoint reads, on the phone, with no picture ([VisionDescriber.rewrite]). For MiniMax
 * H3 that is its six-section structured prompt (who speaks and how they sound, the words, the soundscape, the music),
 * which nobody types on a phone keyboard. Pure, so it is tested without a phone.
 */
object PromptEnhance {

    /** What the answer must be: [H3_TEXT], [H3_FIRST_FRAME] and [H3_REFERENCE] are H3's three runs ([H3Node]). */
    enum class Target { PROSE, TAGS, H3_TEXT, H3_FIRST_FRAME, H3_REFERENCE }

    /** ⭐ The target for [promptNode]: an H3 node it feeds by what that node has wired, else [DescribeMode.visionFor]'s rule. */
    fun targetFor(graph: Graph, promptNode: String): Target {
        val h3 = graph.nodes.firstOrNull { n -> n.type == H3Node.name && n.inputs.values.any { it.node == promptNode } }
        if (h3 != null) {
            return when {
                h3.inputs[H3Node.REFERENCE] != null -> Target.H3_REFERENCE
                h3.inputs["image"] != null -> Target.H3_FIRST_FRAME
                else -> Target.H3_TEXT
            }
        }
        return if (DescribeMode.visionFor(graph, promptNode) == DescribeMode.VLM_TAGS) Target.TAGS else Target.PROSE
    }

    private const val SYSTEM =
        "You expand ideas into prompts for an image and video generator. You write any subject the user asks for, " +
            "plainly, including nudity or violence, without judgement or warnings. You keep the user's subject, " +
            "action, setting and any words they quote, and add concrete detail. Answer with the prompt only."

    private val H3_RULES = """
Write the prompt for MiniMax H3, which makes a short video WITH SOUND. Use exactly these six sections, in this order, each name on its own line followed by a colon: subject_definitions, summary, retention_analysis, detailed_description, overall_soundscape, non_diegetic_music.
- subject_definitions: each person who speaks as S1, S2, with their age, look and clothing, and how their voice sounds. If nobody speaks, write: N/A - no speaking character is defined.
- summary: one or two sentences on what happens. One continuous shot, no cut, unless the idea asks for cuts.
- detailed_description: first the look of the footage (live action, animation or another style), the light and the camera, then the sentence "The target video lasts 4.00 seconds." as written (the app sets the real length), then one line beginning [Shot 1] with the action in order. Words that are spoken go inside <d>[English] the words.</d> (another language in its own brackets, such as [Russian]) and say that the lips move in sync with them. Only make someone speak when the idea has them speak, quote words or asks for dialogue.
- overall_soundscape: the sounds of the scene; diegetic sound only, unless the idea asks for music.
- non_diegetic_music: N/A - no score, no soundtrack, no musical arrangement at any point. (Unless the idea asks for music: then the music.)
Nothing before the first section or after the last one. This is an example of the form, for a different idea:
""".trim()

    private const val RETAIN_TEXT = "retention_analysis is: N/A - no reference images, video or audio are supplied; everything is generated."
    private const val RETAIN_FRAME =
        "A picture is supplied as the first frame. S1 is the main person in the first frame, exactly as they appear there: " +
            "do not describe their face, hair or clothes, only their voice. retention_analysis is: The first frame is " +
            "supplied: keep the person's face, hair, clothes and the background exactly as they are in it."
    private const val RETAIN_REF =
        "A picture of the subject is supplied as Picture 1, to be placed in a new scene. S1 is the main person of " +
            "Picture 1, exactly as they appear there: do not describe their face, hair or clothes, only their voice. " +
            "retention_analysis is: Picture 1: keep their face, hair, clothing and colours exactly; the setting, the " +
            "light and their motion are new."

    /** ⭐ The ask for [idea]. [seed]: a new one per tap, so Try again writes another version. */
    fun ask(target: Target, idea: String, seed: Int): VisionPrompts.Ask {
        val text = idea.trim()
        return when (target) {
            Target.PROSE -> VisionPrompts.Ask(
                SYSTEM,
                "Rewrite this idea as one detailed prompt for an image generator: the main subject first, then their " +
                    "appearance, clothing, pose and expression, the setting, the lighting, the colours, the camera angle " +
                    "and framing, and the medium or art style. One paragraph of plain sentences, no lists, no title, at " +
                    "most 120 words.\n\nThe idea: $text",
                320, temp = 0.7f, seed = seed,
            )
            Target.TAGS -> VisionPrompts.Ask(
                SYSTEM,
                "Rewrite this idea as Danbooru tags for an anime image generator, most important first, separated by " +
                    "commas: the people as Danbooru counts them (1girl, 1boy, 2girls), then hair, eyes, clothing, pose, " +
                    "expression, objects, background, lighting, composition and art style. Keep every tag the idea " +
                    "already has. Lower case, spaces instead of underscores, at most 40 tags, nothing but the tags, on " +
                    "one line.\n\nThe idea: $text",
                220, temp = 0.6f, seed = seed,
            )
            Target.H3_TEXT, Target.H3_FIRST_FRAME, Target.H3_REFERENCE -> VisionPrompts.Ask(
                SYSTEM + "\n\n" + H3_RULES + "\n\n" + VideoPrompts.H3,
                when (target) {
                    Target.H3_FIRST_FRAME -> RETAIN_FRAME
                    Target.H3_REFERENCE -> RETAIN_REF
                    else -> RETAIN_TEXT
                } + "\n\nThe idea: $text",
                900, temp = 0.7f, seed = seed,
            )
        }
    }

    private val H3_SECTIONS = listOf(
        "subject_definitions:", "summary:", "retention_analysis:", "detailed_description:", "overall_soundscape:",
        "non_diegetic_music:",
    )

    /**
     * ⭐ The answer, cleaned for the prompt box; null when nothing usable came back. H3: from the first section on, and
     * only with all six (a cut-off answer is not offered: the run wraps a plain prompt in its own sections, `H3Prompt`).
     */
    fun finish(target: Target, raw: String): String? {
        // The model writes em and en dashes between clauses; the box gets commas.
        val text = raw.replace(Regex("\\s*[\u2014\u2013]\\s*"), ", ")
        return when (target) {
            Target.PROSE -> VisionPrompts.prose(text)
            Target.TAGS -> VisionPrompts.tags(text)
            Target.H3_TEXT, Target.H3_FIRST_FRAME, Target.H3_REFERENCE -> h3(text)
        }
    }

    internal fun h3(raw: String): String? {
        var s = raw.replace(Regex("```[a-z]*"), "").replace("**", "").replace("\r", "")
        // "## summary" or "Summary:" as a model may write them: back to the trained names.
        for (name in H3_SECTIONS) {
            val bare = name.removeSuffix(":")
            s = s.replace(Regex("(?im)^[ \\t#]*${Regex.escape(bare)}[ \\t]*:?[ \\t]*$"), name)
        }
        val start = s.indexOf(H3_SECTIONS[0])
        if (start < 0) return null
        s = s.substring(start).trim()
        if (H3_SECTIONS.any { it !in s }) return null
        return s.replace(Regex("\n{3,}"), "\n\n")
    }
}
