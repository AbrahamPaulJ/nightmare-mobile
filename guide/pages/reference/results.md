# Results

**Results** (top of the screen) holds everything you have made, newest first. Each picture is
kept **together with the flow that made it** — prompt, model, seed, every setting — so you can
always get back to exactly how it was made.

<figure markdown>
  ![Results](../img/history.png){ .screen }
</figure>

## Browsing

- **All** / **Favourites** switch between everything and the pictures you starred. The filter
  icon narrows the list further.
- A [batch](../flows/batch.md) is kept as one group: its pictures are shown together under one
  card.
- Tap a picture to show it large; tap the large one to see it full screen (pinch or double-tap
  to zoom).

## The picture's buttons

| button | does |
|---|---|
| 🗑 **Delete** | Removes it (asks first) |
| ★ **Favourite** | Stars it; starred pictures appear under **Favourites** |
| ⬇ **Save to gallery** | Writes a copy to your phone's gallery |
| **Share** | Shares the picture with another app |
| **Send to a flow** | Offers the flows that take a photo — Image to image, Inpaint, Image edit, Upscale, Image to video — and opens the one you pick with this picture in it |
| **Upscale** | Enlarges it with an upscaler; the bigger copy is kept as a new item. The same window offers **UltraFix** — see below |
| ⓘ **What made this** | The prompt, model, seed and settings |
| **Open flow** / share the flow | Reopens the flow that made it on the canvas, or shares that flow as a file |

A clip plays on its card; tap it to play it full screen.

## Selecting several

Long-press a picture to start selecting, then tap more. Save, share and delete then act on all
of them. The ✕ stops selecting.

<figure markdown>
  ![Selecting several results](../img/history-selecting-360.png){ .screen }
</figure>

## UltraFix

Upscaling makes a picture bigger, not more detailed. **UltraFix** (from Local Dream) redraws the fine
detail of an upscaled picture tile by tile while keeping its composition, and keeps the result as a
new item. Open it from the **Upscale** window on a picture whose shorter side is at least 512 px
(1024 px for an SDXL model) — so upscale a render first. To do both on every Run, turn on
**Hires fix** on the Image node ([nodes](nodes.md)).

| setting | default | what it does |
|---|---|---|
| **Steps** | 10 | The schedule UltraFix runs on |
| **Denoise steps** | 4 | How many of those steps actually redraw — more changes more |
| **Denoise with "masterpiece, best quality, 4k resolution"** | on | Off uses the picture's own prompt |

It uses the picture's own model when that is an SD 1.5 or SDXL model on your phone, otherwise the
one selected. Every tile runs every step, so larger pictures take longer — a 1024² picture took
about 11 s on a Snapdragon 8 Elite.

