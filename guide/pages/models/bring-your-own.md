# Bring your own model

Beyond the catalogue, you can use models you converted or downloaded yourself. How depends on
the family.

## SD 1.5, SDXL and Anima: a converted zip

These families run as models **compiled for the NPU**, so a normal `.safetensors` from CivitAI
cannot be used directly — it has to be converted first. Two ways:

- **On the phone, with [npuforge](https://github.com/AbrahamPaulJ/npuforge)**: it turns an SD 1.5
  `.safetensors` checkpoint into an NPU model with no PC involved. Its 9-channel inpainting
  exports run as real inpainting models, and its **SD1.5 Swap** exports appear under the
  **SD 1.5 Swap** family and take LoRAs and ControlNet per render
  ([nodes](../reference/nodes.md#image-generator)). npuforge 1.0.11's **SDXL Swap** exports with
  LoRA ticked appear under the **SDXL Swap** family and take SDXL LoRAs per render
  ([LoRAs](loras.md#on-sdxl-swap)). NPuForge 1.0.8 and later also marks
  v-prediction packages, which Nightmare enables automatically.
- **A zip made for LocalDream**: converted models shared for LocalDream import unchanged.

To import: open the family's page in **Models** (**Family ▾**), tap **Import**, choose the `.zip` and give it a
name. The app reads which family it is from the files inside.

If a package contains LocalDream's empty `V_PRED` marker, Nightmare launches the
native backend in v-prediction mode. Packages without it keep the existing epsilon
behavior.

## FLUX.2, Z-Image and Krea 2: one .safetensors or .gguf

These build their model at load time from ordinary weights, so **a fine-tune from CivitAI or
Hugging Face imports as-is** — no conversion, no PC. Both `.safetensors` and `.gguf` work.

1. In Models, open **Family ▾ → FLUX.2**, **Z-Image** or **Krea 2**.
2. Tap **Import** and pick the file.
3. Name it.

The app reads the file's tensor names: a Klein 9B fine-tune is recognised as 9B, and a file
picked on the wrong family's page is refused with the family it belongs to.

- **Klein 4B and Z-Image** share the text encoder, VAE and tokenizer with the family's model, so
  an import costs only its own weights (plus up to 2.6 GB once, if you have neither family).
- **Klein 9B and Krea 2** get their own copy of their text encoder (4.8 GB and 2.6 GB), copied
  from the built-in model if you have it, downloaded if not.

!!! warning "Size and memory"
    A checkpoint bigger than about half your phone's RAM is marked *likely too big for this
    phone's RAM* on its row: Android will probably close it while it renders. A smaller quant
    (Q4_0, Q8_0 or fp8) of the same model is the fix. Q4_0, Q8_0, MXFP4 and fp8 are the formats
    known to run on the NPU; others (Q4_K, Q6_K…) are untested.

!!! warning "Z-Image .gguf"
    A Z-Image `.gguf` imports, but in our test (Q4_0) the picture came out as noise. Use a
    Z-Image fine-tune as an fp8 `.safetensors` for now. FLUX.2 `.gguf` files render correctly.

!!! note "Qwen Image"
    Qwen Image fine-tunes cannot be imported from the phone yet. A Qwen folder made by
    LocalDream (see below) is recognised.

## A folder copied onto the phone

If your models are in `Download/Nightmare` ([Settings → Models folder](../reference/settings.md#models-folder)),
you can copy a model's folder straight into `Download/Nightmare/models/` with a file manager.
The app finds it the next time you open Models:

- A folder with LocalDream's marker file — `SDXL`, `ANIMA`, `KLEIN`, `ZIMAGE`,
  `QWEN_IMAGE_2_1`, `npucustom` or `finished` — is taken at its word.
- A FLUX.2 or Z-Image folder with no marker is recognised from its weights.

A model's folder may also contain a `config.json` with its preferred prompt, steps, CFG and
scheduler, as LocalDream uses; the app reads it.
