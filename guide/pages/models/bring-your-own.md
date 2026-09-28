# Bring your own model

Beyond the catalogue, you can use models you converted or downloaded yourself. How depends on
the family.

## SD 1.5, SDXL and Anima: a converted zip

These families run as models **compiled for the NPU**, so a normal `.safetensors` from CivitAI
cannot be used directly — it has to be converted first. Two ways:

- **On the phone, with [npuforge](https://github.com/AbrahamPaulJ/npuforge)**: it turns an SD 1.5
  `.safetensors` checkpoint into an NPU model with no PC involved. Its 9-channel inpainting
  exports run as real inpainting models.
- **A zip made for LocalDream**: converted models shared for LocalDream import unchanged.

To import: open the family's tab in **Models**, tap **Import**, choose the `.zip` and give it a
name. The app reads which family it is from the files inside.

## FLUX.2 and Z-Image: a plain .safetensors

These two build their model at load time from ordinary weights, so **a fine-tune from CivitAI
imports as-is** — no conversion, no PC.

1. Open the **FLUX.2** or **Z-Image** tab in Models (the tab tells the app which family the file
   is).
2. Tap **Import** and pick the `.safetensors` file.
3. Name it.

The text encoder, VAE and tokenizer are shared with the family's built-in model, so an import
costs only the size of its own weights.

!!! note "FLUX.2 Klein 9B"
    Only **Klein 4B** fine-tunes import. A Klein 9B `.safetensors` is recognised and refused:
    it needs the 9B's own, larger text encoder, which imports do not use yet.

!!! note "Qwen Image and Krea 2"
    Qwen Image and Krea 2 models are `.gguf` files and cannot be imported from the phone yet. A Qwen
    folder made by LocalDream (see below) is recognised.

## A folder copied onto the phone

If your models are in `Download/Nightmare` ([Settings → Models folder](../reference/settings.md#models-folder)),
you can copy a model's folder straight into `Download/Nightmare/models/` with a file manager.
The app finds it the next time you open Models:

- A folder with LocalDream's marker file — `SDXL`, `ANIMA`, `KLEIN`, `ZIMAGE`,
  `QWEN_IMAGE_2_1`, `npucustom` or `finished` — is taken at its word.
- A FLUX.2 or Z-Image folder with no marker is recognised from its weights.

A model's folder may also contain a `config.json` with its preferred prompt, steps, CFG and
scheduler, as LocalDream uses; the app reads it.
