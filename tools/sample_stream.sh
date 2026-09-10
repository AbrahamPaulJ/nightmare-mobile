#!/system/bin/sh
# /sample?stream=1 -- does progress actually ARRIVE DURING the render?
#
# Usage:
#   adb -s <serial> push tools/sample_stream.sh /data/local/tmp/nmtest/
#   adb -s <serial> shell "sh /data/local/tmp/nmtest/sample_stream.sh"
#
# The check that matters is TIMING, not event count. A server that buffers the
# whole response and flushes it at the end emits exactly the same bytes as one
# that streams; only the arrival times differ. So every line is stamped with the
# second it was READ, and the test fails if the first progress event and the
# complete event land in the same second -- that is what "no streaming" looks
# like.
#
# The control is the NON-streaming call with the same body: it must produce the
# same handle and the same latent_sha. Without it, a stream of plausible
# progress events followed by a wrong latent reads as a pass.
#
# STEPS defaults to 20 (~3.5 s) because the stamps are in whole seconds. At the
# 8 steps graph_smoke.sh uses, a correct stream and a buffered one both finish
# inside ~1 s and the timing check cannot tell them apart.

BIN=/data/local/tmp/nmtest/libstable_diffusion_core.so
LIBS=/data/local/tmp/ldbase/qnnlibs
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
PORT=8189
BASE=http://127.0.0.1:$PORT
OUT=/data/local/tmp/nmtest
STEPS=${STEPS:-20}

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

BODY='{"prompt":"a cat on grass","negative_prompt":"blurry, lowres","steps":'$STEPS',"cfg":7.5,"seed":42,"width":512,"height":512}'
fail=0

# ---------------------------------------------------------------- 1. control
# The plain call first. Its handle and latent_sha are what the stream must
# reproduce exactly.
echo
echo "== 1. CONTROL: plain /sample (no stream) =="
curl -s -X POST "$BASE/sample" -H 'Content-Type: application/json' -d "$BODY" \
  > "$OUT/ctl.json"
cat "$OUT/ctl.json"; echo
REF_H=$(sed 's/.*"handle":"\([^"]*\)".*/\1/' "$OUT/ctl.json")
REF_S=$(sed 's/.*"latent_sha":"\([^"]*\)".*/\1/' "$OUT/ctl.json")
echo "  ref handle=$REF_H  latent_sha=$REF_S"
[ -n "$REF_H" ] || { echo "  FAIL: control produced no handle"; fail=1; }

# ---------------------------------------------------------------- 2. stream
# curl -N (--no-buffer). Without it curl accumulates the body and every stamp
# below reads identical -- the exact false negative this test exists to avoid,
# and it would be reported as a server bug.
echo
echo "== 2. STREAM: /sample?stream=1, each line stamped with its arrival =="
T0=$(date +%s)
curl -sN -X POST "$BASE/sample?stream=1" -H 'Content-Type: application/json' \
  -d "$BODY" | while IFS= read -r line; do
    echo "$(date +%s) $line"
  done > "$OUT/stream.txt"
T1=$(date +%s)

nprog=$(grep -c '"type":"progress"' "$OUT/stream.txt")
ncomp=$(grep -c '"type":"complete"' "$OUT/stream.txt")
nerr=$(grep -c 'event: error' "$OUT/stream.txt")
first_t=$(grep '"type":"progress"' "$OUT/stream.txt" | head -1 | cut -d' ' -f1)
comp_t=$(grep '"type":"complete"' "$OUT/stream.txt" | head -1 | cut -d' ' -f1)
echo "  wall ${T0}..${T1} ($((T1 - T0))s)  progress=$nprog complete=$ncomp error=$nerr"
echo "  first progress at ${first_t}, complete at ${comp_t}"

# One progress per step, plus CLIP, plus the explicit closing event.
[ "$nprog" -ge "$STEPS" ] || { echo "  FAIL: $nprog progress events, want >= $STEPS"; fail=1; }
[ "$ncomp" = "1" ] || { echo "  FAIL: $ncomp complete events, want 1"; fail=1; }
[ "$nerr" = "0" ] || { echo "  FAIL: $nerr error events"; fail=1; }

# The timing check. Same second = the bytes were buffered, not streamed.
if [ -n "$first_t" ] && [ -n "$comp_t" ]; then
  if [ "$comp_t" -gt "$first_t" ]; then
    echo "  OK: progress led complete by $((comp_t - first_t))s -- genuinely streamed"
  else
    echo "  FAIL: first progress and complete arrived in the same second."
    echo "        Either the response was buffered, or STEPS=$STEPS is too few"
    echo "        to resolve. Re-run with STEPS=30 before blaming the server."
    fail=1
  fi
else
  echo "  FAIL: could not stamp the events"; fail=1
fi

# The bar must reach its own denominator: sample() stops before the VAE decode
# and never spends generate()'s last step.
last_p=$(grep '"type":"progress"' "$OUT/stream.txt" | tail -1)
echo "  last progress: ${last_p#* }"
if echo "$last_p" | grep -q '"step":\([0-9]*\),"total_steps":\1'; then
  echo "  OK: bar closed at step == total_steps"
else
  echo "  FAIL: last progress did not close the bar"; fail=1
fi

# ---------------------------------------------------------------- 3. agree?
echo
echo "== 3. the stream must AGREE with the control =="
STR_H=$(grep '"type":"complete"' "$OUT/stream.txt" | sed 's/.*"handle":"\([^"]*\)".*/\1/')
STR_S=$(grep '"type":"complete"' "$OUT/stream.txt" | sed 's/.*"latent_sha":"\([^"]*\)".*/\1/')
echo "  stream handle=$STR_H  latent_sha=$STR_S"
if [ "$STR_H" = "$REF_H" ]; then echo "  OK: handle matches"
else echo "  FAIL: handle differs from the control"; fail=1; fi
if [ "$STR_S" = "$REF_S" ]; then echo "  OK: latent_sha matches"
else echo "  FAIL: latent_sha differs from the control"; fail=1; fi

# ---------------------------------------------------------------- 4. cancel
# Section 4 of the architecture needs a visible stop, and closing the stream is
# the only way to get one: opSample() holds the generation mutex for the whole
# render. What must be proved is not that the kill works but that the server
# SURVIVES it -- an abort that leaked the mutex would hang every later render
# instead of failing, and would look like a slow model.
echo
echo "== 4. CANCEL: hang up mid-render, then prove the server still works =="
curl -sN -X POST "$BASE/sample?stream=1" -H 'Content-Type: application/json' \
  -d "$BODY" > "$OUT/cancel.txt" &
CPID=$!
sleep 2
kill $CPID 2>/dev/null
wait $CPID 2>/dev/null
got=$(grep -c '"type":"progress"' "$OUT/cancel.txt")
echo "  killed the client after 2s; it had received $got progress events"
sleep 3
echo "  /health -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/health)  (want 200)"
echo "  re-sampling after the abort:"
curl -s -X POST "$BASE/sample" -H 'Content-Type: application/json' -d "$BODY" \
  > "$OUT/after.json"
AFT_H=$(sed 's/.*"handle":"\([^"]*\)".*/\1/' "$OUT/after.json")
AFT_S=$(sed 's/.*"latent_sha":"\([^"]*\)".*/\1/' "$OUT/after.json")
echo "  handle=$AFT_H latent_sha=$AFT_S"
# The server log is the only place that says whether the RENDER stopped. The
# checks above prove the server survived, which a server that quietly ran the
# render to completion would also pass -- and that one wastes a whole render
# per cancel while looking healthy. So read the log for the abort itself.
if grep -q 'Client disconnected, sample aborted' "$OUT/server.log"; then
  aborted_at=$(grep -B4 'Client disconnected, sample aborted' "$OUT/server.log" \
    | grep 'UNET step' | tail -1)
  echo "  OK: the render aborted -- last step before it: ${aborted_at:-<none>}"
else
  echo "  FAIL: no abort in server.log; the render ran to completion anyway"
  fail=1
fi

if [ "$AFT_H" = "$REF_H" ] && [ "$AFT_S" = "$REF_S" ]; then
  echo "  OK: the pipeline is intact after an aborted render"
else
  echo "  FAIL: the server did not recover from the cancel"; fail=1
fi

# ---------------------------------------------------------------- 5. guard
echo
echo "== 5. GUARD: a bad body must still 400 on the streaming path =="
echo "  http $(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/sample?stream=1" \
  -H 'Content-Type: application/json' -d '{not json}')  (want 400)"
echo "  /health -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/health)  (want 200)"

kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
echo
if [ "$fail" = "0" ]; then echo PASS; else echo FAIL; fi
exit $fail
