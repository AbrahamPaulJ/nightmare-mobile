#!/system/bin/sh
# Decode two latents and write real PNGs, so a human (or an agent) can LOOK at
# them. tools/op_smoke.sh proves vae_decode is deterministic; determinism is not
# correctness. An all-black image hashes just as consistently as a good one.
#
# Usage:
#   adb -s <serial> push tools/vae_dump.sh /data/local/tmp/nmtest/
#   adb -s <serial> shell "sh /data/local/tmp/nmtest/vae_dump.sh"
#   adb -s <serial> pull /data/local/tmp/nmtest/out_zeros.png
#   adb -s <serial> pull /data/local/tmp/nmtest/out_seed42.png

BIN=/data/local/tmp/nmtest/libstable_diffusion_core.so
LIBS=/data/local/tmp/ldbase/qnnlibs
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
PORT=8189
BASE=http://127.0.0.1:$PORT
OUT=/data/local/tmp/nmtest

if ps -A -o PID,ARGS 2>/dev/null | grep -q "[l]ibstable_diffusion_core.so --type"; then
  echo "FAIL: a backend is already running; kill it first."
  exit 1
fi

cd "$OUT" || exit 1
# ⚠ DSP_LIBRARY_PATH is required as well as LD_LIBRARY_PATH; without it the HTP
# backend cannot load its skel and dies with "error: 1002".
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

# ⚠ Do NOT extract png_b64 from the JSON on device. toybox sed cannot do a
# greedy match across a 134 KB single line and silently writes an EMPTY file --
# indistinguishable from a failed decode. ?binary=1 returns image/png, so
# `curl -o` writes it straight to disk and the metadata rides in headers.
dump() { # $1 = output name, $2 = request body
  curl -s -D "$OUT/$1.hdr" -o "$OUT/$1.png" \
    -X POST "$BASE/vae_decode?binary=1" \
    -H 'Content-Type: application/json' -d "$2"

  bytes=$(wc -c < "$OUT/$1.png")
  sha=$(awk '/[Xx]-[Rr]gb-[Ss]ha:/ {gsub(/\r/,""); print $2}' "$OUT/$1.hdr")
  ms=$(awk '/[Xx]-[Mm]s:/ {gsub(/\r/,""); print $2}' "$OUT/$1.hdr")

  # A PNG begins with 89 50 4e 47. Without this an empty or truncated file
  # still reads as success -- which is exactly what the sed approach did.
  magic=$(head -c 4 "$OUT/$1.png" | od -An -tx1 | tr -d '[:space:]')
  if [ "$magic" = "89504e47" ]; then
    verdict="PNG ok"
  else
    verdict="NOT A PNG (magic=$magic)"
  fi

  echo "$1.png  ${bytes} bytes  sha=${sha}  ${ms}ms  ${verdict}"
  rm -f "$OUT/$1.hdr"
}

dump out_zeros  '{"width":512,"height":512}'
dump out_seed42 '{"width":512,"height":512,"seed":42}'

kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
echo done
