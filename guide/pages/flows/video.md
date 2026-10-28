# Video

*A prompt, or a prompt and a photo, into a clip. Or your photo moving like a video you choose.*

There are three video models. Install any of them:

| model | makes | needs |
|---|---|---|
| **Wan 2.2 Turbo** (6.25 GB) | up to 5 seconds, any shape up to 1280 × 704. The better clips; minutes per clip | Snapdragon 8 Elite or newer (alpha on 8 Gen 3: tell us if it runs) |
| **Wan 2.2 14B** (about 21 GB; 3.7 GB less beside Wan 2.2 Turbo) | your picture moving, or text to video through a picture first (**Text to video (14B)** below). The best motion and detail, and slow: about 9 minutes for 2 seconds at 480 × 832, 12 minutes for 1 second at 720 × 1280 (faces come out much sharper at the larger size) | Snapdragon 8 Gen 3 or newer, 12 GB of RAM (on an 8 Gen 3, up to 832 × 480) |
| **Neodragon** (about 8.6 GB) | 2 seconds at 1024 × 640 in about 25 seconds | Snapdragon 8 Elite or 8 Elite Gen 5 |

Video has its own models; only **Text to video (14B)** also uses your picture model, for its first frame. **Models → Video** lists them all, and **Wan**
in the family menu has Wan's models on their own page.

## Text to video

**Nodes:** Prompt → Video → Output.

1. **Flows → Text to video.**
2. Write the prompt: describe the scene *and the motion*. `waves rolling onto a beach at
   sunset, camera slowly panning right`.
3. **Run.** With Wan 2.2, 5 seconds at 832 × 480 takes under **3 minutes** (about 2:45) on an 8 Elite; the
   bar moves once per step, four steps, then the clip is decoded and encoded.

The video node's **Model** dropdown switches between **Wan 2.2 Turbo** (the default) and **Neodragon** (the fastest), and **Wan 2.2 14B** with a picture wired: the 14B animates a picture. For text to video on the 14B, use the next flow.
**Use** on Neodragon in Models opens the flow on Neodragon.

## Text to video (14B)

**Nodes:** Prompt → Image (your picture model) → Video (Wan 2.2 14B) → Output. Needs Wan 2.2 14B and an 8 Gen 3 or newer with 12 GB (an 8 Gen 3 goes up to 832 × 480).

1. **Flows → Text to video (14B)**, or **Use** on Wan 2.2 14B in Models (a picture model's **Use** does not offer it). The picture is made by the model in the top bar, in its own shape: the clip takes
   that shape (a 512 × 768 picture makes a portrait clip), and the video node's size sets how many pixels, 832 × 480
   worth for a quick try or 1280 × 720 worth for sharp faces. 1 second by default.
2. Write one prompt for the scene and its motion: it makes the picture and drives the clip.
3. **Run.** The picture appears on its node first, in seconds, then the clip takes minutes. Not the picture you wanted? Stop the run and Run again. Happy with it? Lock its seed (the seed chip lists each node's seed): the next Run keeps that picture and only makes a new clip.

The clip **loops on the node** that made it; tap it to play it full screen. With **Save** on
(the default), an MP4 is also written to `Movies/Nightmare`.

## Image to video

**Nodes:** Prompt and Image → Video → Output, the same flow with a photo wired in.

1. **Flows → Image to video.**
2. Choose the photo on the **image** node. Tap the video node's **Crop** preview: the frame
   takes the clip's shape; drag and pinch to choose the part that is animated. The photo is the
   clip's **first frame**.
3. Describe what moves: `the camera slowly pushes in, the woman turns her head and smiles`.
   **Run.**

## Wan 2.2 settings

| setting | does |
|---|---|
| **Width**, **Height** | Any shape, in 32 px steps, up to 1280 × 704 worth of pixels: square, 4:3, 21:9, a phone screen. Past that, the other side shrinks to fit. Wan 2.2 is made for 1280 × 704: smaller sizes, 832 × 480 included, can look soft or distorted. 832 × 480 is quickest; 1280 × 704 takes about three times as long. On **Wan 2.2 14B** (its own 16 px steps, up to 1280 × 720 worth of pixels), 1280 × 720 is the size for faces and fine detail (about 12 minutes for 1 second); 832 × 480 is for trying an idea first (about 5 minutes), with soft faces. On an 8 Gen 3 the 14B goes up to 832 × 480 worth of pixels (720p there is untested). **Match picture** sets the photo's shape |
| **Seed** | `0` = a new clip every Run; type a seed shown on the node to get that clip back |
| **Seconds** | 0 to 5, at 24 frames a second (Wan 2.2 14B: 1 to 3 at 16 frames a second; 1 second at 720p is about 12 minutes, 3 can take an hour); 0 makes one still picture (about 15 s at 832 × 480 on an 8 Elite; wire **last frame** to the output). On a 12 GB phone, 1280 × 704 for 3 seconds or more renders in parts so it fits: slower, about 12 minutes for 5 seconds. On an 8 Gen 3 a new flow opens on 2 seconds, the longest that has worked there so far; longer clips and 1280 × 704 still run, with a warning in the run log |
| **Smooth motion** | Twice the frames: a new one between every pair, made on the NPU by IFRNet, so 16 frames a second become 32 and 24 become 48. Fast and smooth motion stops stuttering. It adds seconds, not minutes: about 0.2 s a new frame at 832 × 480 and 0.5 s at 720p on an 8 Elite. Up to 1280 × 736 or 736 × 1280; a larger clip stays at its own rate. Needs **Smooth motion** from Models → Tools (about 75 MB, Snapdragon 8 Gen 3 or newer); switching it on offers the download |
| **Parts** | 1 to 10. More than 1 makes a longer clip in parts, each as long as **Seconds** and starting on the last frame of the part before, joined into one video. Each part takes as long as one clip |
| **Part prompts** | With 2 or more parts: what happens in part 2 onwards, one line each. A blank or missing line repeats the part before; part 1 uses the Prompt node |
| **Keep colours** | With 2 or more parts, on by default: holds every part to the first frame's colours. Without it a long clip tends to turn darker and redder part by part (the model drifts a little each part and the next starts from there). Turn it off for a clip meant to change its light |
| **LoRAs** | A LoRA made for the model you use: Wan 2.2 Turbo (TI2V-5B) LoRAs on Turbo; Wan 14B LoRAs on Wan 2.2 14B. A single-file Wan 2.1 14B LoRA works on both of its halves; a Wan 2.2 high/low pair is told apart by its file names (`high_noise`, `low_noise`). See [LoRAs](../models/loras.md) |

Neodragon has **Seed** and **Upscale** (on: 2× to 1024 × 640; off: 512 × 320, a little faster).

<figure markdown>
  ![Models → Video with Wan 2.2](../img/models-video-wan.png){ .screen }
</figure>

## Longer clips: one film

The simplest way is **Parts** on the video node (above): up to 10 parts, a prompt for each, one video.

The Wan video node also has a second output, **last frame**: the clip's final frame as a picture. In
advanced view, wire it into another Wan video node's **image** and give that one its own prompt:
it carries on from where the first clip ended.

The second node's **video** is then the **whole film so far**, the first clip and its own joined
into one (the frame they share appears once). Chain as many as you like: the last node hands the
Output the complete film. A clip that carries on a film takes the film's frame rate: smoothed if
the film is, whatever its own **Smooth motion** switch says. **Results** keeps every part on its own as well, each with the picture
it started from, grouped together beside the film.

On the canvas, a film longer than about 5 seconds loops faster than real time; full screen plays
it at its own speed. The **camera** button beside share (full screen, on the canvas and in
Results) saves the frame on screen to the gallery as a picture.

## Motion transfer

*Your picture moving like a video you choose: its pose, frame by frame.*

**Needs** Wan 2.2 Fun Control: about 3.7 GB with Wan 2.2 Turbo installed (it borrows Turbo's text
encoder and decoder), 7.3 GB without. **Models → Video → Wan 2.2 Fun Control → Download.**

**Nodes:** Prompt, Image (who moves) and **Video** (the moving clip) → Motion transfer → Output.

1. **Flows → Motion transfer.**
2. Choose your picture on the **image** node: it is who the clip shows (face, clothes, setting), and
   the clip starts on it. A full-body photo works best.
3. Choose a video on the **Video** node: a person dancing, walking, waving. Its sound can come along.
4. On the Motion transfer node, tap the **Video** preview and drag either end of **Part of the
   video**: the part you choose is the clip's length, up to 30 seconds. A long part is made in
   pieces, each starting a few frames before the one before ends and joined where the two match best.
5. Pick a **Mode** (the ⓘ beside it says which to use) and **Run**.

| Mode | for |
|---|---|
| **Pose** | a real video of a person. The body, hands and face are read from every frame and your picture copies them. Needs the Pose Detector and Whole-body pose (Models, Tools) |
| **As is** | a video that already is a control video: a pose skeleton or a depth map made elsewhere. An ordinary video here will not work well |

| Setting | what it does |
|---|---|
| **Frame rate** | how often the video is read: **24** (Wan's own rate, best), **12** or **8** (fewer frames, so faster, but the motion moves in bigger jumps; turn on **Smooth motion** to fill them in) |
| **Follow the person** | on: the crop moves with the person on every frame, so a dancer who crosses the room stays large. Off: draw a box in the **Video** tab that cuts every frame the same way |
| **Line up my picture** | on: your picture is cropped so its person stands where the dancer starts, at the dancer's size, so the clip does not open on two people |
| **Keep audio** | on: the video's sound over the same part is copied into the clip |
| **Smooth motion** | twice the frames, as on every Wan node |

Motion transfer takes no LoRAs: `<lora:…>` tags in its prompt are skipped, and the run log says
so. It opens at 480 × 832, where the crop that follows the person keeps faces and hands readable.
On an 8 Elite 10 seconds at 24 fps take about 10 minutes, in three pieces; at 12 fps with Smooth motion about 5 minutes,
in one piece (up to 10 seconds). If the app is closed mid-way (other apps need the memory), Run again with the same
seed and it carries on from the last finished piece.
