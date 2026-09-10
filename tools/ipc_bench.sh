#!/system/bin/sh
# Measure localhost HTTP round-trip cost against a DreamUI-lineage backend.
# Answers: how fine-grained can a node be before IPC costs real time?
#
# Results are recorded in docs/ARCHITECTURE.md §3. Re-run this whenever the
# forked backend gains ops, changes transport, or moves to a new device tier.
#
# Usage (from PowerShell, NOT Git Bash -- it mangles /data/... paths):
#   adb -s <serial> push tools/ipc_bench.sh /data/local/tmp/ldbase/
#   adb -s <serial> shell "sh /data/local/tmp/ldbase/ipc_bench.sh"
#
# Expects a staged backend + model at /data/local/tmp/ldbase (see
# ../LocalDream/docs/BACKEND.md for the launch contract).
#
# ⚠ THREE CHECKS BIT DURING THE FIRST RUN OF THIS. Keep all three guards:
#
#  1. Payload cost MUST be measured against an UNSERVED path. Measuring it
#     against /tokenize tokenizes the body (count: 131075) and reports ~1.1 s
#     for a 1 MB body -- ~900x the true transport cost. The 404 path still
#     receives the whole body but runs no handler.
#  2. `pgrep -f stable_diffusion_core` MATCHES ITS OWN COMMAND LINE and reports
#     a server that does not exist. Use `ps -A -o PID,ARGS | grep '[s]table_...'`.
#  3. The backend DIES WITH ITS ADB SESSION even under nohup, so it is launched
#     in this same shell rather than by a separate adb call.
#
# Every timing block has an empty-body control on the same path in the same
# pass: if the control is not ~60 us, the reader is broken, not the transport.

cd /data/local/tmp/ldbase || exit 1
export LD_LIBRARY_PATH=$PWD/qnnlibs:/system/lib64:/vendor/lib64:/vendor/lib64/egl
export DSP_LIBRARY_PATH=$PWD/qnnlibs
PORT=${PORT:-8085}
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
TYPE=${TYPE:-sd15npu}
U=http://127.0.0.1:$PORT

ps -A -o PID,ARGS | grep '[s]table_diffusion_core' && {
  echo "⚠ a backend is ALREADY running -- requests would hit it, not ours. Aborting."
  echo "  (stale servers look exactly like nondeterministic results)"; exit 1; }

./libstable_diffusion_core.so --type "$TYPE" --model_dir "$MODEL" \
  --lib_dir "$PWD/qnnlibs" --port "$PORT" > ipc_bench.log 2>&1 &
SRV=$!
echo "launched pid $SRV ($TYPE, $MODEL)"

i=0; c=000
while [ $i -lt 60 ]; do
  c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 $U/health)
  [ "$c" = "200" ] && break
  i=$((i+1)); sleep 1
done
[ "$c" != "200" ] && { echo "FAILED to come up:"; tail -20 ipc_bench.log; kill $SRV; exit 1; }
echo "cold load -> healthy in ${i}s   (this is also the model-switch cost)"

echo ""
echo "=== control: 404, EMPTY body x8 -- expect ~60us ==="
curl -s -o /dev/null -w '%{time_total}\n' -X POST -d '' \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__ \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__

echo ""
echo "=== GET /health x12 (transport RTT) ==="
curl -s -o /dev/null -w '%{time_total}\n' \
  $U/health $U/health $U/health $U/health $U/health $U/health \
  $U/health $U/health $U/health $U/health $U/health $U/health

mk() { head -c "$1" /dev/zero | base64 | tr -d '\n' > "$2"; }

mk 65536 _lat.txt
echo ""
echo "=== 404, latent-sized body ($(wc -c < _lat.txt) B b64) x8 ==="
curl -s -o /dev/null -w '%{time_total}\n' -X POST --data-binary @_lat.txt \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__ \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__

mk 786432 _big.txt
echo ""
echo "=== 404, image-sized body ($(wc -c < _big.txt) B b64) x8 ==="
curl -s -o /dev/null -w '%{time_total}\n' -X POST --data-binary @_big.txt \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__ \
  $U/__nope__ $U/__nope__ $U/__nope__ $U/__nope__

echo ""
echo "=== POST /tokenize, short prompt x8 (a REAL handler, for scale) ==="
curl -s -o /dev/null -w '%{time_total}\n' -H 'Content-Type: application/json' \
  -d '{"prompt":"a photograph of an astronaut riding a horse on the moon"}' \
  $U/tokenize $U/tokenize $U/tokenize $U/tokenize \
  $U/tokenize $U/tokenize $U/tokenize $U/tokenize

rm -f _lat.txt _big.txt
kill $SRV 2>/dev/null; sleep 1
ps -A -o PID,ARGS | grep '[s]table_diffusion_core' && echo "⚠ server SURVIVED the kill -- clean up before the next run"
echo "=== done ==="
