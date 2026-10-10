# Image to image

*A photo from the gallery, re-imagined at the strength you choose.*

**Nodes:** Prompt and Image → Image to image → Output.

Use it to restyle a photo (a painting of it, an anime version), or to take a rough picture
and let the model redraw it with more detail.

!!! note "FLUX.2 and Qwen Image use Image edit instead"
    These two are edit models: give them a photo and they re-render it to follow an
    instruction. That is its own flow — see [Image edit](image-edit.md).

## Steps

1. **Flows → Image to image.**
2. Tap the **image** node and choose a photo. It appears on the node straight away.
3. Tap the **prompt** node and describe the result you want — describe the *whole* picture, not
   just the change.
4. Tap the **Image to image** node and set **Denoise** (below).
5. **Run.**

## Draw on it first

Under the picture on the **image** node, **Draw** opens a drawing window over the photo: a brush
with colour swatches and a hue slider, **Size**, **Opacity**, **Softness**, an **Eraser**, undo
and redo. Two fingers zoom and pan for detail work; one finger always paints. **Pick colour**
takes the next colour you touch from the picture, and the rainbow **+** swatch opens a picker for
any colour. Paint a rough shape or a blob of colour where you want something, tap **Done**, and run —
image to image follows your colours and shapes (a denoise around 0.6–0.75 turns a doodle into
something real). The photo itself is kept: the drawing is a layer on it, so you can reopen it,
erase it, or **Clear drawing**.

No photo? **Blank page** starts you on a white page instead.

## Denoise: how much changes

**Denoise** runs from 0 to 1:

| denoise | result |
|---|---|
| 0.2 – 0.4 | Close to the photo: colours and details shift, the composition stays |
| 0.5 – 0.7 | A real reinterpretation that keeps the layout (0.65 is the default) |
| 0.8 – 1.0 | Mostly new; the photo only guides the overall shapes |

## Framing the photo

The generator draws at a fixed size and shape, so the photo is **framed** to that shape first.
Tap the picture on the generator node (or **Crop** in its sheet) to open the framing view:
drag the photo to move it and pinch to zoom; the frame is exactly what will be sent.

You can also zoom *out* past the photo's edges (up to twice its area), which makes the subject
smaller in the frame. The part outside the photo shows as a **checkerboard**: the model sees it
filled with the photo's own edges, mirrored and blurred, but it is **cut away from the result**,
so only the photo's own area comes back. The photo is never stretched.

<figure markdown>
  ![The framing view for image to image](../img/i2i-popup-crop.png){ .screen }
  <figcaption>Zoomed out a little: the checkerboard strip is not part of the result.</figcaption>
</figure>
