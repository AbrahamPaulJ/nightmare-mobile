# Nightmare Mobile

**A node graph for image and video generation on Android, running entirely on the Qualcomm
Hexagon NPU.**

Text to image, image to image, inpainting, upscaling and text to video, all on the phone. No
server, no account, no cloud, no network.

<p align="center">
  <img src="media/workflows.png" alt="Text to image, image to image and image to video, each as a node graph on an Android phone">
</p>

**[Download the APK](https://github.com/AbrahamPaulJ/nightmare-mobile/releases/latest)**

Android 12 or newer, arm64, and a Snapdragon with a Hexagon NPU. Models are not in the APK:
pick one in the app and it downloads on first use.

**Keywords:** on device AI, offline Stable Diffusion, ComfyUI for Android, mobile Stable
Diffusion, local image generation, on device video generation, node editor, Qualcomm Hexagon
NPU, Snapdragon, QNN, FLUX.2, Z-Image, SDXL, Anima, text to image, text to video, image to
video, img2img, inpainting, Segment Anything, SAM 2.1, npuforge, safetensors to QNN, on device
model conversion, no cloud, private.

## What it does

**A canvas built for a phone.** Big ports, snap to connect, pinch to zoom, and a node palette
in a sheet. Draw a graph, press Run, and the picture appears on the node that made it.

**Mix checkpoints in one graph.** Each generate node carries its own model, and the executor
orders the run so each checkpoint loads once. Generate with FLUX.2 and then repaint part of the
result with a dedicated inpainting checkpoint, in a single graph, with a single Run. The run
bar tells you what the model switches will cost before you press it.

**FLUX.2 Klein and Z-Image Turbo on the NPU.** Two DiT models, at any size from 512 to 2048,
picked as a shape and a resolution. Changing the size costs no reload. Snapdragon 8 Elite or
newer.

**A real inpainting model.** AbsoluteReality Inpaint is a 9 channel checkpoint: the model sees
the hole it is filling and the pixels around it, instead of repainting blind and being blended
back afterwards. It sits among the SD 1.5 models and does text and image to image too.

**Tap to select.** The Inpaint flow opens with a Segment model node wired in. Tap the thing you
want redone and the mask follows its outline. That is Segment Anything 2.1, an 87 MB download,
running on the phone's CPU.

**Text to video.** A prompt in, 49 frames at 1024x640 out, about two seconds of clip in about
25 seconds on an 8 Elite. Image to video animates a photo instead, using the same three nodes
with a photo wired in. The clip loops on the node that made it and plays full screen. Needs a
Snapdragon 8 Elite or 8 Elite Gen 5 and about 8.6 GB of models, and the app checks your chip by
running a small real model on it rather than by reading its name.

**Recipes to start from.** Text to image, image to image, inpainting, upscaling and both
video flows, each laid out so no wire crosses and the whole graph fits the screen when
it opens.

**Batching.** Arm `seed`, `steps`, `cfg`, `denoise` or `scheduler` on a generate node and Run
sweeps them. Two knobs at once gives you a grid. Every run is kept with the exact graph that
produced it, so a picture you like can be reopened as the flow that made it.

**Upscalers.** RealESRGAN x4plus anime and 4x UltraSharp V2 Lite, loaded per request so they
cost no restart, or bring your own converted `.bin`.

**Twenty seven checkpoints, or bring your own.** Six SD 1.5, ten SDXL, nine Anima and two DiT
in the catalogue. Import a converted checkpoint as a zip, or convert one on the phone with
[npuforge](https://github.com/AbrahamPaulJ/npuforge), which turns an SD safetensors checkpoint
into a QNN model with no PC involved. Every generate node has its own checkpoint picker,
grouped by family and listing what is actually installed; switching family rewrites that node
and keeps every wire. The APK carries every Hexagon architecture tier and picks the build your
chip can load.

**Pick your size.** SD 1.5 renders any resolution its checkpoint ships a patch for, from 512
square up to 1024, portrait and landscape. SDXL and Anima crop their fixed canvas to the shape
you choose. The DiT models render the size you ask for directly.

**Embeddings.** Import a textual inversion `.safetensors` and name it in a prompt.

**English, Chinese and Russian.** The interface follows your phone's language. Adding another
is a file drop: copy `app/src/main/res/values/strings.xml` into a `values-<code>/` folder and
translate it, with no code changes.

**Offline and private.** Nothing is uploaded, there is no account, and no prompt or picture
leaves the phone. The only thing that ever does is a file you explicitly share.

## Under the hood

A generate node is one node to you, and several ops underneath. `encode_text`, `vae_encode`,
`sample`, `latent_blend` and `vae_decode` are separate endpoints on the inference server, and
conditionings and latents move between them as handles that never cross the wire, so a 512
image costs about 40 bytes of JSON instead of 780 KB. A plugin reaches the ops; a person
reaches the nodes.

The node set is deliberately small. Cropping, masking, encoding, sampling, blending and
decoding happen inside the generate node, because an NPU has no runtime compiler and the inside
of a compiled pipeline cannot be recombined on the phone anyway. Inpainting used to take ten
nodes and now takes four.

## Writing a node (experimental)

Contributors can add a node without an app release: a JSON manifest plus a small JavaScript
file, zipped and pushed to the app's plugin directory. No toolchain and no compile step. Four
worked examples, from a one op wrapper to a multi node toolkit, are in
[`examples/`](examples/).

This is early. The plugin format can still change between versions, there is no host op yet
that lets a node run a model of its own, and an imported script should be treated like any
other code you did not write. Read an example in `examples/` for the manifest shape and the
image and latent ops a node can call.

## Building it

JDK 17 and Android SDK 35.

```
gradlew assembleDebug
```

The NPU backend is a native binary, and the QNN runtime libraries are not in this repository.
Without them the app builds and the canvas works, but nothing renders.

## Credits

**The NPU work is not ours.** This app forks the C++ inference server from
[xororz/local-dream](https://github.com/xororz/local-dream), which is where the QNN pipelines,
the model conversions, the per chipset build tiers and the device gating all come from. The
checkpoint and upscaler archives the app downloads are published by the same author. Anyone
interested in how Stable Diffusion runs on a Hexagon NPU at all should start there rather than
here.

**FLUX.2 and Z-Image run on work by [happyyzy](https://github.com/happyyzy/stable-diffusion.cpp).**
The DiT engine is [leejet's stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
with happyyzy's Hexagon optimisations for on device DiT inference, the same engine local-dream
3.0 ships, and it is what makes these models usable on a phone at all. The Qwen3 text encoder
and the DiT VAEs were converted by
[zhiyuanasad](https://huggingface.co/zhiyuanasad/flux2_klein_adreno). The models themselves are
[FLUX.2 klein 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8) by Black Forest
Labs and [Z-Image Turbo](https://huggingface.co/Tongyi-MAI/Z-Image-Turbo) by Tongyi-MAI, with
fp8 weights by [Kijai](https://huggingface.co/Kijai/Z-Image_comfy_fp8_scaled).

**The video is not ours either.** It is
[Neodragon](https://huggingface.co/Qualcomm-AI-Research/Neodragon) by Qualcomm AI Research,
released under BSD-3-Clause-Clear with additional terms on the model card. The pyramidal
schedule, the autoregressive MMDiT loop and the streaming VAE decode are theirs, and the NPU
conversions this app runs were made from their weights. The first frame comes from
[SSD-1B](https://huggingface.co/segmind/SSD-1B) by Segmind.

**AbsoluteReality Inpaint** is Lykon's inpainting checkpoint, converted for LocalDream.

[LocalDream](https://github.com/AbrahamPaulJ/dreamui) is the consumer app built on the same
backend, and it stays the simpler way to generate a picture on a phone. Its mask editor, brush
behaviour and batch strip are ported here rather than reinvented, so that someone with both
installed does not have to learn the same tool twice.

What is new here is the graph: pipelines decomposed into ops, latents and conditionings as
handles, node types a contributor can add without an app release, and the video path taken
apart into the same kind of nodes as the picture one.

## Licence

**CC BY-NC 4.0**, inherited rather than chosen. This project derives from
[xororz/local-dream](https://github.com/xororz/local-dream), which is released under that
licence, and it continues under the same terms. No further restrictions are added here.

Share it, fork it, build on it. Do not sell it or ship it inside something you sell.

Full text in [LICENSE](LICENSE); attribution and third party components in [NOTICE](NOTICE).

The Qualcomm AI Runtime libraries the app needs at runtime are covered by Qualcomm's own terms,
which this licence does not override. They are not in this repository.

## Support

[buymeacoffee.com/abrahampaulj](https://buymeacoffee.com/abrahampaulj)

Tips are welcome and change nothing: the app stays free, offline and CC BY-NC, and nothing is
gated behind them. A donation is not a purchase of the software and does not grant commercial
rights the licence withholds.
