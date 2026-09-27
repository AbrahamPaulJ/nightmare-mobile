# Troubleshooting

Find your symptom below. If it is not here, or the fix does not work, please
[open an issue](https://github.com/AbrahamPaulJ/nightmare-mobile/issues) with the
**run log** attached — the copy button on the log above Run copies it — and your phone model.

## A flow is dimmed and will not open

It needs a newer chip than your phone has; the card says which (for example *"this flow needs
v79 (Snapdragon 8 Elite or newer)"*). Image edit and both video flows need an 8 Elite.
Nothing can change this — the models are built for that generation of NPU.

## A model says "this device cannot run it"

Same reason: the model family needs a newer chip. See the table on the
[home page](index.md#what-your-phone-needs).

## A download is slow, stuck or fails

- Check you are on Wi-Fi and have enough free storage (the row shows the size).
- If Hugging Face is slow or blocked where you are, switch
  [Settings → Downloads → Download source](reference/settings.md#download-source) to
  **hf-mirror.com**.
- An interrupted download resumes. A model left **incomplete** shows **Repair**, which
  fetches only the missing file.

## Run does nothing, or says there is no model

- Check the generator node has a **Checkpoint** chosen, and that the model is installed
  (**Models**). A flow opened with a model you have since deleted needs a new one picked.
- The first Run after choosing a model loads it; watch the run log above Run.
- The first Run of FLUX.2, Z-Image or Qwen Image also downloads the 23 MB engine they share.

## The app closes (or restarts) during a render

Android closed it to free memory. In order:

1. Close other apps and try again.
2. Try a smaller size.
3. For SDXL and Anima, make sure **low RAM mode** is on in
   [Settings → General → Memory](reference/settings.md#memory).
4. For a **FLUX.2 edit with a reference picture**, use 512 × 512.
5. On Samsung phones, allow the app to run in the background
   ([Settings → General](reference/settings.md#keep-rendering-in-the-background)).

More in [Memory and speed](reference/memory.md).

## A render stops when I switch apps

Allow background running:
[Settings → General → Keep rendering in the background](reference/settings.md#keep-rendering-in-the-background).
A render you leave running finishes; a model you are not using is let go a minute after you
leave, which is normal ([why](reference/memory.md#when-you-leave-the-app)).

## The first Run after coming back is slower

That is the model loading again after it was let go to give your phone its memory back. It
takes a few seconds, once.

## A node is red

The node failed; the reason is written underneath it. Everything after it is blocked. Common
ones:

- no photo chosen on an Image node that a generator needs;
- an Inpaint node with nothing painted in its mask;
- the backend stopping mid-render — usually memory, see above.

## Image to image or an edit changes too much, or too little

Change **Denoise**: lower keeps more of the photo, higher changes more. Image to image opens at
0.65; an edit at 1.0. For inpainting, also check the mask covers what you want changed — and a
little beyond it.

## The picture ignores part of my prompt

- SD 1.5 and SDXL read a limited number of tokens (about 75 for most models); the counter
  under the prompt box shows how much you have used. Put the important words first.
- FLUX.2, Z-Image and Qwen Image read full sentences, and **ignore the negative prompt** at
  CFG 1. Say what you want instead of what you do not.
- A Russian or Chinese prompt should be translated first (the **文A** button) for SD 1.5 and
  SDXL.

## Video flows say the models are missing

Install them under **Models → Video** (8.6 GB). They are separate from picture models.

## Reporting a problem

Include: your phone model, the app version (shown next to the logo), what you did, and the
**run log** (copy button on the log). A screenshot of the canvas helps too.
