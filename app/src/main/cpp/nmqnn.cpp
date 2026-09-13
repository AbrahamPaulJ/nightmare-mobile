// In-process QNN runner — load a context binary, bind tensors by name, execute.
//
// ⭐⭐ **Ported from `../Neodragon`'s `ndqnn.cpp`** (the user's own T2V_NPU
// project), unchanged except for the JNI package and the log tag. The comments
// below are its measurements, not re-derived ones — `docs/NEODRAGON.md` says
// what the port changed and what it deliberately did not.
//
// ⚠ This is the app's SECOND route to the NPU and it does not replace the
// first. `libstable_diffusion_core.so` is a separate PROCESS holding a
// checkpoint bound at launch (`docs/ARCHITECTURE.md` §4); this runs graphs
// **in-process**, one context binary at a time, joining no context key — the
// `/upscale` pattern, without the HTTP hop. A node that uses it needs no
// backend running at all.
//
//
// WHY THIS EXISTS, and why the obvious approach does not work:
//
// The desktop harness drives every graph by exec'ing `qnn-net-run` over adb, and the first
// version of this app shipped that same binary inside the APK as `libqnnnetrun.so`. It runs
// -- it prints its build banner and reads its inputs -- and then dies in backend init with
//
//     QnnDsp <E> loadRemoteSymbols failed with err 4000
//     QnnDsp <E> Failed to load skel, error: 4000
//     Device Creation failure
//
// That is not a missing library and not a wrong ADSP_LIBRARY_PATH. Bisected on device, the
// byte-identical binary (same md5) with identical LD_LIBRARY_PATH, identical skel directory
// and identical CWD succeeds from /data/local/tmp and fails from the APK:
//
//     /data/local/tmp/nd/qnn-net-run          u:object_r:shell_data_file:s0   works
//     <apk>/lib/arm64/libqnnnetrun.so         u:object_r:apk_data_file:s0     fails
//
// The only variable is where the executable lives, so a process exec'd out of the APK is
// denied the Hexagon fastrpc device. Exec'ing a helper is therefore a dead end for an app
// no matter how the environment is arranged; the backend has to be dlopen'd INTO the app
// process, which already holds the DSP permission it needs.
//
// The rewrite pays for itself twice over: the context binary stays resident between calls
// instead of being re-read every inference. Loading mmdit_s2f is 1.58 GB off flash, and the
// autoregressive video loop visits all three MMDiT stages every unit.
//
// Quantization is handled here rather than in Kotlin because it has to be. qnn-net-run
// reads fp32 raws and converts them using each tensor's own encoding from the binary; doing
// it in-process means replicating that, and the direction is easy to get backwards -- QNN's
// convention is real = (quantized + offset) * scale with a NEGATIVE offset. Getting it wrong
// yields plausible-looking output rather than an error (trap #8 in the same family).

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <jni.h>

#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "QNN/QnnBackend.h"
#include "QNN/QnnCommon.h"
#include "QNN/QnnContext.h"
#include "QNN/QnnDevice.h"
#include "QNN/QnnGraph.h"
#include "QNN/QnnInterface.h"
#include "QNN/QnnLog.h"
#include "QNN/QnnTensor.h"
#include "QNN/QnnTypes.h"
#include "QNN/HTP/QnnHtpDevice.h"
#include "QNN/HTP/QnnHtpPerfInfrastructure.h"
#include "QNN/System/QnnSystemContext.h"
#include "QNN/System/QnnSystemInterface.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "nmqnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nmqnn", __VA_ARGS__)

namespace {

std::string g_err;  // last error, surfaced to Kotlin as an exception message

bool fail(const std::string& m) {
  g_err = m;
  LOGE("%s", m.c_str());
  return false;
}

// ---------------------------------------------------------------------------
// Tensor field access. Qnn_Tensor_t is a versioned union and the binaries this
// app loads are written by 2.49, but reading through the version tag rather than
// assuming v2 costs nothing and survives an SDK bump.
// ---------------------------------------------------------------------------

#define TF(t, field) \
  ((t).version == QNN_TENSOR_VERSION_1 ? (t).v1.field : (t).v2.field)

const char* tName(const Qnn_Tensor_t& t) { return TF(t, name); }
uint32_t tRank(const Qnn_Tensor_t& t) { return TF(t, rank); }
uint32_t* tDims(const Qnn_Tensor_t& t) { return TF(t, dimensions); }
Qnn_DataType_t tType(const Qnn_Tensor_t& t) { return TF(t, dataType); }
Qnn_QuantizeParams_t tQuant(const Qnn_Tensor_t& t) { return TF(t, quantizeParams); }

void tSetBuf(Qnn_Tensor_t& t, void* data, uint32_t bytes) {
  if (t.version == QNN_TENSOR_VERSION_1) {
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    t.v1.clientBuf = {data, bytes};
  } else {
    t.v2.memType = QNN_TENSORMEMTYPE_RAW;
    t.v2.clientBuf = {data, bytes};
  }
}

size_t elemSize(Qnn_DataType_t d) {
  switch (d) {
    case QNN_DATATYPE_INT_8:
    case QNN_DATATYPE_UINT_8:
    case QNN_DATATYPE_SFIXED_POINT_8:
    case QNN_DATATYPE_UFIXED_POINT_8:
    case QNN_DATATYPE_BOOL_8:      return 1;
    case QNN_DATATYPE_INT_16:
    case QNN_DATATYPE_UINT_16:
    case QNN_DATATYPE_FLOAT_16:
    case QNN_DATATYPE_SFIXED_POINT_16:
    case QNN_DATATYPE_UFIXED_POINT_16: return 2;
    case QNN_DATATYPE_INT_32:
    case QNN_DATATYPE_UINT_32:
    case QNN_DATATYPE_FLOAT_32:
    case QNN_DATATYPE_SFIXED_POINT_32:
    case QNN_DATATYPE_UFIXED_POINT_32: return 4;
    case QNN_DATATYPE_INT_64:
    case QNN_DATATYPE_UINT_64:
    case QNN_DATATYPE_FLOAT_64:    return 8;
    default:                        return 0;
  }
}

size_t numElems(const Qnn_Tensor_t& t) {
  size_t n = 1;
  uint32_t r = tRank(t);
  uint32_t* d = tDims(t);
  for (uint32_t i = 0; i < r; ++i) n *= d[i];
  return n;
}

// fp16 <-> fp32, needed because some graphs ship FLOAT_16 I/O.
float h2f(uint16_t h) {
  uint32_t s = (h & 0x8000u) << 16;
  uint32_t e = (h >> 10) & 0x1F;
  uint32_t m = h & 0x3FF;
  uint32_t out;
  if (e == 0) {
    if (m == 0) { out = s; }
    else {                                  // subnormal -> normalise
      e = 127 - 15 + 1;
      while (!(m & 0x400)) { m <<= 1; --e; }
      m &= 0x3FF;
      out = s | (e << 23) | (m << 13);
    }
  } else if (e == 31) {
    out = s | 0x7F800000u | (m << 13);
  } else {
    out = s | ((e - 15 + 127) << 23) | (m << 13);
  }
  float f;
  std::memcpy(&f, &out, 4);
  return f;
}

uint16_t f2h(float f) {
  uint32_t x;
  std::memcpy(&x, &f, 4);
  uint32_t s = (x >> 16) & 0x8000u;
  int32_t e = (int32_t)((x >> 23) & 0xFF) - 127 + 15;
  uint32_t m = x & 0x7FFFFF;
  if (e <= 0) return (uint16_t)s;                        // flush subnormals to zero
  if (e >= 31) return (uint16_t)(s | 0x7C00u);           // saturate to inf
  return (uint16_t)(s | (e << 10) | (m >> 13));
}

// Per-tensor encoding. Per-CHANNEL (axis) encodings appear on weights, never on
// the graph I/O these models expose, so an axis encoding here means something is
// wrong and is reported rather than silently mis-scaled.
bool encodingOf(const Qnn_Tensor_t& t, double* scale, double* offset) {
  Qnn_QuantizeParams_t q = tQuant(t);
  if (q.encodingDefinition != QNN_DEFINITION_DEFINED) { *scale = 1.0; *offset = 0.0; return true; }
  if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
    *scale = q.scaleOffsetEncoding.scale;
    *offset = q.scaleOffsetEncoding.offset;
    return true;
  }
  if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET) {
    *scale = q.bwScaleOffsetEncoding.scale;
    *offset = q.bwScaleOffsetEncoding.offset;
    return true;
  }
  return fail(std::string("tensor ") + tName(t) + ": unsupported quantization encoding " +
              std::to_string((int)q.quantizationEncoding));
}

// real -> device bytes.  QNN: real = (q + offset) * scale, offset negative.
bool writeTensor(const Qnn_Tensor_t& t, void* dst, const float* src, size_t n) {
  double sc, off;
  if (!encodingOf(t, &sc, &off)) return false;
  switch (tType(t)) {
    case QNN_DATATYPE_FLOAT_32:
      std::memcpy(dst, src, n * 4);
      return true;
    case QNN_DATATYPE_FLOAT_16: {
      auto* d = (uint16_t*)dst;
      for (size_t i = 0; i < n; ++i) d[i] = f2h(src[i]);
      return true;
    }
    case QNN_DATATYPE_INT_32:
    case QNN_DATATYPE_UINT_32: {
      auto* d = (int32_t*)dst;
      for (size_t i = 0; i < n; ++i) d[i] = (int32_t)llrintf(src[i]);
      return true;
    }
    case QNN_DATATYPE_UFIXED_POINT_16: {
      auto* d = (uint16_t*)dst;
      for (size_t i = 0; i < n; ++i) {
        double q = std::nearbyint(src[i] / sc) - off;
        d[i] = (uint16_t)(q < 0 ? 0 : (q > 65535 ? 65535 : q));
      }
      return true;
    }
    case QNN_DATATYPE_UFIXED_POINT_8: {
      auto* d = (uint8_t*)dst;
      for (size_t i = 0; i < n; ++i) {
        double q = std::nearbyint(src[i] / sc) - off;
        d[i] = (uint8_t)(q < 0 ? 0 : (q > 255 ? 255 : q));
      }
      return true;
    }
    case QNN_DATATYPE_SFIXED_POINT_16: {
      auto* d = (int16_t*)dst;
      for (size_t i = 0; i < n; ++i) {
        double q = std::nearbyint(src[i] / sc) - off;
        d[i] = (int16_t)(q < -32768 ? -32768 : (q > 32767 ? 32767 : q));
      }
      return true;
    }
    case QNN_DATATYPE_SFIXED_POINT_8: {
      auto* d = (int8_t*)dst;
      for (size_t i = 0; i < n; ++i) {
        double q = std::nearbyint(src[i] / sc) - off;
        d[i] = (int8_t)(q < -128 ? -128 : (q > 127 ? 127 : q));
      }
      return true;
    }
    default:
      return fail(std::string("tensor ") + tName(t) + ": unsupported input dtype " +
                  std::to_string((int)tType(t)));
  }
}

// device bytes -> real
bool readTensor(const Qnn_Tensor_t& t, const void* src, float* dst, size_t n) {
  double sc, off;
  if (!encodingOf(t, &sc, &off)) return false;
  switch (tType(t)) {
    case QNN_DATATYPE_FLOAT_32:
      std::memcpy(dst, src, n * 4);
      return true;
    case QNN_DATATYPE_FLOAT_16: {
      auto* s = (const uint16_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = h2f(s[i]);
      return true;
    }
    case QNN_DATATYPE_INT_32:
    case QNN_DATATYPE_UINT_32: {
      auto* s = (const int32_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = (float)s[i];
      return true;
    }
    case QNN_DATATYPE_UFIXED_POINT_16: {
      auto* s = (const uint16_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = (float)((s[i] + off) * sc);
      return true;
    }
    case QNN_DATATYPE_UFIXED_POINT_8: {
      auto* s = (const uint8_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = (float)((s[i] + off) * sc);
      return true;
    }
    case QNN_DATATYPE_SFIXED_POINT_16: {
      auto* s = (const int16_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = (float)((s[i] + off) * sc);
      return true;
    }
    case QNN_DATATYPE_SFIXED_POINT_8: {
      auto* s = (const int8_t*)src;
      for (size_t i = 0; i < n; ++i) dst[i] = (float)((s[i] + off) * sc);
      return true;
    }
    default:
      return fail(std::string("tensor ") + tName(t) + ": unsupported output dtype " +
                  std::to_string((int)tType(t)));
  }
}

// ---------------------------------------------------------------------------
// Backend, held once for the process.
// ---------------------------------------------------------------------------

struct Backend {
  void* libBackend = nullptr;
  void* libSystem = nullptr;
  QNN_INTERFACE_VER_TYPE qnn{};
  QNN_SYSTEM_INTERFACE_VER_TYPE sys{};
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_LogHandle_t log = nullptr;
  uint32_t powerId = 0;
  bool ready = false;
};

Backend g_be;
std::mutex g_mu;

// Forward QNN's own diagnostics to logcat. Without this the DSP's explanation of a
// failed deviceCreate ("Failed to load skel", "loadRemoteSymbols failed") is discarded
// and all that survives is an unhelpful error code.
void qnnLog(const char* fmt, QnnLog_Level_t level, uint64_t, va_list argp) {
  char buf[1024];
  vsnprintf(buf, sizeof(buf), fmt, argp);
  int prio = level == QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
           : level == QNN_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                          : ANDROID_LOG_INFO;
  __android_log_print(prio, "nmqnn.qnn", "%s", buf);
}

bool initBackend(const std::string& backendPath, const std::string& systemPath,
                 const std::string& skelDir) {
  if (g_be.ready) return true;

  // The Hexagon skel is found through ADSP_LIBRARY_PATH, which the fastrpc layer reads
  // when it is first loaded -- so this must happen BEFORE dlopen'ing the backend. The
  // exec'd version of this app got it from ProcessBuilder's environment; in-process
  // there is nothing to inherit it from. The vendor rfsa directories are kept on the
  // path so the platform's own stubs still resolve.
  std::string adsp = skelDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
  LOGI("ADSP_LIBRARY_PATH=%s", adsp.c_str());

  // ⚠⚠⚠ PRELOAD THE HOST-SIDE ARCH LIBS BY ABSOLUTE PATH, OR NOTHING WORKS.
  //
  // `libQnnHtp.so` dlopens `libQnnHtpV<arch>Stub.so` by BARE NAME. That goes to
  // the dynamic linker's search path -- which on Android is the app's
  // `nativeLibraryDir` and the system paths, and is NOT `ADSP_LIBRARY_PATH`
  // (that variable is read by fastrpc for the DSP-side *Skel*, a different
  // library entirely). Measured on device 2026-09-12, and it is the exact point
  // where this app diverges from `../Neodragon`:
  //
  //     QnnDsp <W> Failed in loading stub: dlopen failed:
  //                library "libQnnHtpV79Stub.so" not found
  //     QnnDsp <E> loadRemoteSymbols failed with err 4000
  //     QnnDsp <E> Failed to load skel, error: 4000
  //
  // ⚠⚠ That error names the SKEL and the failure is the STUB. Neodragon read it
  // as "the DSP is not reachable from this process" and concluded SELinux was
  // denying an APK-resident process the fastrpc device. The message is the same
  // in both cases; here the cause is a missing file on the linker path.
  //
  // ⚠ Neodragon never hit it because it shipped the QNN libs in `jniLibs`, so
  // they landed in `nativeLibraryDir` and the bare-name dlopen just worked. This
  // app ships them as ASSETS and unpacks only the device's arch trio at runtime
  // (`BackendProcess.prepareRuntime`) -- 150 MB of arches in the APK, one trio
  // on disk. That is a deliberate trade and it is not being undone for this.
  //
  // ⇒ dlopen each host-side lib by ABSOLUTE path first. Android registers a
  // library under its soname, so the later bare-name dlopen finds the one
  // already loaded instead of searching. RTLD_GLOBAL so its symbols are visible
  // to the backend that comes next.
  //
  // ⚠ Failures are logged, not fatal: the trio present depends on the device's
  // arch, `*Skel.so` is a DSP-side ELF that cannot be dlopen'd here at all, and
  // guessing which names *should* exist is how a new arch becomes a crash.
  // ⚠⚠ …and the stub itself needs `libcdsprpc.so`, the fastrpc client, which
  // lives in /vendor/lib64 and is NOT on an app's linker path by default.
  // Measured 2026-09-12:
  //
  //     dlopen failed: library "libcdsprpc.so" not found:
  //       needed by .../qnnruntime/libQnnHtpV79Stub.so in namespace clns-9
  //
  // ⚠ It IS listed in this device's /vendor/etc/public.libraries.txt, so the
  // bare name may resolve through the vendor namespace link. Both spellings are
  // tried and the outcome is logged, because which one works is a property of
  // the DEVICE's namespace configuration and not something to assume.
  for (const char* cand : {"libcdsprpc.so", "/vendor/lib64/libcdsprpc.so",
                           "libadsprpc.so", "/vendor/lib64/libadsprpc.so"}) {
    if (dlopen(cand, RTLD_NOW | RTLD_GLOBAL)) {
      LOGI("fastrpc: loaded %s", cand);
      break;
    }
    LOGI("fastrpc: %s -> %s", cand, dlerror());
  }

  {
    DIR* d = opendir(skelDir.c_str());
    if (!d) return fail("cannot read runtime dir " + skelDir);
    int preloaded = 0;
    while (struct dirent* e = readdir(d)) {
      std::string n = e->d_name;
      if (n.rfind("libQnnHtpV", 0) != 0) continue;
      // The Skel runs on the DSP. dlopen'ing it here fails loudly and means
      // nothing; fastrpc finds it through ADSP_LIBRARY_PATH above.
      if (n.find("Skel") != std::string::npos) continue;
      std::string full = skelDir + "/" + n;
      if (dlopen(full.c_str(), RTLD_NOW | RTLD_GLOBAL)) {
        ++preloaded;
      } else {
        LOGI("preload skipped %s: %s", n.c_str(), dlerror());
      }
    }
    closedir(d);
    LOGI("preloaded %d host-side HTP libs from %s", preloaded, skelDir.c_str());
  }

  g_be.libBackend = dlopen(backendPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!g_be.libBackend) return fail(std::string("dlopen backend: ") + dlerror());
  g_be.libSystem = dlopen(systemPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!g_be.libSystem) return fail(std::string("dlopen system: ") + dlerror());

  auto getProviders = (Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*))
      dlsym(g_be.libBackend, "QnnInterface_getProviders");
  if (!getProviders) return fail("QnnInterface_getProviders not found");

  const QnnInterface_t** providers = nullptr;
  uint32_t n = 0;
  if (getProviders(&providers, &n) != QNN_SUCCESS || n == 0)
    return fail("QnnInterface_getProviders returned none");
  g_be.qnn = providers[0]->QNN_INTERFACE_VER_NAME;

  auto getSysProviders = (Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*))
      dlsym(g_be.libSystem, "QnnSystemInterface_getProviders");
  if (!getSysProviders) return fail("QnnSystemInterface_getProviders not found");
  const QnnSystemInterface_t** sysProviders = nullptr;
  uint32_t sn = 0;
  if (getSysProviders(&sysProviders, &sn) != QNN_SUCCESS || sn == 0)
    return fail("QnnSystemInterface_getProviders returned none");
  g_be.sys = sysProviders[0]->QNN_SYSTEM_INTERFACE_VER_NAME;

  if (g_be.qnn.logCreate) g_be.qnn.logCreate(qnnLog, QNN_LOG_LEVEL_WARN, &g_be.log);

  if (g_be.qnn.backendCreate(g_be.log, nullptr, &g_be.backend) != QNN_SUCCESS)
    return fail("backendCreate failed");

  // This is the call that failed with err 4000 when qnn-net-run was exec'd out of
  // the APK. In-process it succeeds, which is the whole point of this file.
  if (g_be.qnn.deviceCreate &&
      g_be.qnn.deviceCreate(g_be.log, nullptr, &g_be.device) != QNN_SUCCESS)
    return fail("deviceCreate failed -- the DSP is not reachable from this process");

  // Burst clocks. Without this the HTP ramps lazily and every measured latency in
  // docs/ (which were all taken with --perf_profile burst) is unreproducible here.
  QnnDevice_Infrastructure_t infra{};
  if (g_be.qnn.deviceGetInfrastructure &&
      g_be.qnn.deviceGetInfrastructure(&infra) == QNN_SUCCESS) {
    auto* htpInfra = (QnnHtpDevice_Infrastructure_t*)infra;
    if (htpInfra && htpInfra->infraType == QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
      auto& perf = htpInfra->perfInfra;
      if (perf.createPowerConfigId &&
          perf.createPowerConfigId(0, 0, &g_be.powerId) == QNN_SUCCESS) {
        QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
        dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
        dcvs.dcvsV3Config.contextId = g_be.powerId;
        dcvs.dcvsV3Config.setDcvsEnable = 1;
        dcvs.dcvsV3Config.dcvsEnable = 0;              // pin, do not let DCVS drop us
        dcvs.dcvsV3Config.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
        dcvs.dcvsV3Config.setSleepLatency = 1;
        dcvs.dcvsV3Config.sleepLatency = 40;
        dcvs.dcvsV3Config.setBusParams = 1;
        dcvs.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        dcvs.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        dcvs.dcvsV3Config.setCoreParams = 1;
        dcvs.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        dcvs.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = {&dcvs, nullptr};
        if (perf.setPowerConfig) perf.setPowerConfig(g_be.powerId, cfgs);
      }
    }
  }

  g_be.ready = true;
  LOGI("QNN backend ready");
  return true;
}

// ---------------------------------------------------------------------------
// A loaded context binary, kept resident.
// ---------------------------------------------------------------------------

struct Model {
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::vector<Qnn_Tensor_t> inputs, outputs;      // shallow copies of binary metadata
  std::vector<std::vector<uint8_t>> inBuf, outBuf;
  QnnSystemContext_Handle_t sysCtx = nullptr;
};



bool bindTensors(Model* m, const QnnSystemContext_GraphInfo_t& gi) {
  const char* gname = nullptr;
  uint32_t nIn = 0, nOut = 0;
  Qnn_Tensor_t *ins = nullptr, *outs = nullptr;

  switch (gi.version) {
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
      gname = gi.graphInfoV1.graphName; nIn = gi.graphInfoV1.numGraphInputs;
      ins = gi.graphInfoV1.graphInputs; nOut = gi.graphInfoV1.numGraphOutputs;
      outs = gi.graphInfoV1.graphOutputs; break;
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
      gname = gi.graphInfoV2.graphName; nIn = gi.graphInfoV2.numGraphInputs;
      ins = gi.graphInfoV2.graphInputs; nOut = gi.graphInfoV2.numGraphOutputs;
      outs = gi.graphInfoV2.graphOutputs; break;
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
      gname = gi.graphInfoV3.graphName; nIn = gi.graphInfoV3.numGraphInputs;
      ins = gi.graphInfoV3.graphInputs; nOut = gi.graphInfoV3.numGraphOutputs;
      outs = gi.graphInfoV3.graphOutputs; break;
    default:
      return fail("unsupported graph info version");
  }

  if (g_be.qnn.graphRetrieve(m->context, gname, &m->graph) != QNN_SUCCESS)
    return fail(std::string("graphRetrieve failed for ") + (gname ? gname : "?"));

  m->inputs.assign(ins, ins + nIn);
  m->outputs.assign(outs, outs + nOut);
  m->inBuf.resize(nIn);
  m->outBuf.resize(nOut);

  for (uint32_t i = 0; i < nIn; ++i) {
    size_t bytes = numElems(m->inputs[i]) * elemSize(tType(m->inputs[i]));
    if (!bytes) return fail(std::string("input ") + tName(m->inputs[i]) + ": zero size");
    m->inBuf[i].resize(bytes);
    tSetBuf(m->inputs[i], m->inBuf[i].data(), (uint32_t)bytes);
  }
  for (uint32_t i = 0; i < nOut; ++i) {
    size_t bytes = numElems(m->outputs[i]) * elemSize(tType(m->outputs[i]));
    if (!bytes) return fail(std::string("output ") + tName(m->outputs[i]) + ": zero size");
    m->outBuf[i].resize(bytes);
    tSetBuf(m->outputs[i], m->outBuf[i].data(), (uint32_t)bytes);
  }
  return true;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_lastError(JNIEnv* env, jclass) {
  return env->NewStringUTF(g_err.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_init(JNIEnv* env, jclass, jstring jBackend, jstring jSystem,
                                       jstring jSkelDir) {
  std::lock_guard<std::mutex> lk(g_mu);
  const char* b = env->GetStringUTFChars(jBackend, nullptr);
  const char* s = env->GetStringUTFChars(jSystem, nullptr);
  const char* k = env->GetStringUTFChars(jSkelDir, nullptr);
  bool ok = initBackend(b, s, k);
  env->ReleaseStringUTFChars(jBackend, b);
  env->ReleaseStringUTFChars(jSystem, s);
  env->ReleaseStringUTFChars(jSkelDir, k);
  return ok ? JNI_TRUE : JNI_FALSE;
}

/** Load a context binary and keep it resident. Returns 0 on failure. */
JNIEXPORT jlong JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_load(JNIEnv* env, jclass, jstring jPath) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (!g_be.ready) { fail("backend not initialised"); return 0; }

  const char* path = env->GetStringUTFChars(jPath, nullptr);
  std::string p(path);
  env->ReleaseStringUTFChars(jPath, path);

  // mmap rather than read(): contextCreateFromBinary deserialises into its own
  // storage, so a heap copy would be a transient duplicate of the whole file -- 1.4 GB
  // for clipg and ssd1bunet. On an 11 GB phone that was enough to put the process into
  // lmkd's sights (MemFree was observed at 110 MB with eight background apps culled in
  // one sweep). The mapping is dropped as soon as the context exists.
  // ⚠ Logged BEFORE the load, not after. A crash inside the deserialiser
  // takes the process with it, so a line printed on success names every binary
  // EXCEPT the one that failed -- which is the only one worth knowing.
  LOGI("loading %s", p.c_str());

  int fd = open(p.c_str(), O_RDONLY);
  if (fd < 0) { fail("cannot open " + p); return 0; }
  struct stat st{};
  if (fstat(fd, &st) != 0) { close(fd); fail("cannot stat " + p); return 0; }
  size_t sz = (size_t)st.st_size;
  void* blob = mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (blob == MAP_FAILED) { fail("mmap failed for " + p); return 0; }
  // The pages are read once, front to back, and never needed again.
  madvise(blob, sz, MADV_SEQUENTIAL);

  struct MapGuard {
    void* p; size_t n;
    ~MapGuard() { if (p) munmap(p, n); }
  } guard{blob, sz};

  auto m = std::make_unique<Model>();

  // The binary's metadata is what names the tensors and gives their encodings; it
  // has to be parsed before the context exists.
  if (g_be.sys.systemContextCreate(&m->sysCtx) != QNN_SUCCESS) {
    fail("systemContextCreate failed");
    return 0;
  }
  const QnnSystemContext_BinaryInfo_t* info = nullptr;
  Qnn_ContextBinarySize_t infoSize = 0;
  if (g_be.sys.systemContextGetBinaryInfo(m->sysCtx, blob, sz, &info,
                                          &infoSize) != QNN_SUCCESS || !info) {
    fail("systemContextGetBinaryInfo failed for " + p);
    return 0;
  }

  const QnnSystemContext_GraphInfo_t* graphs = nullptr;
  uint32_t numGraphs = 0;
  if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
    graphs = info->contextBinaryInfoV1.graphs;
    numGraphs = info->contextBinaryInfoV1.numGraphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
    graphs = info->contextBinaryInfoV2.graphs;
    numGraphs = info->contextBinaryInfoV2.numGraphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
    graphs = info->contextBinaryInfoV3.graphs;
    numGraphs = info->contextBinaryInfoV3.numGraphs;
  } else {
    fail("unsupported binary info version");
    return 0;
  }
  if (numGraphs != 1) {
    // Every binary this project converts holds exactly one graph; more than one
    // would mean the wrong file was pushed.
    fail(p + ": expected 1 graph, found " + std::to_string(numGraphs));
    return 0;
  }

  if (g_be.qnn.contextCreateFromBinary(g_be.backend, g_be.device, nullptr, blob,
                                       sz, &m->context, nullptr) != QNN_SUCCESS) {
    fail("contextCreateFromBinary failed for " + p);
    return 0;
  }
  if (!bindTensors(m.get(), graphs[0])) return 0;

  LOGI("loaded %s (%.2f MB, %zu in, %zu out)", p.c_str(), sz / 1048576.0,
       m->inputs.size(), m->outputs.size());
  return (jlong)(intptr_t)m.release();
}

/** "name:d0,d1,...:dtype" per tensor, inputs then outputs, separated by ';'. */
JNIEXPORT jstring JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_describe(JNIEnv* env, jclass, jlong h) {
  // ⚠⚠ Takes the same lock `load`/`execute`/`free` take. It did not upstream,
  // where one pipeline on one thread was the only caller. Here a node builds a
  // runner per run and frees it in a `finally`, so "nothing else is in QNN right
  // now" stopped being structurally true — and reading a Model while another
  // thread deletes it is a use-after-free whose signature is a jump to a
  // garbage address, indistinguishable from the memory-pressure crash this was
  // found next to.
  std::lock_guard<std::mutex> lk(g_mu);
  auto* m = (Model*)(intptr_t)h;
  std::string s;
  auto add = [&](const std::vector<Qnn_Tensor_t>& v, const char* tag) {
    for (auto& t : v) {
      s += tag; s += tName(t); s += ":";
      uint32_t r = tRank(t); uint32_t* d = tDims(t);
      for (uint32_t i = 0; i < r; ++i) { if (i) s += ","; s += std::to_string(d[i]); }
      s += ":" + std::to_string((int)tType(t)) + ";";
    }
  };
  add(m->inputs, "in ");
  add(m->outputs, "out ");
  return env->NewStringUTF(s.c_str());
}

/**
 * Execute with inputs given BY NAME. Names are matched against the binary's own
 * metadata rather than positionally -- the converter is free to reorder graph
 * inputs, and a silent mismatch would produce plausible output.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_execute(JNIEnv* env, jclass, jlong h,
                                          jobjectArray jNames, jobjectArray jData) {
  std::lock_guard<std::mutex> lk(g_mu);
  auto* m = (Model*)(intptr_t)h;
  if (!m) { fail("null model handle"); return nullptr; }

  jsize nGiven = env->GetArrayLength(jNames);
  if ((size_t)nGiven != m->inputs.size()) {
    fail("expected " + std::to_string(m->inputs.size()) + " inputs, got " +
         std::to_string(nGiven));
    return nullptr;
  }

  for (jsize i = 0; i < nGiven; ++i) {
    auto jn = (jstring)env->GetObjectArrayElement(jNames, i);
    const char* nm = env->GetStringUTFChars(jn, nullptr);
    std::string name(nm);
    env->ReleaseStringUTFChars(jn, nm);

    int slot = -1;
    for (size_t k = 0; k < m->inputs.size(); ++k)
      if (name == tName(m->inputs[k])) { slot = (int)k; break; }
    if (slot < 0) { fail("no graph input named " + name); return nullptr; }

    auto arr = (jfloatArray)env->GetObjectArrayElement(jData, i);
    size_t want = numElems(m->inputs[slot]);
    size_t got = (size_t)env->GetArrayLength(arr);
    if (got != want) {
      fail(name + ": expected " + std::to_string(want) + " floats, got " +
           std::to_string(got));
      return nullptr;
    }
    jfloat* src = env->GetFloatArrayElements(arr, nullptr);
    bool ok = writeTensor(m->inputs[slot], m->inBuf[slot].data(), src, want);
    env->ReleaseFloatArrayElements(arr, src, JNI_ABORT);
    if (!ok) return nullptr;
  }

  Qnn_ErrorHandle_t e = g_be.qnn.graphExecute(
      m->graph, m->inputs.data(), (uint32_t)m->inputs.size(), m->outputs.data(),
      (uint32_t)m->outputs.size(), nullptr, nullptr);
  if (e != QNN_SUCCESS) {
    fail("graphExecute failed: " + std::to_string((long long)e));
    return nullptr;
  }

  jclass fac = env->FindClass("[F");
  jobjectArray res = env->NewObjectArray((jsize)m->outputs.size(), fac, nullptr);
  for (size_t i = 0; i < m->outputs.size(); ++i) {
    size_t n = numElems(m->outputs[i]);
    jfloatArray a = env->NewFloatArray((jsize)n);
    std::vector<float> tmp(n);
    if (!readTensor(m->outputs[i], m->outBuf[i].data(), tmp.data(), n)) return nullptr;
    env->SetFloatArrayRegion(a, 0, (jsize)n, tmp.data());
    env->SetObjectArrayElement(res, (jsize)i, a);
    env->DeleteLocalRef(a);
  }
  return res;
}

/** Output names in the order execute() returns them. */
JNIEXPORT jobjectArray JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_outputNames(JNIEnv* env, jclass, jlong h) {
  std::lock_guard<std::mutex> lk(g_mu);
  auto* m = (Model*)(intptr_t)h;
  jclass sc = env->FindClass("java/lang/String");
  jobjectArray res = env->NewObjectArray((jsize)m->outputs.size(), sc, nullptr);
  for (size_t i = 0; i < m->outputs.size(); ++i) {
    jstring s = env->NewStringUTF(tName(m->outputs[i]));
    env->SetObjectArrayElement(res, (jsize)i, s);
    env->DeleteLocalRef(s);
  }
  return res;
}

JNIEXPORT void JNICALL
Java_com_abrah_nightmare_npu_NativeQnn_free(JNIEnv*, jclass, jlong h) {
  std::lock_guard<std::mutex> lk(g_mu);
  auto* m = (Model*)(intptr_t)h;
  if (!m) return;
  if (m->context) g_be.qnn.contextFree(m->context, nullptr);
  if (m->sysCtx) g_be.sys.systemContextFree(m->sysCtx);
  delete m;
}

}  // extern "C"
