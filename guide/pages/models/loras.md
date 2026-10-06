# LoRAs and embeddings

Two kinds of small add-on file change what a model draws without replacing it.

## LoRAs

A **LoRA** is a small adapter (usually tens to hundreds of MB) that teaches a model a style, a
character or a concept. In Nightmare, LoRAs work on **FLUX.2**, **Z-Image**,
**SD 1.5 Swap** (an SD 1.5 checkpoint converted in npuforge as *SD1.5 Swap*) and
**SDXL Swap** (an SDXL checkpoint converted in npuforge 1.0.10 or newer as *SDXL Swap*, with
LoRA ticked).

### Import

**Settings → Add-ons → LoRAs → Import**, and pick the `.safetensors` file. It is listed with
its size; the bin deletes it.

### Use

1. Tap a FLUX.2, Z-Image, SD 1.5 Swap or SDXL Swap generator node.
2. Under **LoRAs**, tick the ones you want — a checkbox per installed file.
3. Set each one's **strength** with its slider (1.0 is the adapter's full effect).
4. Run.

<figure markdown>
  ![The LoRA picker on a generator node](../img/lora-picker.png){ .screen }
</figure>

You can stack as many as you like. A LoRA is applied when the render starts, so switching one
on or off, or moving its slider, **reloads nothing**. It does cost render time, in proportion to
its size: about 6% for a typical FLUX.2 adapter, around 40% for a large Z-Image one.

If the LoRA's description names a trigger word, put that word in your prompt.

### Notes

The **⋮** beside each LoRA opens a note for it: its trigger words, the strength and settings it
works best with, anything you would otherwise have to remember. Put the **trigger words on the
first line**. In the note, **Copy** copies the whole note and **Add to prompt** adds the first
line to the prompt wired into that generator. A dot beside the ⋮ marks a LoRA that has a note.
Notes are kept next to the LoRA files, so they stay when the app is updated.

### On SD 1.5 Swap

An SD 1.5 Swap model has room for ONE rank-64 adapter per layer, so the LoRAs you tick are
**merged** into it on the phone the first time that mix is used — about 10 s for one large
LoRA, 25 s for two — and kept for next time. With a single LoRA the strength slider is free;
changing the strengths of several makes a new merge. A LoRA trained above rank 64 is reduced to
its best rank-64 version. Only the part of a LoRA that changes the image model applies; the
part trained into the text encoder does not (true of every NPU conversion).

### On SDXL Swap

The same, with **SDXL** LoRAs: an SDXL Swap model has 700 rank-64 slots, filled on the phone the
first time a mix is used and kept for next time. LoRAs written with either layer naming
(diffusers or SGM) are read. SDXL Swap is new: ControlNet and IP-Adapter are not offered on it
yet, and npuforge's SDXL Swap conversion takes about an hour on the phone.

## Embeddings

An **embedding** (textual inversion) is a tiny file holding one trained concept — often a
style, or a "negative" such as a quality fix.

**Settings → Add-ons → Embeddings → Import**, pick the `.safetensors`, and then **name it in
a prompt** (or the negative prompt) to use it.
