# Edit a face

*The faces in a picture, changed with sliders: a smile, the eyes, where they look, the mouth, and
a turn, nod or tilt of the head. No checkpoint involved.*

**Nodes:** Image → Face edit → Output.

## First: install the Face editor

**Models → Tools → Face editor (LivePortrait)**, about 180 MB. The first Run asks for it if it is
not there yet. It runs on the NPU of every Snapdragon the app supports.

## Steps

1. **Flows → Edit a face.**
2. Tap the **image** node and choose a picture with a face in it.
3. Tap the **face** node, then **Edit face**. The picture opens with the sliders under it:
   - **Eyes**: eyebrows, gaze left / right, gaze up / down, eyes open.
   - **Mouth**: smile, lips open, grimace, pout, purse lips, mouth left / right and up / down.
   - **Head**: turn, nod and tilt.

   Each slider runs from -1 to 1, and 0 leaves that part alone. The picture updates as you move
   them; **hold the picture** to see the original. **Biggest face only** edits one face when there
   are several. **Reset** puts every slider back to 0.
4. **Done**, then **Run**.

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
