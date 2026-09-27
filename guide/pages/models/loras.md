# LoRAs and embeddings

Two kinds of small add-on file change what a model draws without replacing it.

## LoRAs

A **LoRA** is a small adapter (usually tens to hundreds of MB) that teaches a model a style, a
character or a concept. In Nightmare, LoRAs work on **FLUX.2** and **Z-Image**.

### Import

**Settings → Add-ons → LoRAs → Import**, and pick the `.safetensors` file. It is listed with
its size; the bin deletes it.

### Use

1. Tap a FLUX.2 or Z-Image generator node.
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

## Embeddings

An **embedding** (textual inversion) is a tiny file holding one trained concept — often a
style, or a "negative" such as a quality fix.

**Settings → Add-ons → Embeddings → Import**, pick the `.safetensors`, and then **name it in
a prompt** (or the negative prompt) to use it.
