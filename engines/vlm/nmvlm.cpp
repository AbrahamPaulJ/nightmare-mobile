// nmvlm: picture to prompt with a vision language model (GitHub #120), our JNI bridge over llama.cpp's mtmd.
//
// Built by .github/workflows/vlm-engine.yml with llama.cpp linked in STATICALLY, so the engine is this one
// .so plus the Hexagon skels (libggml-htp-v*.so, DSP-side, found through ADSP_LIBRARY_PATH). The app downloads
// it (vlm/VlmEngine.kt) and loads it with System.load inside its own process (":vlm", vlm/VlmService.kt):
//   - ADSP_LIBRARY_PATH is read ONCE per process by fastrpc, and the in-process Neodragon runner (nmqnn.cpp)
//     sets it to its own skel dir; a separate process gives each its own.
//   - ending that process frees the model at once, before a DiT render needs the memory.
//
// One model at a time, one call at a time (the service serialises them). Everything here is ours; llama.cpp
// is MIT (NOTICE).

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "nmvlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::string g_error;
std::mutex g_mu;

void setError(const std::string& e) {
  g_error = e;
  LOGW("%s", e.c_str());
}

// llama.cpp and ggml log through here, to logcat, so a failed load explains itself (`adb logcat -s nmvlm`).
void logCb(enum ggml_log_level level, const char* text, void*) {
  if (level == GGML_LOG_LEVEL_DEBUG) return;
  int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
           : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                           : ANDROID_LOG_INFO;
  __android_log_print(prio, TAG, "%s", text);
}

struct Vlm {
  llama_model* model = nullptr;
  llama_context* ctx = nullptr;
  mtmd_context* mtmd = nullptr;
  const llama_vocab* vocab = nullptr;
  std::string tmpl;  // the model's own chat template, or empty for chatml
  int nCtx = 0;
};

std::string jstr(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

// ⚠ The model writes UTF-8 a token at a time, and one character can span two tokens. A piece cut mid-character
// must wait for the rest before Java sees it, or NewStringUTF gets an invalid sequence.
size_t completeUtf8(const std::string& s) {
  size_t n = s.size(), i = n;
  int back = 0;
  while (i > 0 && back < 4) {
    unsigned char c = static_cast<unsigned char>(s[i - 1]);
    if ((c & 0xC0) != 0x80) {  // a lead byte or ASCII
      int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
      return (n - (i - 1) >= static_cast<size_t>(need)) ? n : i - 1;
    }
    --i;
    ++back;
  }
  return n;
}

// Java strings take modified UTF-8; a 4-byte character (an emoji) must go in as a surrogate pair, so the bytes
// are handed over as a byte array and decoded on the Java side.
jbyteArray bytes(JNIEnv* env, const std::string& s) {
  jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
  if (a) env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
  return a;
}

std::string applyTemplate(const Vlm& v, const std::string& system, const std::string& user) {
  std::vector<llama_chat_message> msgs;
  if (!system.empty()) msgs.push_back({"system", system.c_str()});
  msgs.push_back({"user", user.c_str()});
  const char* tmpl = v.tmpl.empty() ? "chatml" : v.tmpl.c_str();
  std::vector<char> buf(2 * (system.size() + user.size()) + 512);
  int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
  if (n < 0 && !v.tmpl.empty()) {  // a template llama.cpp cannot read: Qwen's own format is chatml
    n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
  }
  if (n < 0) return {};
  if (n > (int32_t)buf.size()) {
    buf.resize(n);
    n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
  }
  return std::string(buf.data(), n);
}

}  // namespace

extern "C" {

// The skel dir first, then the vendor dirs so the platform's own stubs still resolve (nmqnn.cpp's rule).
// ⚠ Must run BEFORE the first backend call: fastrpc reads the variable when it is first loaded.
JNIEXPORT jboolean JNICALL
Java_com_abrah_nightmare_vlm_VlmNative_nativeInit(JNIEnv* env, jclass, jstring skelDir) {
  std::lock_guard<std::mutex> lock(g_mu);
  std::string dir = jstr(env, skelDir);
  std::string adsp = dir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
  LOGI("ADSP_LIBRARY_PATH=%s", adsp.c_str());
  llama_log_set(logCb, nullptr);
  mtmd_helper_log_set(logCb, nullptr);
  llama_backend_init();
  ggml_backend_load_all_from_path(dir.c_str());  // a no-op for a static build; kept for a dynamic one
  for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
    ggml_backend_dev_t d = ggml_backend_dev_get(i);
    LOGI("device %zu: %s (%s)", i, ggml_backend_dev_name(d), ggml_backend_dev_description(d));
  }
  return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_abrah_nightmare_vlm_VlmNative_nativeLastError(JNIEnv* env, jclass) {
  return env->NewStringUTF(g_error.c_str());
}

// Load the language model and its vision projector. `npuLayers`: layers offloaded to the NPU (99 = all, 0 = the
// CPU alone). Returns a handle, or 0 with nativeLastError set.
JNIEXPORT jlong JNICALL
Java_com_abrah_nightmare_vlm_VlmNative_nativeLoad(JNIEnv* env, jclass, jstring modelPath, jstring mmprojPath,
                                                  jint nCtx, jint nThreads, jint npuLayers, jboolean visionOnNpu) {
  std::lock_guard<std::mutex> lock(g_mu);
  g_error.clear();
  auto* v = new Vlm();
  std::string mp = jstr(env, modelPath), pp = jstr(env, mmprojPath);

  llama_model_params mparams = llama_model_default_params();
  mparams.n_gpu_layers = npuLayers;
  v->model = llama_model_load_from_file(mp.c_str(), mparams);
  if (!v->model) {
    setError("could not load the model " + mp);
    delete v;
    return 0;
  }
  v->vocab = llama_model_get_vocab(v->model);
  if (const char* t = llama_model_chat_template(v->model, nullptr)) v->tmpl = t;

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = static_cast<uint32_t>(nCtx);
  cparams.n_batch = 2048;
  cparams.n_threads = nThreads;
  cparams.n_threads_batch = nThreads;
  cparams.type_k = GGML_TYPE_Q8_0;
  cparams.type_v = GGML_TYPE_Q8_0;
  cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
  v->ctx = llama_init_from_model(v->model, cparams);
  if (!v->ctx) {
    setError("could not create a context of " + std::to_string(nCtx) + " tokens");
    llama_model_free(v->model);
    delete v;
    return 0;
  }
  v->nCtx = nCtx;

  mtmd_context_params pparams = mtmd_context_params_default();
  pparams.use_gpu = visionOnNpu == JNI_TRUE;
  pparams.n_threads = nThreads;
  pparams.print_timings = true;
  pparams.warmup = false;
  v->mtmd = mtmd_init_from_file(pp.c_str(), v->model, pparams);
  if (!v->mtmd || !mtmd_support_vision(v->mtmd)) {
    setError("could not load the vision projector " + pp);
    if (v->mtmd) mtmd_free(v->mtmd);
    llama_free(v->ctx);
    llama_model_free(v->model);
    delete v;
    return 0;
  }
  LOGI("loaded %s + %s, ctx %d, npu layers %d, vision on %s", mp.c_str(), pp.c_str(), nCtx, npuLayers,
       pparams.use_gpu ? "npu" : "cpu");
  return reinterpret_cast<jlong>(v);
}

// Describe one picture. `rgb` is w*h*3 bytes. The answer streams through `sink.onText(byte[])`, which returns
// false to stop. Returns the whole answer as UTF-8 bytes, or null with nativeLastError set.
JNIEXPORT jbyteArray JNICALL
Java_com_abrah_nightmare_vlm_VlmNative_nativeDescribe(JNIEnv* env, jclass, jlong handle, jbyteArray rgb, jint w,
                                                      jint h, jstring system, jstring user, jint maxTokens,
                                                      jfloat temp, jint topK, jfloat topP, jint seed, jobject sink) {
  std::lock_guard<std::mutex> lock(g_mu);
  g_error.clear();
  auto* v = reinterpret_cast<Vlm*>(handle);
  if (!v) {
    setError("no model loaded");
    return nullptr;
  }
  jmethodID onText = nullptr;
  if (sink) onText = env->GetMethodID(env->GetObjectClass(sink), "onText", "([B)Z");

  std::vector<unsigned char> px(static_cast<size_t>(w) * h * 3);
  env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(px.size()), reinterpret_cast<jbyte*>(px.data()));
  mtmd_bitmap* bmp = mtmd_bitmap_init(static_cast<uint32_t>(w), static_cast<uint32_t>(h), px.data());

  // The picture goes where the marker is: first in the user's turn, then the instruction.
  std::string userText = std::string(mtmd_default_marker()) + "\n" + jstr(env, user);
  std::string prompt = applyTemplate(*v, jstr(env, system), userText);
  if (prompt.empty()) {
    mtmd_bitmap_free(bmp);
    setError("the chat template failed");
    return nullptr;
  }

  llama_memory_clear(llama_get_memory(v->ctx), true);
  mtmd_input_chunks* chunks = mtmd_input_chunks_init();
  mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
  const mtmd_bitmap* bmps[] = {bmp};
  int32_t rc = mtmd_tokenize(v->mtmd, chunks, &text, bmps, 1);
  mtmd_bitmap_free(bmp);
  if (rc != 0) {
    mtmd_input_chunks_free(chunks);
    setError("tokenize failed (" + std::to_string(rc) + ")");
    return nullptr;
  }
  llama_pos nPast = 0;
  rc = mtmd_helper_eval_chunks(v->mtmd, v->ctx, chunks, 0, 0, 2048, true, &nPast);
  size_t nIn = mtmd_helper_get_n_tokens(chunks);
  mtmd_input_chunks_free(chunks);
  if (rc != 0) {
    setError("reading the picture failed (" + std::to_string(rc) + ")");
    return nullptr;
  }
  LOGI("prompt + picture: %zu tokens, n_past %d", nIn, nPast);

  llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
  // ⚠ top-k first: penalties scan the candidates, and the whole vocabulary is slow (llama.h's own note).
  llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
  llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(v->vocab), 64, 1.05f, 0.0f, 0.0f));
  llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
  llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

  std::string out, pending;
  char piece[256];
  bool stopped = false;
  for (int i = 0; i < maxTokens && nPast < v->nCtx; ++i) {
    llama_token tok = llama_sampler_sample(smpl, v->ctx, -1);
    if (llama_vocab_is_eog(v->vocab, tok)) break;
    int32_t n = llama_token_to_piece(v->vocab, tok, piece, sizeof(piece), 0, false);
    if (n > 0) {
      pending.append(piece, n);
      size_t ok = completeUtf8(pending);
      if (ok > 0) {
        std::string done = pending.substr(0, ok);
        pending.erase(0, ok);
        out += done;
        if (onText) {
          jbyteArray b = bytes(env, done);
          jboolean go = env->CallBooleanMethod(sink, onText, b);
          env->DeleteLocalRef(b);
          if (env->ExceptionCheck() || go == JNI_FALSE) {
            stopped = true;
            break;
          }
        }
      }
    }
    llama_batch batch = llama_batch_get_one(&tok, 1);
    if (llama_decode(v->ctx, batch) != 0) {
      setError("decode failed after " + std::to_string(i) + " tokens");
      break;
    }
    ++nPast;
  }
  out += pending;
  llama_sampler_free(smpl);
  LOGI("answer: %zu bytes%s", out.size(), stopped ? " (stopped)" : "");
  if (!g_error.empty() && out.empty()) return nullptr;
  return bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_abrah_nightmare_vlm_VlmNative_nativeFree(JNIEnv*, jclass, jlong handle) {
  std::lock_guard<std::mutex> lock(g_mu);
  auto* v = reinterpret_cast<Vlm*>(handle);
  if (!v) return;
  if (v->mtmd) mtmd_free(v->mtmd);
  if (v->ctx) llama_free(v->ctx);
  if (v->model) llama_model_free(v->model);
  delete v;
}

}  // extern "C"
