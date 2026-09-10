#!/system/bin/sh
# Run the forked backend in the FOREGROUND, so an adb shell holds it open while
# something else -- the app, curl, a browser -- talks to it.
#
# Usage (the adb call blocks until you stop it):
#   adb -s <serial> push tools/serve.sh /data/local/tmp/nmtest/
#   adb -s <serial> shell "sh /data/local/tmp/nmtest/serve.sh"
#
# ⚠ THE BACKEND DIES WITH ITS ADB SESSION, even under nohup. That is why this
# runs in the foreground and why the calling adb command must stay open. A
# backgrounded launch looks like it worked and then the server is gone by the
# time anything connects.

BIN=/data/local/tmp/nmtest/libstable_diffusion_core.so
LIBS=/data/local/tmp/ldbase/qnnlibs
MODEL=${MODEL:-/data/local/tmp/ldbase/models/dreamshaper}
PORT=${PORT:-8189}

if ps -A -o PID,ARGS 2>/dev/null | grep -q "[l]ibstable_diffusion_core.so --type"; then
  echo "FAIL: a backend is already running. Kill it first -- two servers on one"
  echo "      port means whichever won the bind is the one you are testing."
  exit 1
fi

cd /data/local/tmp/nmtest || exit 1
# ⚠ DSP_LIBRARY_PATH is required as well as LD_LIBRARY_PATH. Without it the HTP
# backend cannot load its skel and dies with "error: 1002", naming neither.
export LD_LIBRARY_PATH=$LIBS:/system/lib64:/vendor/lib64:/vendor/lib64/egl
export DSP_LIBRARY_PATH=$LIBS

echo "serving $MODEL on 127.0.0.1:$PORT -- Ctrl-C or close the adb session to stop"
exec "$BIN" --type sd15npu --model_dir "$MODEL" --lib_dir "$LIBS" --port "$PORT"
