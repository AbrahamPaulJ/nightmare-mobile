#!/system/bin/sh
# Smoke-test the Nightmare op endpoints against a real model on the NPU.
#
# Usage (from PowerShell, NOT Git Bash -- it mangles /data/... paths):
#   adb -s <serial> push tools/op_smoke.sh /data/local/tmp/nmtest/
#   adb -s <serial> shell "sh /data/local/tmp/nmtest/op_smoke.sh"
#
# Expects our forked binary at /data/local/tmp/nmtest/ and a staged model +
# QNN libs under /data/local/tmp/ldbase (see ../backend-patches/README.md).
#
# ⚠ THE BACKEND DIES WITH ITS ADB SESSION even under nohup, so it MUST be
# launched from this same shell rather than by a separate adb call. That is why
# this is a script and not three adb commands.
#
# ⚠ Port 8189, not 8085: DreamUI's own backend may be resident on 8085 and a
# silent connection to the WRONG server would look exactly like success.

BIN=/data/local/tmp/nmtest/libstable_diffusion_core.so
LIBS=/data/local/tmp/ldbase/qnnlibs
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
PORT=8189
BASE=http://127.0.0.1:$PORT
LOG=/data/local/tmp/nmtest/server.log

# ⚠ pgrep -f MATCHES ITS OWN COMMAND LINE and reports a server that does not
# exist. Use ps + a bracketed grep, as tools/ipc_bench.sh learned the hard way.
if ps -A -o PID,ARGS 2>/dev/null | grep -q "[l]ibstable_diffusion_core.so --type"; then
  echo "FAIL: a backend is already running; kill it first."
  echo "      (a stale server looks exactly like a passing test)"
  exit 1
fi

echo "== launching backend on :$PORT =="
cd /data/local/tmp/nmtest || exit 1
# ⚠ BOTH variables are required, and the failure mode of omitting the second is
# deeply unhelpful. Without DSP_LIBRARY_PATH the HTP backend cannot load its
# skel (libQnnHtpV79Skel.so) and dies with
#   QnnDsp <E> Failed to load skel, error: 1002
#   ... Failed to create device / UNET Device Creation failure
# which names neither the variable nor the file. LD_LIBRARY_PATH alone is NOT
# enough. Taken from ldbase/run_base.sh, which is the reference launcher.
export LD_LIBRARY_PATH=$LIBS:/system/lib64:/vendor/lib64:/vendor/lib64/egl
export DSP_LIBRARY_PATH=$LIBS
"$BIN" --type sd15npu --model_dir "$MODEL" \
  --lib_dir "$LIBS" --port $PORT > "$LOG" 2>&1 &
SERVER_PID=$!

i=0
while [ $i -lt 90 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health" 2>/dev/null)
  [ "$code" = "200" ] && break
  i=$((i + 1))
  sleep 1
done

if [ "$code" != "200" ]; then
  echo "FAIL: backend never answered /health (last code: '$code') after ${i}s"
  echo "---- server.log tail ----"
  tail -25 "$LOG"
  kill $SERVER_PID 2>/dev/null
  exit 1
fi
echo "health OK after ${i}s"

echo
echo "== CONTROL: an endpoint that must NOT exist =="
# If this returns 200, we are talking to some other server and every result
# below is meaningless.
echo "  /definitely_not_an_endpoint -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/definitely_not_an_endpoint)  (want 404)"

echo
echo "== encode_text, run 1 =="
curl -s -X POST "$BASE/encode_text" -H 'Content-Type: application/json' \
  -d '{"prompt":"a cat on grass","negative_prompt":"blurry, lowres"}'
echo

echo
echo "== encode_text, run 2 (same input: handle, preview_neg AND preview_pos must all match) =="
curl -s -X POST "$BASE/encode_text" -H 'Content-Type: application/json' \
  -d '{"prompt":"a cat on grass","negative_prompt":"blurry, lowres"}'
echo

echo
echo "== encode_text, run 3 (different POSITIVE prompt, same negative) =="
curl -s -X POST "$BASE/encode_text" -H 'Content-Type: application/json' \
  -d '{"prompt":"a dog on sand","negative_prompt":"blurry, lowres"}'
# ⚠ preview_pos MUST differ from run 1; preview_neg MUST match it, because the
# negative prompt is unchanged. An identical preview_pos means the positive side
# is not being encoded -- and a preview taken only from the front of `hidden`
# would show a false match every time, which is exactly what v1 of this did.
echo

# ⚠ png_b64 is ~500 KB of base64. Strip it: an unreadable wall of output is an
# output nobody reads, and this test exists to be read.
strip_png() { sed 's/"png_b64":"[^"]*"/"png_b64":"<stripped>"/'; }

echo
echo "== vae_decode: zeros (flat field -- cheapest proof the graph ran) =="
curl -s -X POST "$BASE/vae_decode" -H 'Content-Type: application/json'   -d '{"width":512,"height":512}' | strip_png
echo

echo
echo "== vae_decode: seed 42 =="
curl -s -X POST "$BASE/vae_decode" -H 'Content-Type: application/json'   -d '{"width":512,"height":512,"seed":42}' | strip_png
echo

echo
echo "== vae_decode: seed 42 again (rgb_sha MUST match the run above) =="
curl -s -X POST "$BASE/vae_decode" -H 'Content-Type: application/json'   -d '{"width":512,"height":512,"seed":42}' | strip_png
echo

echo
echo "== vae_decode: seed 43 (rgb_sha MUST differ) =="
curl -s -X POST "$BASE/vae_decode" -H 'Content-Type: application/json'   -d '{"width":512,"height":512,"seed":43}' | strip_png
echo

echo
echo "== GUARD: a deliberately wrong-sized latent MUST 400, not crash =="
# 3 floats where 16384 are required. Before the guard in opVaeDecodeToRgb this
# walked off the end of a fixed graph tensor and killed the server.
echo "  http $(curl -s -o /tmp/nm_guard.json -w '%{http_code}' -X POST $BASE/vae_decode   -H 'Content-Type: application/json'   -d '{"width":512,"height":512,"latent_b64":"AAAAAAAAAAAAAAAA"}')  (want 400)"
cat /tmp/nm_guard.json 2>/dev/null
echo

echo
echo "== GUARD AFTERMATH: the server must still be alive =="
echo "  /health -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/health)  (want 200)"

echo
echo "== handles resident =="
curl -s "$BASE/handles"
echo

echo
echo "== shutting down =="
kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
echo "done"
