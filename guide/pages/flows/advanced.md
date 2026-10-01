# Advanced: LoRA, ControlNet and IP-Adapter

*Text to image and image to image on an **SD 1.5 Swap** model, with all three tools on the
generator.*

Under **Advanced** in Flows there are two cards: **Text to image** and **Image to image —
LoRA, ControlNet, IP-Adapter**. Both open with an SD 1.5 Swap model and with every tool
**switched off**, so nothing changes until you turn one on. Open the generator node to find
them: **LoRA** in its settings, **ControlNet** and **IP-Adapter** as tiles beside the picture.

## ControlNet

Pick a type (**canny**, **depth** or **openpose**) in the ControlNet tile. The picture it reads
is **wired into the generator's `control` input** so you can see it on the canvas:

- In **image to image**, the photo you are re-imagining is wired in — ControlNet follows it.
- In **text to image**, a new **image** node appears, wired in; tap it (or **Choose** in the
  tile) and pick the control picture.

To use a **different** control picture, tap **Replace** in the tile, or change the picture on
its image node on the canvas. Replace puts the picture into the control's own image node. It
never changes a picture something else uses — your img2img photo, or one image node you wired
into both ControlNet and IP-Adapter: then a new image node takes the new picture. **Remove**
empties the control's image node and keeps it, so the next picture goes back into it. Switch ControlNet to **none** and its wire and image node are removed
(your img2img photo always stays).

## IP-Adapter

Pick **plus** (subject and style) or **face** in the IP-Adapter tile. A new **image** node is
wired into the generator's `reference` input — choose the reference picture there or with
**Choose** in the tile. **none** switches it off again and removes that image node. While a
tile has no picture it shows a **+**.

You can also wire pictures in on the canvas: dragging an image into the generator's
**reference** dot switches IP-Adapter on (Plus), and into **control** switches ControlNet on
(canny), if they were off. The generator node shows what the ControlNet sees — the edges, depth
map or skeleton — next to its picture.

## Cropping a ControlNet or reference picture

When the picture is not your img2img photo, the tile has a **Crop** button. It opens the same
crop window as everywhere else — drag to move, pinch to zoom — square, because both are read
square. In **image to image** your photo is drawn **underneath**, as it will be rendered, with
the control or reference picture see-through on top, so you can line them up. **Done** closes
it.
