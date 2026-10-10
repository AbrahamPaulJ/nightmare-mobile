# Local API

Another device on your Wi-Fi or tailnet (a script, a desktop app, an AI agent) can run your
flows on the phone through a small HTTP API. It is **off by default**.

## Turning it on

**Settings → Advanced → API → Listen on the local network.** The app shows the address to use
(`http://<phone>:8820`) and a **token**. While it listens, a notification says so; its **Stop**
turns the API off. **New token** cuts off every client that had the old one.

Only addresses on your own network are answered: home and office ranges, and Tailscale's
`100.64.x.x`–`100.127.x.x`. Every call except `/info` needs the token:

```
Authorization: Bearer <token>
```

## Calls

| call | what it does |
|---|---|
| `GET /info` | the app, its version, whether it is rendering |
| `GET /models` | installed checkpoints: id, family, native size, steps / CFG / scheduler |
| `GET /flows` | the recipes (`txt2img`, `img2img`, `inpaint`, `swap_t2i`, …), your saved flows (`saved:<name>`) and `current` — what is on the canvas |
| `GET /flows/<id>` | a flow's nodes with every setting each one takes |
| `POST /run` | runs a flow — below |
| `GET /results/<run>/<file>` | a result picture (PNG) or clip (MP4) |
| `POST /cancel` | stops the API's render |

## Running a flow

```sh
curl -H "Authorization: Bearer $TOKEN" -X POST http://192.168.0.4:8820/run -d '{
  "flow": "txt2img",
  "model": "absolutereality",
  "prompt": "a lighthouse on a cliff at sunset",
  "seed": 42, "steps": 20
}'
```

The answer lists the results: `{"run": "…", "results": [{"url": "/results/…/output.png", …}]}`.
Add `"stream": true` to follow the render as server-sent events — each node, the step count and
the run log's lines — ending in a `done` (or `error`) event with the same answer.

What you can set:

- **`flow`** — a recipe id, `saved:<name>` or `current`; or **`graph`**, a whole flow as the `.json` the app
  shares (Flows → Saved → share).
- **`model`** — every generate node switches to it, with its steps, CFG and scheduler.
- **`prompt`**, **`negative`** — every prompt node.
- **`seed`**, **`steps`**, **`cfg`**, **`denoise`**, **`scheduler`**, **`width`**, **`height`**,
  **`aspect`**, **`loras`** — every generate node that has the setting.
- **`params`** — any setting of any node: `{"generate": {"controlnet": "canny"}}`.
- **`images`** — a picture for an image node, base64: `{"image": "<base64>"}`.

A name the flow does not have, or a value it does not accept, is refused with a sentence saying
which — nothing runs. An empty picture is skipped the way the Run button skips it (a warning in
`warnings`). One render at a time: while the app itself is rendering, `/run` answers `409`.

Results are kept for the last 20 runs. They are not added to the app's Results.
