# Bring your own model

Beyond the catalogue, you can use models you converted or downloaded yourself.

**To import anything:** tap **+** next to the **Models** title, optionally type a name, tap
**Choose a file** and pick it. The app reads the file and works out what it is: a converted
`.zip`, a checkpoint `.safetensors` or `.gguf` (and which model it is for), or an upscaler `.bin`.
It asks only when a checkpoint does not say which model it was made for. A LoRA or an embedding
picked by mistake is named, with where to add it instead.

## SD 1.5, SDXL and Anima: a converted zip

These families run as models **compiled for the NPU**, so a normal `.safetensors` from CivitAI
cannot be used directly. It has to be converted first. Two ways:

- **On the phone, with [npuforge](https://github.com/AbrahamPaulJ/npuforge)**: it turns an SD 1.5
  `.safetensors` checkpoint into an NPU model with no PC involved. Its 9-channel inpainting
  exports run as real inpainting models, and its **SD1.5 Swap** exports appear under the
  **SD 1.5 Swap** family and take LoRAs and ControlNet per render
  ([nodes](../reference/nodes.md#image-generator)). npuforge's **SDXL Swap** exports appear under the
  **SDXL Swap** family and take SDXL LoRAs per render ([LoRAs](loras.md#on-sdxl-swap)); from npuforge
  1.0.12 they can also keep **ControlNet** and **IP-Adapter**. A conversion has only the features
  ticked when it was made: the others are dimmed on its node, and tapping one says so. NPuForge 1.0.8 and later also marks
  v-prediction packages, which Nightmare enables automatically.
- **A zip made for LocalDream**: converted models shared for LocalDream import unchanged.

Import the `.zip` with **+** (above). The app reads which family it is from the files inside.

If a package contains LocalDream's empty `V_PRED` marker, Nightmare launches the
native backend in v-prediction mode. Packages without it keep the existing epsilon
behavior.

## FLUX.2, Z-Image, Krea 2, Anima and MOSS-Image: one .safetensors or .gguf

These build their model at load time from ordinary weights, so **a fine-tune from CivitAI or
Hugging Face imports as-is**. No conversion, no PC. Both `.safetensors` and `.gguf` work.

Import the file with **+** (above). The app reads the file's tensor names to tell Anima, FLUX.2
Klein (4B or 9B), Z-Image, Krea 2 and MOSS-Image apart, so an Anima file from CivitAI lands on the **Anima**
page without you choosing anything.

On **Family ▾ → Anima**, both kinds of Anima are listed: first the ones on the DiT engine (any
Anima file from CivitAI), then the ones converted for the NPU (zip).

- **Klein 4B and Z-Image** share the text encoder, VAE and tokenizer with the family's model, so
  an import costs only its own weights (plus up to 2.6 GB once, if you have neither family).
- **Klein 9B and Krea 2** get their own copy of their text encoder (4.8 GB and 2.6 GB), copied
  from the built-in model if you have it, downloaded if not.
- **Anima** takes its text encoder and VAE (0.9 GB) from Anima Turbo 1.1 the same way. Anima 2B and 2.9B (40 blocks) both import; a 2.9B starts at 28 steps and CFG 4, as a base model wants; another size is refused. An
  imported `.safetensors` runs as it is: about 20% slower than the built-in, which the app
  converts to Q8_0. A Q8_0 `.gguf` runs at the built-in's speed.
- **MOSS-Image** uses the text encoder, VAE and tokenizer of the MOSS-Image you have installed, so
  an import costs only its own weights (without one, it downloads its own 5.6 GB). An import gets
  the built-in's **8-step speed-up** too (the same LoRA, read from the installed MOSS-Image, 8 steps
  and CFG 1), shown as an **8 steps** badge on its card. Switch it off with **⋮ → 8-step speed-up**
  for a fine-tune that wants its own steps: it then starts at 25 steps and CFG 3. A "Turbo" or
  "8 step" file has the speed-up built in, so it never takes it twice (the app reads that from the
  file's name) and starts at 8 steps and CFG 1. A 16-bit file that stops importing part way says
  how much of it arrived: it is usually still downloading. A Q4_0 `.gguf` such as molbal's *MOSS Image Turbo GGUF* is the
  fastest MOSS-Image: about 40 s at 512x512 and 2.5 min at 1024x1024 on an 8 Elite (it is also in the
  catalogue as **MOSS-Image Turbo**).

!!! example "Example: Moody Moss Mix (25 steps)"
    On its CivitAI page pick the **fp8** file (*V1.0 25 STEPS CFG3*, about 4.8 GB), not the int8 one.
    Install MOSS-Image SFT first, then import the file with **+**, and switch **⋮ → 8-step speed-up**
    off: it then starts at 25 steps and CFG 3, which is what it needs. About 3 min at 512x512 and 16 min at
    1024x1024 on an 8 Elite.

!!! warning "Size and memory"
    A checkpoint bigger than about half your phone's RAM is marked *likely too big for this
    phone's RAM* on its row: Android will probably close it while it renders. A smaller quant
    (Q4_0, Q8_0 or fp8) of the same model is the fix. Q4_0, Q8_0, MXFP4 and fp8 are the formats
    known to run on the NPU; others (Q4_K, Q6_K…) are untested.

!!! warning "int8 and 4-bit files"
    Files made for NVIDIA cards in ComfyUI's int8 or 4-bit formats (int8, ConvRot, int4, nf4,
    nvfp4) are refused on import with a message: the engine cannot run them yet. Pick the `.gguf`
    (Q4_0 or Q8_0) or fp8 version of the same model.

!!! note "Qwen Image"
    Qwen Image fine-tunes cannot be imported from the phone yet. A Qwen folder made by
    LocalDream (see below) is recognised.

## A folder copied onto the phone

If your models are in `Download/Nightmare` ([Settings → Models folder](../reference/settings.md#models-folder)),
you can copy a model's folder straight into `Download/Nightmare/models/` with a file manager.
The app finds it the next time you open Models:

- A folder with LocalDream's marker file (`SDXL`, `ANIMA`, `KLEIN`, `ZIMAGE`,
  `QWEN_IMAGE_2_1`, `npucustom` or `finished`) is taken at its word.
- A FLUX.2 or Z-Image folder with no marker is recognised from its weights.

A model's folder may also contain a `config.json` with its preferred prompt, steps, CFG and
scheduler, as LocalDream uses; the app reads it.
