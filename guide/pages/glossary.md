# Glossary

**CFG** — "classifier-free guidance": how strictly the model follows the prompt. Higher is more
literal; too high burns the colours. FLUX.2, Z-Image, Qwen Image and Krea 2 are made for 1.

**Checkpoint** — a model file (or set of files) that draws pictures. Used interchangeably with
*model*.

**Denoise** — with a starting photo, how much of it is replaced. 0 keeps the photo, 1 replaces
it completely.

**DiT** — "diffusion transformer", the newer kind of image model: FLUX.2, Z-Image, Qwen
Image and Krea 2. They read full sentences and draw any size from 512 to 2048.

**Edit model** — a model that takes a photo as a clean reference and changes it to follow an
instruction: FLUX.2 Klein and Qwen Image 2.1.

**Embedding** — a tiny file holding one trained concept, used by writing its name in a prompt.

**Family** — a group of models that work the same way: SD 1.5, SDXL, Anima, FLUX.2, Z-Image,
Qwen Image, Krea 2.

**Flow** — a set of nodes wired together for one job. The app ships ready-made ones; you can
save your own.

**HTP / NPU** — the Snapdragon's AI accelerator (Hexagon Tensor Processor), where the models
run. Its generation is shown as "HTP arch", e.g. v79 on a Snapdragon 8 Elite.

**Inpaint** — repainting only a masked area of a photo.

**LoRA** — a small adapter file that adds a style or character to a model without replacing it.

**Mask** — the painted area that inpainting redoes (red in the mask editor).

**Node** — one step in a flow: a prompt, a photo, a generator, an output.

**Outpaint** — extending a photo past its edges; the new area is generated.

**Seed** — the number that picks the starting noise. The same seed with the same settings makes
the same picture; `0` means a new random one every Run.

**Steps** — how many refinement passes the model makes. More is slower; each model has a
sweet spot.

**Scheduler** — the method used for those passes (Euler, DPM, LCM…). Mostly a matter of what
the model was made for.

**Upscaler** — a small model that enlarges a picture and sharpens it.

**VAE** — the part of a model that turns its internal image into pixels and back.

**VTCM** — fast memory inside the NPU. Some older or smaller chips have less, which limits which
model builds they can run.

**Wire** — a line joining one node's output to another's input.
