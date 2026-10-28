# Edit a face

*The faces in a picture, changed with sliders: a smile, the eyes, where they look, the mouth, and
a turn, nod or tilt of the head. No checkpoint involved.*

**Nodes:** Image → Face edit → Output.

## First: install the Face editor

**Models → Faces → Face editor (LivePortrait)**, about 180 MB. The first Run asks for it if it is
not there yet. It runs on the NPU of every Snapdragon the app supports. Once it is installed,
**Use** on its card opens Edit a face or [Animate a portrait](#animate-a-portrait).

## Steps

1. **Flows → Edit a face.**
2. Tap the **image** node and choose a picture with a face in it.
3. Tap the **face** node, then **Edit face**. The picture opens with three rows under it, each a
   menu and a slider. The menu picks what the slider moves:
   - **Eyes**: eyebrows, gaze left / right, gaze up / down, eyes open.
   - **Mouth**: smile, lips open, grimace, pout, purse lips, mouth left / right and up / down.
   - **Head**: turn, nod and tilt.

   Every setting is kept when you pick another in the menu, so you can set a smile, switch to
   lips open and set that too. Each slider runs from -1 to 1, and 0 leaves that part alone. The
   picture stays in view and updates as you move them; **hold the picture** to see the original. Pinch or double-tap it to zoom in on the face.
   The node page shows the same three rows. **Biggest face only** edits one face when there
   are several. **Reset** puts every slider back to 0.
4. **Done**, then **Run**.

## Animate a portrait

The same model makes a photo of a face move the way a face in a video moves.

**Nodes:** Image (the photo) and **Video** (the moving face) → Animate portrait → Output.

1. **Flows → Animate a portrait.**
2. Tap the **image** node and choose a photo with a face in it.
3. Tap the **clip** node and **Choose a video** with a face in it: someone talking, turning their
   head, smiling. On the canvas the animate node shows your photo and the video looping beside it.
4. On the **animate** node, **Seconds** sets how much of the video to follow (1 to 30, 10 to start).
5. **Run.** About 0.2 s a frame on an 8 Elite: a 10 second clip in about a minute and a half.

The photo keeps its own pose and follows the video's **changes** from its first frame, so the two
faces need not look alike or face the same way. A frame of the video with no face in it leaves the
photo as it was. The clip is 25 frames a second, at the photo's size.

## From Results

Any picture you kept has **Edit face** in its row and in the full-screen viewer. It opens the same
window, and **Done** keeps the edited picture as a new result beside the original.

## Good to know

- A face that is small, turned far away or partly hidden may not be found; the window then says
  so.
- Large edits (a slider near -1 or 1, or a big head turn) change more than the part you moved:
  hair and the background near the face move with it.
- The face is redrawn at 512 × 512, so a face that fills a large picture comes back a little
  softer than the rest.
