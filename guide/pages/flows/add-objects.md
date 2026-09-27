# Add Objects

*Paste a real object from another photo into the one you are repainting — only its edges are
repainted, so it blends in while its middle stays exactly as photographed.*

Use it for virtual try-on (a jacket from a shop photo onto a person), putting a product into a
scene, or adding a real pet to a picture. Unlike ordinary inpainting, the object is **not**
redrawn by the model, so logos, text and faces on it stay true.

It works inside the [Inpaint](inpaint.md) flow.

## Steps

1. Open **Flows → Inpaint** and choose the photo you want to add to on the **image** node.
2. Tap the **Inpaint** node and tick **Enable Add Objects**.
3. Open the **Mask** window. An **Add** button appears: tap it and choose the photo that
   contains the object.
4. **Choose the object**: paint over it in that photo (tap to select and auto mask work here
   too), then confirm.
5. **Place it**: the object appears on your photo as a new layer.
    - drag to move;
    - two fingers to resize and turn;
    - the buttons turn it 90°, flip it, remove it, or **Reset** it to how it was placed.
6. Write a prompt describing the whole picture with the object in it, and **Run**.

Only a band around the object's edge is repainted, so it takes on the light and colour of the
scene while its middle stays pixel-exact.

## Layers

Each Add is its own **layer** above the photo (**Layer 1 · image** is the photo itself), up to
two object layers. Tap an object to select it; the layer bin deletes a whole layer (it asks
first). Your ordinary mask stays on the photo's layer, so you can still repaint part of the
photo in the same Run.

## Tips

- Objects blend best at **denoise below 1.0** — about **0.7** is a good start.
- Choose the object generously but cleanly: a tight mask leaves less edge for the model to
  blend; a sloppy one carries the old background along.
- A cut takes the object's whole bounding box from the chosen mask, so if you masked two
  separate things in the source photo, both come across together.
