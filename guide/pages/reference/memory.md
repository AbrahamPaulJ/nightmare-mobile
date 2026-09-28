# Memory and speed

Image models are large — from about 1 GB to over 10 GB — and a phone has 8 to 16 GB of RAM
for everything. Nightmare manages this for you; this page explains what it does, so a slow
first Run or a model being let go is not a surprise.

## A loaded model stays loaded — while you are using the app

The first Run after choosing a model **loads** it, which takes a few seconds (longer for big
models). After that it stays loaded, so the next Run starts at once. The line under the top
bar shows it: `AbsoluteReality (idle)` means loaded and waiting.

Switching to a different model — or, for SD 1.5, a different resolution — means loading again.
A flow that uses two models loads each once per Run, and the app tells you before you press
Run.

## Models too big to keep

Some models take so much of the phone's memory that a second render in a row would not fit.
For those, the model is **let go after each Run**, and the next Run loads it again (a few
seconds, since the phone keeps the files cached). The run log says so when it happens.

On a 12 GB phone this applies to **Z-Image Turbo** and **Qwen Image 2.1**. On 16 GB phones they
stay loaded like the others.

**Qwen Image 2.1** also loads its parts **one at a time** — the text encoder, then the image
model, then the decoder — giving each back before the next. That is why it fits on a 12 GB
phone at all, and part of why it is slower.

## When you leave the app

**Close the app** — swipe it away from recent apps, or back out of it — and the model is let go
**at once**, even in the middle of a render (a closed app has abandoned it).

**Step away** — Home, switching to another app, the photo picker — and the model stays for
**one minute**, so a quick trip does not cost a reload; after that it is let go, or at once if
the phone reports it is short of memory. A render you step away from is never interrupted: it
finishes first, then the minute starts.

Coming back costs one reload on your next Run.

## Low RAM mode (SDXL and Anima)

SDXL and Anima models can either stay fully loaded (faster, with a live preview while they
render) or load each part on demand and free it after use (**low RAM mode**). Below 16 GB of
RAM, low RAM mode is **required**.

**Settings → General → Memory** has a switch for each, set automatically from your phone's RAM.
You only need to touch them if:

- you have **16 GB** and an SDXL or Anima model is killed during a render → turn low RAM mode
  **on**;
- you have **12 GB** and Anima is still killed with low RAM mode on → also turn on
  **Anima DiT sequential loading** (slower per step).

## If the app is killed during a render

Android closes apps that use too much memory. If a render stops and the app restarts:

1. Close other apps, especially games and camera apps, and try again.
2. Use a smaller size — memory grows with the picture's area.
3. For SDXL or Anima, check low RAM mode is on (above).
4. For a FLUX.2 edit with a reference picture, use 512 × 512.

See also [Troubleshooting](../troubleshooting.md).
