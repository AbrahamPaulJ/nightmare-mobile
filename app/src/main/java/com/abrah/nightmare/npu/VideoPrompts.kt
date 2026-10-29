package com.abrah.nightmare.npu

/**
 * ⭐⭐ Each video model's opening prompt, written for what that model does best (the user, 2026-10-30: "the fox thing is
 * so basic, give each a thoughtful prompt depending on its capability"). THE table: the recipes open with it, and
 * [HarnessViewModel.setVideoModel] swaps it when the Model dropdown changes the node, but only while the prompt is
 * still one of these (or empty), never over the person's own text ([isDefault]).
 */
object VideoPrompts {

    /** ⭐ Wan 2.2 Turbo, text to video: 24 fps, good at camera moves, weather and light; 5 s at 832x480 in under 3 min. */
    const val WAN_T2V =
        "A street musician plays a saxophone under a neon-lit awning on a rainy night in Tokyo, raindrops glittering " +
            "in the pink and blue light, steam drifting from a food stall behind him while people with umbrellas hurry " +
            "past. Slow dolly-in from a low angle, shallow depth of field, cinematic colour grade."

    /** ⭐ Wan 2.2 Turbo, image to video: the picture is the first frame, so the prompt is the MOTION, for any picture. */
    const val WAN_I2V =
        "The subject comes to life: a slow breath, a slight turn of the head and a soft smile, hair and clothes moving " +
            "in a light breeze, the light shifting gently across the scene. The camera pushes in slowly and steadily."

    /**
     * ⭐ Wan 2.2 14B, image to video: the best motion and faces (720p for faces), so a performance with small, readable
     * detail: eyes, expression, hair against wind.
     */
    const val WAN14_I2V =
        "The person looks up, blinks and breaks into a warm, genuine smile, then tilts their head as a gust of wind " +
            "lifts strands of hair across their face. Natural skin texture and realistic, unhurried motion; the " +
            "background stays still while the camera drifts slowly to the left."

    /**
     * ⭐ Text to video (14B): ONE prompt read twice, by the picture model for the first frame and by the 14B for the
     * motion, so it describes a picture and then what happens in it.
     */
    const val WAN14_T2V =
        "A young woman in a yellow raincoat stands at the end of a wooden pier at golden hour, waves breaking behind " +
            "her and gulls overhead. She turns towards the camera and laughs as the wind pulls back her hood. " +
            "Photorealistic, 35 mm film look, warm low sunlight."

    /** ⭐ NSFW Wan 1.3B: a community fine-tune for adult content, 16 fps, 480p; sensual, slow, close. */
    const val NSFW13 =
        "A woman in black lace lingerie lounges on rumpled silk sheets in a candlelit bedroom, slowly stretching, then " +
            "looks over her shoulder at the camera with a playful smile. Warm low light, soft focus, the camera gliding " +
            "slowly closer."

    /** ⭐ Neodragon: 2 s in ~25 s from a picture model's first frame, best at one big, simple motion. */
    const val NEODRAGON =
        "Ocean waves roll onto a black sand beach at sunset, white foam sliding up the shore and pulling back, golden " +
            "light glittering on the water, a slow aerial pan along the coastline."

    /** ⭐ Motion transfer (Fun Control): the picture is WHO, the video is HOW they move; the prompt keeps the look. */
    const val MOTION =
        "The person from the picture dancing in a bright, plain studio: the same face, hair and outfit, sharp detailed " +
            "face and hands, natural light, steady camera."

    /**
     * ⭐⭐ MiniMax H3, video WITH SOUND, in the structured form it was trained on (`docs/MINIMAX-H3.md` §5c): who is in it
     * and how they sound, what is said, the soundscape, the music. Shows what H3 alone does here: a person speaking a
     * line on camera, lips in sync, with the room's own sound. [H3Prompt] sets the stated length to the clip's.
     */
    const val H3 = """subject_definitions:
S1 is a barista in her twenties with curly red hair tied back, wearing a green apron over a white shirt. She has a bright, friendly, slightly husky voice.

summary:
Text to video with audio. One continuous shot, no cut. A barista finishes a latte in a busy café, looks up at the camera and says one line out loud.

retention_analysis:
N/A - no reference images, video or audio are supplied; everything is generated.

detailed_description:
Live action footage, warm morning light through the café windows, shallow depth of field, static camera at counter height. The target video lasts 2.33 seconds.
[Shot 1] Medium close-up of the barista (S1) behind the counter, her face and mouth clearly visible. She sets down a cup with a leaf drawn in the foam, looks into the camera and says out loud: <d>[English] Your flat white, made on a phone.</d> Her lips move clearly and naturally in sync with the words; this is not a voiceover, the sound comes from her mouth in the shot. She smiles at the end.

overall_soundscape:
Diegetic sound only. The hiss of the espresso machine, cups clinking on saucers and the soft murmur of customers, her voice close to the microphone.

non_diegetic_music:
N/A - no score, no soundtrack, no musical arrangement at any point."""

    /**
     * ⭐ MiniMax H3 with a picture wired: the picture is the first frame, so the subjects are whoever is in it and the
     * prompt says what they do and say. Any picture with a person in it reads; one without one still gets its sound.
     */
    const val H3_I2V = """subject_definitions:
S1 is the main person in the first frame, exactly as they appear there. They have a clear, natural, friendly voice.

summary:
Image to video with audio. One continuous shot, no cut. The person from the first frame comes to life, looks into the camera and says one line out loud.

retention_analysis:
The first frame is supplied: keep the person's face, hair, clothes and the background exactly as they are in it.

detailed_description:
Live action footage, the light and colours of the first frame, static camera. The target video lasts 2.33 seconds.
[Shot 1] The person (S1) takes a small breath, looks into the camera and says out loud: <d>[English] Hello! I was a picture a moment ago.</d> Their lips move clearly and in sync with the words; the sound comes from their mouth in the shot. They smile at the end.

overall_soundscape:
Diegetic sound only. The quiet ambience of the place in the picture, their voice close to the microphone.

non_diegetic_music:
N/A - no score, no soundtrack, no musical arrangement at any point."""

    /**
     * ⭐ MiniMax H3 as an EDITOR ([canvas.h3EditWorkflow], `docs/MINIMAX-H3.md` §5f): the picture is the first frame, the
     * prompt makes ONE change happen over a short clip, and the last frame is the edit. Keep says what must not move.
     */
    const val H3_EDIT = """subject_definitions:
S1 is the person in the first frame, exactly as they appear there.

summary:
Image to video with audio. One continuous shot, no cut. The person from the first frame stands still while their clothes change into an elegant red silk dress.

retention_analysis:
The first frame is supplied: keep the face, hair, pose, the background and everything else exactly as they are in it. Only the clothes change.

detailed_description:
Live action footage, the light and colours of the first frame, static camera. The target video lasts 1.62 seconds.
[Shot 1] The person (S1) stays still and looks into the camera. Their clothes smoothly transform into a deep red silk dress with thin straps; by the end of the shot they are clearly wearing the red silk dress. Their face, pose and the background do not change.

overall_soundscape:
Diegetic sound only. A quiet room, the soft rustle of silk.

non_diegetic_music:
N/A - no score, no soundtrack, no musical arrangement at any point."""

    /** ⭐ A video node type's opening prompt; [i2v] when a picture is wired (the Wan nodes animate it). */
    fun forType(type: String, i2v: Boolean): String = when (type) {
        H3Node.name -> if (i2v) H3_I2V else H3
        WanNsfw13Node.name -> NSFW13
        WanA14Node.name -> WAN14_I2V
        WanFunNode.name -> MOTION
        VideoGate.VIDEO_TYPE -> if (i2v) WAN_I2V else NEODRAGON
        else -> if (i2v) WAN_I2V else WAN_T2V
    }

    private val ALL = listOf(WAN_T2V, WAN_I2V, WAN14_I2V, WAN14_T2V, NSFW13, NEODRAGON, MOTION, H3, H3_I2V, H3_EDIT)

    /**
     * ⚠ Pure: whether [prompt] is still an opening prompt (or empty), so a model switch may replace it. The fox prompts
     * of 1.6.260 and before count too: a flow saved with one gets the new model's prompt on its next switch.
     */
    fun isDefault(prompt: String): Boolean {
        val t = prompt.trim()
        return t.isEmpty() || ALL.any { it.trim() == t } || t in LEGACY
    }

    private val LEGACY = setOf(
        "a red fox trotting through a snowy pine forest, soft morning light, cinematic",
        "the camera slowly pushes in, gentle natural motion",
        "a person dancing, natural light, detailed face",
        "gentle camera push in, subtle motion",
        "a cat walking through tall grass, cinematic",
    )
}
