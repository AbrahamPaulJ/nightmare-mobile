# Nightmare Mobile

**A node graph for image and video generation on Android, running on the Qualcomm Hexagon NPU.**
Text to image, image to image, inpainting, upscaling, image editing and text to video — all on
the phone. No server, no account, no cloud.

<p align="center">
  <img src="media/workflows.png" alt="Text to image, image to image and image to video, each as a node graph on an Android phone">
</p>

**[Download the APK](https://github.com/AbrahamPaulJ/nightmare-mobile/releases/latest)** ·
**[User guide](https://abrahampaulj.github.io/nightmare-mobile/)** ·
**[Report a problem](https://github.com/AbrahamPaulJ/nightmare-mobile/issues)**

## What your phone needs

Android 12 or newer, arm64, a Snapdragon with a Hexagon NPU. Models are not in the APK: pick one
and it downloads on first use; the app shows which ones your phone can run.

| chip | runs |
|---|---|
| Snapdragon 888 and newer | SD 1.5 |
| 8 Gen 3 and newer | SDXL, Anima |
| 8 Elite and newer | FLUX.2 Klein 4B / 9B, Z-Image Turbo, Qwen Image 2.1, SDXL Swap, text and image to video |
| 8 Elite + 16 GB RAM | Krea 2 Turbo |

## Features

### The canvas
- **Built for a phone** — big ports, snap to connect, pinch to zoom; the picture appears on the node that made it.
- **Recipes** — text to image, image to image, inpaint, upscale, image edit, text / image to video and two Advanced flows, listed from least to most demanding; one your phone can't run says so.
- **Several checkpoints in one graph** — each generate node has its own model; the run loads each once and tells you the cost first.
- **Batching** — sweep seed, steps, CFG, denoise or scheduler; two at once makes a grid. Every result keeps the graph that made it.
- **Results** — star, save, share, reopen any picture as the flow that made it.

### Models
- **35 checkpoints in the catalogue** — SD 1.5, SD 1.5 Swap, SDXL, SDXL Swap, Anima and the DiT family, each downloaded on demand.
- **FLUX.2 Klein 4B / 9B, Z-Image Turbo, Qwen Image 2.1, Krea 2 Turbo** — any size from 512 to 2048 in 64 px steps, no reload to change it.
- **Bring your own** — import a converted checkpoint, convert one on the phone with [npuforge](https://github.com/AbrahamPaulJ/npuforge), or drop in a FLUX.2 / Z-Image / Krea 2 `.safetensors` or `.gguf` fine-tune as it is.
- **Upscalers** — RealESRGAN anime and 4x UltraSharp V2 Lite, or your own; a checkbox on the output node.
- **A real inpainting model** — AbsoluteReality Inpaint sees the hole it fills.

### Editing
- **Inpaint and outpaint** — paint a mask, or zoom the frame out past the photo and the padding is generated.
- **Tap to select** — tap an object and the mask follows its outline (Segment Anything 2.1).
- **Auto mask by name** — Clothes, Face, Hair, Shoes or Bag, found in every new photo.
- **Add Objects** — paste an object from another photo; only its edges are repainted.
- **Image editing** — FLUX.2 Klein or Qwen Image re-render a photo to follow a prompt, with an optional second reference picture.
- **Text and image to video** — 49 frames at 1024×640 in about 25 s on an 8 Elite (Neodragon).

### LoRA, ControlNet and IP-Adapter
- **LoRAs per render** — on FLUX.2, Z-Image and Swap models: tick, set strength, no reload. A note per LoRA keeps its trigger words.
- **Get LoRAs in the app** — search CivitAI and Hugging Face filtered to the model's family, or paste a link; hash-checked downloads.
- **SD 1.5 Swap and SDXL Swap** — npuforge conversions whose LoRA, **ControlNet** (canny, depth, openpose) and **IP-Adapter** (Plus, Face) are chosen per render; pose and depth are found from a photo on the phone.
- **Embeddings** — import a textual inversion and name it in a prompt.

### Prompts
- **A prompt from a picture** — describe any picture on the canvas or in your gallery: **Short** or **Detailed** sentences (Florence-2 PromptGen), or **Tags** in Danbooru style for anime models (WD tagger).
- **A tag toolbar** — while you type: make the tag under the cursor heavier or lighter, delete it, add one after it, undo, redo.
- **Translate** — a Russian or Chinese prompt to English, offline (Firefox Translations), keeping weights like `(long hair:1.2)`.

### Your phone, your files
- **English, Chinese and Russian** — the app follows your phone's language.
- **Models where you can see them** — optionally in `Download/Nightmare`, surviving an uninstall.
- **Download from a mirror** — hf-mirror.com or any address, for every model the app fetches.
- **Your RAM back** — the model is released when you leave; low-RAM switches for SDXL and Anima.
- **Offline and private** — nothing is uploaded, no account; a failure report never includes your prompt.

The [user guide](https://abrahampaulj.github.io/nightmare-mobile/) explains each of these, with measurements.

## Under the hood

A generate node is one node to you and several ops underneath: `encode_text`, `vae_encode`,
`sample`, `latent_blend` and `vae_decode` are separate endpoints on the inference server, and
conditionings and latents move between them as handles that never cross the wire. The node set is
deliberately small — an NPU has no runtime compiler, so the inside of a compiled pipeline cannot be
recombined on the phone anyway.

**Writing a node (experimental):** a JSON manifest plus a small JavaScript file, zipped into the
app's plugin directory — no toolchain, no app release. Four worked examples are in
[`examples/`](examples/). The format can still change, and an imported script is code you did not
write.

## Building it

JDK 17 and Android SDK 35: `gradlew assembleDebug`. The NPU backend is a native binary and the QNN
runtime libraries are not in this repository; without them the app builds and the canvas works, but
nothing renders. Prompt translation needs Foxlet built once: `tools/build_foxlet.sh` (Git Bash)
clones and builds it and copies the AAR into `app/libs/`.

## Credits

**The NPU work is not ours.** The C++ inference server is a fork of
[xororz/local-dream](https://github.com/xororz/local-dream) — the QNN pipelines, model conversions,
per-chip build tiers and device gating come from there, as do the checkpoint and upscaler archives
the app downloads. Anyone interested in how Stable Diffusion runs on a Hexagon NPU should start
there. Every component, with its licence and terms, is listed in [NOTICE](NOTICE).

### Engines and runtimes

| project | by | licence | used for |
|---|---|---|---|
| [local-dream](https://github.com/xororz/local-dream) | xororz | CC BY-NC 4.0 | the inference server this app forks |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) + [Hexagon work](https://github.com/happyyzy/stable-diffusion.cpp) | leejet, happyyzy | MIT | the DiT engine (FLUX.2, Z-Image, Qwen Image, Krea 2) |
| [ggml](https://github.com/ggml-org/ggml) | ggml authors | MIT | under stable-diffusion.cpp |
| Qualcomm AI Runtime (QNN) | Qualcomm | Qualcomm's terms | the NPU; not in this repository |
| [MNN](https://github.com/alibaba/MNN) | Alibaba | Apache 2.0 | CPU and GPU model paths |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Microsoft | MIT | the picture helpers below |
| [QuickJS](https://bellard.org/quickjs/) | Fabrice Bellard, Charlie Gordon | MIT | plugin nodes |
| [Foxlet](https://github.com/yinvoke/foxlet-translate) (Bergamot, Marian) | yinvoke; Mozilla; Marian authors | MIT; MPL-2.0; MIT | prompt translation |
| [Inter](https://github.com/rsms/inter) | Rasmus Andersson | SIL OFL 1.1 | the typeface |

### Image and video models

| model | by | licence | notes |
|---|---|---|---|
| [FLUX.2 klein 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8) | Black Forest Labs | Apache 2.0 | text encoder and VAEs converted by [zhiyuanasad](https://huggingface.co/zhiyuanasad/flux2_klein_adreno) |
| [FLUX.2 klein 9B](https://huggingface.co/black-forest-labs/FLUX.2-klein-9B) | Black Forest Labs | FLUX Non-Commercial | [leejet's GGUF](https://huggingface.co/leejet/FLUX.2-klein-9B-GGUF); [Qwen3-8B](https://huggingface.co/Qwen/Qwen3-8B) encoder (Apache 2.0, [bartowski's GGUF](https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF)) |
| [Z-Image Turbo](https://huggingface.co/Tongyi-MAI/Z-Image-Turbo) | Tongyi-MAI | Apache 2.0 | fp8 weights by [Kijai](https://huggingface.co/Kijai/Z-Image_comfy_fp8_scaled) |
| [Qwen Image 2.1](https://huggingface.co/Qwen/Qwen-Image-2.1) | Qwen team | Qwen Research License | [leejet's GGUF](https://huggingface.co/leejet/Qwen-Image-2.1-GGUF) / [unsloth's FP8](https://huggingface.co/unsloth/Qwen-Image-2.1-FP8); VAE by [Comfy-Org](https://huggingface.co/Comfy-Org/Qwen-Image-2.1); [Qwen3-VL-8B](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct) encoder (Apache 2.0, [bartowski](https://huggingface.co/bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF)) |
| [Krea 2 Turbo](https://huggingface.co/krea/Krea-2-Turbo) | Krea | [Krea 2 Community License](https://krea.ai/krea-2-licensing) | [gguf-org MXFP4 GGUF](https://huggingface.co/gguf-org/krea-2-gguf); [Qwen3-VL-4B](https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct) encoder (Apache 2.0, [bartowski](https://huggingface.co/bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF)); [Wan 2.1 VAE](https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged) (Apache 2.0) |
| [Neodragon](https://huggingface.co/Qualcomm-AI-Research/Neodragon) | Qualcomm AI Research | BSD-3-Clause-Clear + model card terms | text and image to video, converted for the NPU |
| [SSD-1B](https://huggingface.co/segmind/SSD-1B) | Segmind | Apache 2.0 | the video's first frame |
| SD 1.5, SDXL, Anima catalogue and upscalers | their authors, converted by xororz | each model's licence | via local-dream |
| AbsoluteReality v1.8.1 / Inpaint, CuteYukiMix, Anything V5 | Lykon, kemiaomiao, Yuno779 | CreativeML OpenRAIL-M | SD 1.5 Swap defaults, [our conversions](https://huggingface.co/AbrahamPJ/nightmare-sd15-swap-models) |
| Illustrious XL v1.0, Juggernaut XL Ragnarok | OnomaAI Research, RunDiffusion | OpenRAIL++-M; OpenRAIL-M + RunDiffusion's terms | SDXL Swap defaults, [our conversions](https://huggingface.co/AbrahamPJ/nightmare-sdxl-swap-models) |

### Control, reference and picture helpers

| model | by | licence | used for |
|---|---|---|---|
| [ControlNet 1.1](https://huggingface.co/lllyasviel/ControlNet-v1-1) (canny, depth, openpose) | lllyasviel | CreativeML OpenRAIL-M | SD 1.5 Swap, [our QNN builds](https://huggingface.co/AbrahamPJ/nightmare-sd15-controlnet-qnn) |
| SDXL ControlNets: [canny](https://huggingface.co/diffusers/controlnet-canny-sdxl-1.0), [depth](https://huggingface.co/diffusers/controlnet-depth-sdxl-1.0), [openpose](https://huggingface.co/thibaud/controlnet-openpose-sdxl-1.0) | diffusers team; Thibaud Zamora | OpenRAIL++-M; openpose: CMU non-commercial | SDXL Swap, [our QNN builds](https://huggingface.co/AbrahamPJ/nightmare-sdxl-controlnet-qnn) |
| [IP-Adapter Plus / Plus Face](https://huggingface.co/h94/IP-Adapter) + OpenCLIP ViT-H/14 | Tencent AI Lab; OpenCLIP (LAION-2B) | Apache 2.0; MIT | reference pictures, [as ONNX](https://huggingface.co/AbrahamPJ/nightmare-ip-adapter) |
| [MoveNet MultiPose Lightning](https://huggingface.co/Xenova/movenet-multipose-lightning) | Google | Apache 2.0 | pose from a photo (with local-dream's OpenPose renderer) |
| [Depth Anything V2 Small](https://huggingface.co/onnx-community/depth-anything-v2-small) | Depth Anything team | Apache 2.0 | depth from a photo |
| [SAM 2.1](https://github.com/facebookresearch/sam2) hiera-tiny | Meta | Apache 2.0 | tap to select |
| [SegFormer-B2 on ATR](https://huggingface.co/mattmdjaga/segformer_b2_clothes) | mattmdjaga | MIT | auto mask by name |
| [Florence-2 PromptGen v2.0](https://huggingface.co/MiaoshouAI/Florence-2-base-PromptGen-v2.0) | MiaoshouAI, on Microsoft's [Florence-2](https://huggingface.co/microsoft/Florence-2-base-ft) | MIT | describe: sentences, [as ONNX](https://huggingface.co/AbrahamPJ/florence2-promptgen-onnx) on onnx-community's graphs |
| [WD ViT tagger v3](https://huggingface.co/SmilingWolf/wd-vit-tagger-v3) | SmilingWolf | Apache 2.0 | describe: tags |
| [Firefox Translations](https://github.com/mozilla/firefox-translations-models) (ru, zh → en) | Mozilla | MPL-2.0 | prompt translation |

## Licence

**CC BY-NC 4.0**, inherited from [xororz/local-dream](https://github.com/xororz/local-dream) rather
than chosen; no further restrictions are added. Share it, fork it, build on it — don't sell it or
ship it inside something you sell. Full text in [LICENSE](LICENSE); third-party components in
[NOTICE](NOTICE). The Qualcomm AI Runtime is under Qualcomm's own terms and is not in this repository.

## Something failed?

Tap the **share** icon on the red error message: it shows a full report — the error, the backend's
log, your phone and model files, never your prompt — to copy or share as a `.txt`. Attach it to an
[issue](https://github.com/AbrahamPaulJ/nightmare-mobile/issues). More in the
[troubleshooting guide](https://abrahampaulj.github.io/nightmare-mobile/troubleshooting/).

## Support

[buymeacoffee.com/abrahampaulj](https://buymeacoffee.com/abrahampaulj) — tips are welcome and change
nothing: the app stays free, offline and CC BY-NC, and nothing is gated behind them.

<sub>**Keywords:** on device AI, offline Stable Diffusion, ComfyUI for Android, mobile Stable
Diffusion, local image generation, on device video generation, node editor, Qualcomm Hexagon NPU,
Snapdragon, QNN, FLUX.2, Z-Image, Qwen Image, Krea 2, SDXL, Anima, Illustrious, ControlNet,
IP-Adapter, LoRA, img2img, inpainting, outpainting, Segment Anything, npuforge, image to prompt,
Danbooru tags, no cloud, private.</sub>
