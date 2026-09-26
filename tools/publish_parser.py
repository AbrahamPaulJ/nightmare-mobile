"""Publish the int8 ATR parser that `segment/Parser.kt` downloads.

⚠⚠ NOT RUN YET as of 2026-09-23 — `Parser.URL` points at the file this creates,
and until it exists the only copy is the one side-loaded onto the phone
(`notes/PROGRESS.md` ⓪, "PICK BY NAME"). Running this is what makes Download
and Delete work on that Tools row.

⚠ A new FILENAME per revision, never an upload over the old one — the same rule
`Segmenter.REVISION` exists for (`docs/SEGMENTER.md` §2). Bump `Parser.REVISION`
and `DEST` together, or a phone with the old file will never fetch the new one.

Rebuilding the artefact from scratch (6 s, needs onnxruntime + onnx only):

    curl -L -o b2_fp32.onnx \\
      https://huggingface.co/mattmdjaga/segformer_b2_clothes/resolve/main/onnx/model.onnx
    python -c "from onnxruntime.quantization import quantize_dynamic, QuantType; \\
      quantize_dynamic('b2_fp32.onnx', 'b2_int8.onnx', weight_type=QuantType.QUInt8)"

⚠ The result must be exactly `Parser.BYTES` (28,635,315) or the app rejects the
download by size. ⚠ fp16 was measured and is NOT the one to ship: ORT's CPU EP
has no fp16 kernels and casts, making it 2× SLOWER than fp32 (`docs/SEGMENTER.md` §7).

Usage:  python tools/publish_parser.py <path to b2_int8.onnx>
"""
import os
import sys

from huggingface_hub import HfApi

# Token: $HF_TOKEN, else whatever `huggingface-cli login` cached.
TOKEN = os.environ.get("HF_TOKEN")

REPO = "AbrahamPJ/segformer-b2-clothes-onnx"
DEST = "atr-parser-int8-v1.onnx"
BYTES = 28_635_315

CARD = """---
license: other
license_name: nvidia-segformer
license_link: https://github.com/NVlabs/SegFormer/blob/master/LICENSE
tags: [onnx, segmentation, human-parsing, android]
---

# ATR human parser, int8 ONNX (for Nightmare Mobile)

[`mattmdjaga/segformer_b2_clothes`](https://huggingface.co/mattmdjaga/segformer_b2_clothes)
(SegFormer-B2 fine-tuned on ATR, 27.4M params) quantized to int8 with
`onnxruntime.quantization.quantize_dynamic(QUInt8)` for on-device CPU inference.

* input `pixel_values` `[1,3,512,512]` float32 — image squashed to 512x512
  (not letterboxed), rescaled 1/255, then ImageNet mean/std.
* output `logits` `[1,18,128,128]` float32 — argmax over the 18 ATR labels.

Labels: Background, Hat, Hair, Sunglasses, Upper-clothes, Skirt, Pants, Dress,
Belt, Left-shoe, Right-shoe, Face, Left-leg, Right-leg, Left-arm, Right-arm,
Bag, Scarf.

## Measured, Snapdragon 8 Elite (S25 Ultra), ORT 1.29.0 CPU, 4 threads

| | fp32 | this (int8) |
|---|---|---|
| bytes | 110,039,290 | 28,635,315 |
| inference, warm | 886-999 ms | **510-616 ms** |

Label agreement with fp32 is 97.8-99.9% of pixels, and the union masks
(clothes, skin) are IoU 0.97. Quantization moves pixels between *adjacent*
labels (Dress vs Upper-clothes, a limb vs Bag) but never empties a mask — so
grouped targets are stable and individual garment names are not.

Weights and license are upstream's; this repo only re-exports them.
"""


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    size = os.path.getsize(src)
    if size != BYTES:
        sys.exit("refusing: %s is %d bytes, Parser.BYTES is %d" % (src, size, BYTES))
    api = HfApi(token=TOKEN)
    api.create_repo(REPO, repo_type="model", exist_ok=True, private=False)
    api.upload_file(path_or_fileobj=src, path_in_repo=DEST, repo_id=REPO)
    api.upload_file(path_or_fileobj=CARD.encode(), path_in_repo="README.md", repo_id=REPO)
    print("https://huggingface.co/%s/resolve/main/%s" % (REPO, DEST))


if __name__ == "__main__":
    main()
