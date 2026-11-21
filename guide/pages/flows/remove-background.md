# Remove background

A photo in, the same photo out with its background made **transparent**, saved as a PNG. Hair, fur
and other soft edges stay soft rather than cut out with scissors.

It runs **RMBG 3.0**, Bria's background remover built on Fibo 2.0 Edit Turbo. It needs an
**8 Gen 3 or newer with 12 GB of RAM**; on an 8 Elite it takes about a minute.

## Use it

1. In **Models**, download **RMBG 3.0 (background remover)**. It is 4.9 GB, and borrows Fibo 2.0 Edit
   Turbo's text encoder and VAE when that is installed (otherwise those come with it).
2. Open **Flows**, then **Remove background** (or **Use** on RMBG 3.0).
3. Pick a photo on the **image** node and press **Run**.

The result is the whole photo at its own size, with the background see-through. Save it from the
output or from **Results**; it stays a PNG, so the transparency is kept.

There is no prompt: RMBG 3.0 was trained on one fixed instruction, and any other text gives a
coloured picture instead of a mask, so the flow always sends that one.

## Only part of the background, or a mask to repaint

In the [Inpaint](inpaint.md#mask-the-subject) mask editor, the **sparkle** button masks the subject
with the same model, so you can repaint the subject or (with **Invert**) the background, or save the
cut-out.
