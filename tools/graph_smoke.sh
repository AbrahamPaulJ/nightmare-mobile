#!/system/bin/sh
# The first two-node graph: sample() -> vae_decode, joined by a latent handle
# that never leaves the server.
#
# Usage:
#   adb -s <serial> push tools/graph_smoke.sh /data/local/tmp/nmtest/
#   adb -s <serial> shell "sh /data/local/tmp/nmtest/graph_smoke.sh"
#   adb -s <serial> pull /data/local/tmp/nmtest/graph_cat.png
#
# ⭐ The check that matters is VISUAL. A sampled latent must decode to an actual
# picture. A random latent decodes to beige blobs (tools/vae_dump.sh), and both
# are deterministic, so hashes alone cannot tell a working sampler from a broken
# one -- only looking at the output can.

BIN=/data/local/tmp/nmtest/libstable_diffusion_core.so
LIBS=/data/local/tmp/ldbase/qnnlibs
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
PORT=8189
BASE=http://127.0.0.1:$PORT
OUT=/data/local/tmp/nmtest
STEPS=${STEPS:-8}

if ps -A -o PID,ARGS 2>/dev/null | grep -q "[l]ibstable_diffusion_core.so --type"; then
  echo "FAIL: a backend is already running; kill it first."
  exit 1
fi

cd "$OUT" || exit 1
export LD_LIBRARY_PATH=$LIBS:/system/lib64:/vendor/lib64:/vendor/lib64/egl
export DSP_LIBRARY_PATH=$LIBS
"$BIN" --type sd15npu --model_dir "$MODEL" --lib_dir "$LIBS" --port $PORT \
  > "$OUT/server.log" 2>&1 &
SERVER_PID=$!

i=0
while [ $i -lt 90 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health" 2>/dev/null)
  [ "$code" = "200" ] && break
  i=$((i + 1))
  sleep 1
done
if [ "$code" != "200" ]; then
  echo "FAIL: no /health after ${i}s"
  tail -20 "$OUT/server.log"
  kill $SERVER_PID 2>/dev/null
  exit 1
fi
echo "backend up after ${i}s"

sample() { # $1 = label, $2 = body
  echo
  echo "== sample: $1 =="
  curl -s -X POST "$BASE/sample" -H 'Content-Type: application/json' -d "$2"
  echo
}

BODY_A='{"prompt":"a cat on grass","negative_prompt":"blurry, lowres","steps":'$STEPS',"cfg":7.5,"seed":42,"width":512,"height":512}'
BODY_B='{"prompt":"a cat on grass","negative_prompt":"blurry, lowres","steps":'$STEPS',"cfg":7.5,"seed":43,"width":512,"height":512}'

sample "seed 42"            "$BODY_A"
sample "seed 42 again (handle + latent_sha MUST match)" "$BODY_A"
sample "seed 43 (latent_sha MUST differ)" "$BODY_B"

# Pull the handle out of a fresh call. ⚠ Small JSON here, so a sed extract is
# safe -- unlike the 134 KB png_b64 case that silently produced an empty file.
HANDLE=$(curl -s -X POST "$BASE/sample" -H 'Content-Type: application/json' \
  -d "$BODY_A" | sed 's/.*"handle":"\([^"]*\)".*/\1/')
echo
echo "== chaining through handle: $HANDLE =="

curl -s -D "$OUT/g.hdr" -o "$OUT/graph_cat.png" \
  -X POST "$BASE/vae_decode?binary=1" -H 'Content-Type: application/json' \
  -d '{"latent_handle":"'"$HANDLE"'"}'

bytes=$(wc -c < "$OUT/graph_cat.png")
sha=$(awk '/[Xx]-[Rr]gb-[Ss]ha:/ {gsub(/\r/,""); print $2}' "$OUT/g.hdr")
magic=$(head -c 4 "$OUT/graph_cat.png" | od -An -tx1 | tr -d '[:space:]')
if [ "$magic" = "89504e47" ]; then v="PNG ok"; else v="NOT A PNG (magic=$magic)"; fi
echo "graph_cat.png  ${bytes} bytes  rgb_sha=${sha}  ${v}"
rm -f "$OUT/g.hdr"

echo
echo "== GUARD: an unknown handle must 400, not crash =="
echo "  http $(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/vae_decode \
  -H 'Content-Type: application/json' -d '{"latent_handle":"lat_nope"}')  (want 400)"
echo "  /health -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/health)  (want 200)"

kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
echo
echo done
