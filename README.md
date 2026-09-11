# Nightmare Mobile

**A ComfyUI style node graph for Stable Diffusion on Android, running entirely on device.**

Local text to image, image to image and inpainting on the Qualcomm Hexagon NPU. No server,
no account, no cloud, no network. Built on LocalDream's NPU backend.

<p align="center">
  <img src="media/ui.gif" width="270" alt="A ComfyUI style node graph running Stable Diffusion on an Android phone">
</p>

> **Early and experimental.** It works, and it has been run on exactly one phone.
> The plugin format can still change between versions.

**[Download the APK](https://github.com/AbrahamPaulJ/nightmare-mobile/releases/latest)** —
Android 12 or newer, arm64, and a Snapdragon with a Hexagon NPU. Models are not in the APK;
pick one in the app and it downloads on first use.

Stable Diffusion 1.5 and SDXL run locally on a Snapdragon NPU, offline. You draw a graph,
press Run, and the picture appears on the node that made it. Everything stays on the phone.

**Keywords:** on device AI, offline Stable Diffusion, ComfyUI for Android, mobile
Stable Diffusion, local image generation, node editor, Qualcomm Hexagon NPU, Snapdragon,
QNN, SDXL, text to image, img2img, inpainting, no cloud, private.

## What it does

- **A canvas built for a phone.** Big ports, snap to connect, pinch to zoom, a node palette
  in a sheet. Not a desktop editor shrunk down.
- **Real decomposition.** `encode_text`, `sample`, `vae_encode`, `vae_decode` and
  `latent_blend` are separate nodes. Conditionings and latents move between them as handles
  that never cross the wire, so a 512 image costs about 40 bytes of JSON instead of 780 KB.
- **Recipes to start from.** Text to image, image to image, upscale a photo, and inpainting
  where you paint the area to redo.
- **Batching.** Arm `seed`, `steps`, `cfg`, `denoise` or `scheduler` on the sampler and Run
  sweeps them. Two knobs at once gives you a grid. Every run is kept with the exact graph
  that produced it.
- **Upscalers.** RealESRGAN x4plus anime and 4x UltraSharp V2 Lite, loaded per request so
  they cost no process restart.
- **Pick your size.** SD 1.5 renders any resolution its checkpoint ships a patch for — 512²
  up to 1024², portrait and landscape; SDXL crops its fixed 1024² canvas to the shape you
  choose.
- **Bring your own model.** Fifteen checkpoints in the catalogue, or import a converted one
  as a zip. The app carries every HTP architecture tier and picks the build your chip can
  actually load.
- **Bring your own nodes.** A manifest and a script, no toolchain, no app release.
- **English, 中文 and Русский.** The interface follows your phone's language. Adding another
  is a file drop — copy `app/src/main/res/values/strings.xml` into a `values-<code>/`
  folder and translate it; no code changes. ⚠ Model prompts are never translated: SD 1.5
  reads English tags, so a translated prompt would render something else.
- **Offline and private.** Nothing is uploaded, there is no account, and no prompt or
  picture leaves the phone. The only thing that ever does is a file you explicitly share.

## Writing a node

Two files. Nothing is compiled, and nothing needs a new version of the app.

`node.json`

```json
{
  "id": "com.example.center-square",
  "version": "0.1.0",
  "api": 1,
  "nodes": [{
    "type": "CenterSquare",
    "category": "image",
    "tier": 0,
    "inputs":  [{ "name": "image", "type": "IMAGE" }],
    "outputs": [{ "name": "image", "type": "IMAGE" }],
    "widgets": [
      { "name": "size", "type": "int", "default": 192, "min": 16, "max": 2048 }
    ]
  }],
  "permissions": ["image"]
}
```

`index.js`

```js
__nm.register('com.example.center-square:CenterSquare', {
  run: function (ctx, inputs, widgets) {
    var info = ctx.host('image.info', { image: inputs.image });
    var side = Math.min(info.width, info.height);

    var square = ctx.host('image.crop', {
      image: inputs.image,
      x: Math.floor((info.width  - side) / 2),
      y: Math.floor((info.height - side) / 2),
      width: side,
      height: side
    });

    return ctx.host('image.resize', {
      image: square.image,
      width: parseInt(widgets.size, 10),
      height: parseInt(widgets.size, 10)
    });
  }
});
```

Zip the two files and import them from the Flows tab, or push the folder to the app's plugin
directory. Four worked examples are in [`examples/`](examples/).

**What a node can reach.** Inputs arrive as ids, never as pixels, and `ctx.host` is the whole
surface:

| op | takes |
|---|---|
| `image.info` | an image, returns width and height |
| `image.resize` | image, width, height |
| `image.crop` | image, x, y, width, height |
| `image.new` | width, height, colour |
| `image.composite` | base, overlay, x, y, optional mask |
| `image.blend` | a, b, alpha |
| `image.grayscale`, `image.invert` | an image |
| `latent.blend` | two latents and a mask image |

Widgets are declared, not drawn. Give a number `min` and `max` and you get a slider, give it
`options` and you get a dropdown or a row of chips, add a `hint` and it appears under the
control. An author picks values, never widgets, so no pack invents its own controls.

**What a node cannot do yet.** There is no host op that runs a model, so a node cannot
segment, detect or estimate anything. That is the next tier and it is not built. Scripts run
in a QuickJS sandbox with permissions denied by default, but nothing yet bounds how long one
may run, so treat an imported pack the way you would treat any other code you did not write.

## Building it

JDK 17 and Android SDK 35.

```
gradlew.bat assembleDebug
```

The NPU backend is a native binary and the QNN runtime libraries are not in this repository.
Without them the app builds and the canvas works, but nothing renders.

## Credits

The NPU work is not ours. This app forks the C++ inference server from
[xororz/local-dream](https://github.com/xororz/local-dream), which is where the QNN pipelines,
the model conversions, the per chipset build tiers and the device gating all come from. The
checkpoint and upscaler archives it downloads are published by the same author. Anyone
interested in how Stable Diffusion runs on a Hexagon NPU at all should start there rather
than here.

[LocalDream](https://github.com/AbrahamPaulJ/dreamui) is the consumer app built on that
backend, and it stays the simpler way to generate a picture on a phone. Its mask editor,
brush behaviour and batch strip are ported here rather than reinvented, on purpose: someone
who has both installed should not have to learn the same tool twice.

What is new here is the graph. Pipelines were decomposed into ops, latents and conditionings
became handles, and node types became something a contributor can add without an app release.

## Licence

**CC BY-NC 4.0**, inherited rather than chosen. This project derives from
[xororz/local-dream](https://github.com/xororz/local-dream), which is released under that
licence, and it continues under the same terms. No further restrictions are added here.

Share it, fork it, build on it. Do not sell it or ship it inside something you sell.

Full text in [LICENSE](LICENSE); attribution and third party components in [NOTICE](NOTICE).

The Qualcomm AI Runtime libraries the app needs at runtime are covered by Qualcomm's own
terms, which this licence does not override. They are not in this repository.
