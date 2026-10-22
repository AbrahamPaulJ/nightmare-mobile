"""Read a DiT checkpoint's tensor table without downloading it, and say what Nightmare's import would make of it.

    python tools/dit_header.py <file | https URL> [--names]

A URL is read with HTTP Range requests (the first 2 MB, more if the header needs it). For a CivitAI link set
CIVITAI_TOKEN, and from a blocked region route curl through a proxy with CURL_PROXY (e.g. socks5h://127.0.0.1:25344).
Mirrors CustomModels.ditFamilyOf / int8Problem / packedQuantProblem; the app stays the authority (docs/IMPORTS.md).
"""
import collections, json, os, re, struct, subprocess, sys

GG = {0: "F32", 1: "F16", 2: "Q4_0", 3: "Q4_1", 6: "Q5_0", 7: "Q5_1", 8: "Q8_0", 9: "Q8_1", 10: "Q2_K", 11: "Q3_K",
      12: "Q4_K", 13: "Q5_K", 14: "Q6_K", 15: "Q8_K", 16: "IQ2_XXS", 17: "IQ2_XS", 18: "IQ3_XXS", 19: "IQ1_S",
      20: "IQ4_NL", 21: "IQ3_S", 22: "IQ2_S", 23: "IQ4_XS", 24: "I8", 25: "I16", 26: "I32", 27: "I64", 28: "F64",
      29: "IQ1_M", 30: "BF16", 34: "TQ1_0", 35: "TQ2_0", 39: "MXFP4"}
# (bytes per block, elements per block), for the size column
BS = {"F32": (4, 1), "F16": (2, 1), "BF16": (2, 1), "Q4_0": (18, 32), "Q4_1": (20, 32), "Q5_0": (22, 32),
      "Q5_1": (24, 32), "Q8_0": (34, 32), "Q2_K": (84, 256), "Q3_K": (110, 256), "Q4_K": (144, 256),
      "Q5_K": (176, 256), "Q6_K": (210, 256), "MXFP4": (17, 32), "IQ4_NL": (18, 32), "I8": (1, 1), "I32": (4, 1)}
# CustomModels.ditFamilyOf: two hits of one family, and exactly one family
SIGNATURES = {
    "Z-Image": ["cap_embedder", "noise_refiner.", "context_refiner.", "attention.qkv."],
    "FLUX.2": ["double_blocks.", "single_blocks.", "img_in.", "txt_in."],
    "Krea 2": ["txtfusion.", "tproj.", "tmlp.", "qknorm."],
    "Anima DiT": ["llm_adapter.", "t_embedder.", "x_embedder.", "adaln_modulation_self_attn"],
    "MOSS-Image": ["video_embedder.", "shared_block_modulation.", "final_linear_video.", "video_head_modulation."],
}
PACKED = {"convrot_w4a4", "asym_w4a8_int8", "nvfp4", "mxfp4", "nf4", "fp4", "int4"}
NPU_OK = {"F32", "F16", "BF16", "Q4_0", "Q8_0", "MXFP4", "F8_E4M3", "F8_E5M2", "type40"}


def fetch(src, n):
    if os.path.exists(src):
        with open(src, "rb") as f:
            return f.read(n)
    url = src
    tok = os.environ.get("CIVITAI_TOKEN")
    if tok and "civitai" in src:
        url += ("&" if "?" in src else "?") + "token=" + tok
    cmd = ["curl", "-sL", "--max-time", "120", "-r", f"0-{n - 1}", url]
    if os.environ.get("CURL_PROXY") and "civitai" in src:
        cmd[1:1] = ["-x", os.environ["CURL_PROXY"]]
    return subprocess.run(cmd, capture_output=True).stdout[:n]


def safetensors(b, src):
    ln = struct.unpack("<Q", b[:8])[0]
    if not 2 <= ln <= 64 << 20:
        sys.exit(f"not a safetensors file (header claims {ln} bytes)")
    if len(b) < 8 + ln:
        b = fetch(src, 8 + ln)
    h = json.loads(b[8:8 + ln])
    meta = h.pop("__metadata__", {})
    t = {k: (v["dtype"], v["shape"], v["data_offsets"][1] - v["data_offsets"][0]) for k, v in h.items()}
    return "safetensors", t, meta


def gguf(b, src):
    o = [4]

    def take(fmt):
        v = struct.unpack_from(fmt, b, o[0])[0]
        o[0] += struct.calcsize(fmt)
        return v

    def string():
        n = take("<Q")
        v = b[o[0]:o[0] + n].decode("utf-8", "replace")
        o[0] += n
        return v

    def value(t):
        sizes = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}
        if t in sizes:
            return take(sizes[t])
        if t == 8:
            return string()
        if t == 9:
            et, n = take("<I"), take("<Q")
            return [value(et) for _ in range(n)][:8]
        raise ValueError(f"gguf value type {t}")

    take("<I")
    nt, nkv = take("<Q"), take("<Q")
    meta = {}
    for _ in range(nkv):
        k = string()
        meta[k] = value(take("<I"))
    t = {}
    for _ in range(nt):
        name = string()
        dims = [take("<Q") for _ in range(take("<I"))]
        ty = GG.get(take("<I"), "?")
        take("<Q")
        ne = 1
        for d in dims:
            ne *= d
        bs = BS.get(ty, (0, 1))
        t[name] = (ty, dims[::-1], ne // bs[1] * bs[0])
    return "gguf", t, meta


def main():
    src = sys.argv[1]
    b = fetch(src, 2 << 20)
    if len(b) < 16:
        sys.exit("could not read the file (a CivitAI link needs CIVITAI_TOKEN, and maybe CURL_PROXY)")
    kind, t, meta = (gguf if b[:4] == b"GGUF" else safetensors)(b, src)
    names = " ".join(t)
    print(f"{kind}, {len(t)} tensors")
    by = collections.defaultdict(lambda: [0, 0])
    for ty, _, n in t.values():
        by[ty][0] += 1
        by[ty][1] += n
    for ty, (n, size) in sorted(by.items(), key=lambda kv: -kv[1][1]):
        print(f"  {ty:8s} {n:5d} tensors  {size / 1e9:6.2f} GB")
    # Anima only with llm_adapter. (Z-Image's t_embedder./x_embedder. are two Anima hits)
    hits = [f for f, keys in SIGNATURES.items()
            if sum(k in names for k in keys) >= 2 and (f != "Anima DiT" or "llm_adapter." in names)]
    family = hits[0] if len(hits) == 1 else None
    print("family:", family or ("ambiguous: " + ", ".join(hits) if hits else "none named (the app asks)"))
    if family == "FLUX.2":
        w = next((v[1] for k, v in t.items() if k.endswith("img_in.weight")), None)
        print("  img_in.weight", w, "-> Klein 9B" if w and w[0] == 4096 else "-> Klein 4B (or a FLUX.1 look-alike: 64 in)")
    problems = []
    blob = json.dumps(meta)
    fmt = {m.lower() for m in re.findall(r'format\\*"\s*:\s*\\*"([A-Za-z0-9_]+)', blob)}
    if fmt & PACKED:
        problems.append("a 4-bit ComfyUI format (%s): refused" % ", ".join(sorted(fmt & PACKED)))
    if any(ty == "I8" and k.endswith("weight") for k, (ty, _, _) in t.items()):
        problems.append("int8 weights (ComfyUI int8_tensorwise, ConvRot or int4_cr): refused, the engine aborts on them")
    other = sorted({ty for ty, _, _ in t.values()} - NPU_OK - {"I8", "U8", "I32", "I64", "F64"})
    if other:
        problems.append("types not known to run on the NPU: " + ", ".join(other) + " (unmeasured)")
    print("verdict:", "; ".join(problems) if problems else ("imports" if family else "needs the family asked"))
    if "--names" in sys.argv:
        for k, (ty, shape, _) in t.items():
            if not re.search(r"(layers|blocks)\.([1-9]\d*)\.", k):
                print("  ", k, ty, shape)


if __name__ == "__main__":
    main()
