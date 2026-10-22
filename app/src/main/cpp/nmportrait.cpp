// LivePortrait in-process: the face editor and the portrait animator.
//
// ⭐ A port of facefusion-mobile's ffpipe.cpp (7375fe3): its face analysis (yoloface boxes +
// 5 points, 2DFAN4's 68) and its LivePortrait stage (lpCrop, lpMotion, lpFeature, lpRender,
// editFaces, setPortrait, animate), on this library's runner (nmqnn_api.h) instead of ffnn.
// The keypoint maths (portrait/ffportrait) and the geometry (portrait/ffcv) are copied
// unchanged. The graphs are the ones FF hosts per tier: yoloface, fan2d, lp_feature,
// lp_motion, lp_gencut + lp_mlp, each `<name>_<tier>.bin` in one directory.
//
// ⚠ Native end to end, on purpose: a frame's `hg_input` is 7.2 M floats and the feature
// volume 2.1 M, which through JNI FloatArrays would be ~37 MB of copies per frame.
// ⚠ Pixels cross JNI once each way, as ARGB ints (Bitmap.getPixels / setPixels); ffcv
// works in BGR bytes, as FF does.
// ⚠ No content gate: FF's gate belongs to its face swap; Nightmare has none anywhere.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "nmqnn_api.h"
#include "portrait/ffcv.h"
#include "portrait/ffportrait.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "nmportrait", __VA_ARGS__)

namespace {

// FF's Config defaults (ffpipe.h).
constexpr int kDetSize = 640;
constexpr float kDetScore = 0.5f, kLmScore = 0.5f, kNms = 0.4f, kMaskBlur = 0.3f;
// ffhq_512 at 512, the graphs at half that (prepare_crop_frame).
constexpr int kLpCrop = 512, kLpModel = 256;

double nowMs() {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Face {
  float box[4];
  float landmark5[10];
  float landmark68[136];
  float landmark5_68[10];
};

struct Session {
  std::string err;
  nmqnn::Handle det = nullptr, fan = nullptr, feat = nullptr, motion = nullptr, gen = nullptr;
  ffportrait::Mlps mlps;
  std::vector<float> hg;
  ffcv::MatF mask;
  // The animator's portrait (FF's Impl::Portrait).
  struct {
    bool ready = false, haveFirst = false;
    ffcv::Image photo;
    ffcv::Affine am;
    std::vector<float> fv;
    ffportrait::Motion motion, first;
  } P;
  double msLast = 0;

  ~Session() {
    for (nmqnn::Handle h : {det, fan, feat, motion, gen}) if (h) nmqnn::release(h);
  }

  bool fail(const std::string& m) { err = m; return false; }

  bool exec(nmqnn::Handle h, const std::vector<std::string>& names, const std::vector<const float*>& data,
            std::vector<std::vector<float>>* outs, const char* what) {
    if (nmqnn::run(h, names, data, outs)) return true;
    return fail(std::string(what) + ": " + nmqnn::lastError());
  }

  // ---- face analysis: Pipeline::analyse without tracking, recognition or 5->68 lift
  std::vector<Face> analyse(const ffcv::Image& frame) {
    std::vector<Face> faces;
    const int S = kDetSize;
    double scale = 1.0;
    if (frame.h > S || frame.w > S) scale = std::min((double)S / frame.h, (double)S / frame.w);
    int tw = (int)(frame.w * scale), th = (int)(frame.h * scale);
    if (scale >= 1.0) { tw = frame.w; th = frame.h; }
    double ratioW = (double)frame.w / tw, ratioH = (double)frame.h / th;
    std::vector<float> in((size_t)3 * S * S, 0.f);
    ffcv::resizeToCHW(frame, tw, th, S, in.data());
    std::vector<std::vector<float>> out;
    if (!exec(det, {"input"}, {in.data()}, &out, "face detector")) return faces;

    std::vector<std::array<float, 4>> boxes;
    std::vector<float> scores;
    std::vector<std::array<float, 10>> lms;
    const int A = 8400;
    if (out.empty() || out[0].size() < (size_t)20 * A) { fail("face detector: unexpected output"); return faces; }
    const float* d = out[0].data();
    for (int i = 0; i < A; ++i) {
      float sc = d[4 * A + i];
      if (sc <= kDetScore) continue;
      float cx = d[0 * A + i], cy = d[1 * A + i], w = d[2 * A + i], h = d[3 * A + i];
      // FF's floor: a detector that returned noise never reaches the geometry.
      if (!std::isfinite(sc) || !std::isfinite(cx) || !std::isfinite(cy) || !std::isfinite(w) || !std::isfinite(h)) continue;
      if (!(w > 1.0f && h > 1.0f && w <= (float)S && h <= (float)S)) continue;
      if (cx < 0.0f || cy < 0.0f || cx > (float)S || cy > (float)S) continue;
      std::array<float, 10> l{};
      bool ok = true;
      for (int k = 0; k < 5; ++k) {
        float lx = d[(5 + 3 * k + 0) * A + i], ly = d[(5 + 3 * k + 1) * A + i];
        if (!std::isfinite(lx) || !std::isfinite(ly)) { ok = false; break; }
        l[2 * k] = (float)(lx * ratioW);
        l[2 * k + 1] = (float)(ly * ratioH);
      }
      if (!ok) continue;
      boxes.push_back({(float)((cx - w / 2) * ratioW), (float)((cy - h / 2) * ratioH),
                       (float)((cx + w / 2) * ratioW), (float)((cy + h / 2) * ratioH)});
      scores.push_back(sc);
      lms.push_back(l);
    }
    std::vector<int> keep = ffcv::nmsBoxes(boxes, scores, kDetScore, kNms);

    for (int idx : keep) {
      Face f{};
      std::memcpy(f.landmark5, lms[idx].data(), sizeof(f.landmark5));
      for (int i = 0; i < 4; ++i) f.box[i] = boxes[idx][i];
      // The angle from the eye axis (FF without fan_68_5: points 0 and 16 from the eyes).
      float lm68_5[136];
      std::memset(lm68_5, 0, sizeof(lm68_5));
      lm68_5[0] = f.landmark5[0]; lm68_5[1] = f.landmark5[1];
      lm68_5[32] = f.landmark5[2]; lm68_5[33] = f.landmark5[3];
      int angle = ffcv::estimateFaceAngle(lm68_5);

      // 2DFAN4: warp to 256, 68 heatmaps, soft-argmax back.
      const int MS = 256;
      float bw = f.box[2] - f.box[0], bh = f.box[3] - f.box[1];
      float sc2 = 195.0f / std::max(1.0f, std::max(bw, bh));
      float tx = (MS - (f.box[0] + f.box[2]) * sc2) * 0.5f;
      float ty = (MS - (f.box[1] + f.box[3]) * sc2) * 0.5f;
      ffcv::Affine aff;
      aff(0, 0) = sc2; aff(0, 1) = 0; aff(0, 2) = tx;
      aff(1, 0) = 0; aff(1, 1) = sc2; aff(1, 2) = ty;
      ffcv::Image crop = ffcv::warpAffine(frame, aff, MS, MS, ffcv::BORDER_CONSTANT);
      ffcv::Affine rot = ffcv::getRotationMatrix2D(MS / 2.0, MS / 2.0, angle, 1.0);
      if (angle != 0) crop = ffcv::warpAffine(crop, rot, MS, MS, ffcv::BORDER_CONSTANT);
      std::vector<float> fin((size_t)3 * MS * MS);
      for (int y = 0; y < MS; ++y) {
        const uint8_t* row = crop.row(y);
        for (int x = 0; x < MS; ++x)
          for (int c = 0; c < 3; ++c) fin[(size_t)c * MS * MS + (size_t)y * MS + x] = row[x * 3 + c] / 255.0f;
      }
      std::vector<std::vector<float>> hm;
      if (!exec(fan, {"input"}, {fin.data()}, &hm, "face landmarker")) return {};
      // ⚠ Sized before it is read: decodeHeatmaps walks 68x64x64 floats, and a landmarker graph of
      // another shape was an out-of-bounds read rather than an error.
      if (hm.empty() || hm[0].size() < (size_t)68 * 64 * 64) { fail("face landmarker: unexpected output"); return {}; }
      float xy[136], peak[68];
      ffcv::decodeHeatmaps(hm[0].data(), 68, 64, 64, xy, peak);
      for (int k = 0; k < 68; ++k) { xy[2 * k] = xy[2 * k] / 64.f * MS; xy[2 * k + 1] = xy[2 * k + 1] / 64.f * MS; }
      if (angle != 0) {
        ffcv::Affine ir = ffcv::invertAffine(rot);
        float tmp[136];
        ffcv::transformPoints(xy, 68, ir, tmp);
        std::memcpy(xy, tmp, sizeof(xy));
      }
      ffcv::transformPoints(xy, 68, ffcv::invertAffine(aff), f.landmark68);
      double meanPeak = 0;
      for (int k = 0; k < 68; ++k) meanPeak += peak[k];
      meanPeak /= 68.0;
      float score68 = (float)std::min(1.0, std::max(0.0, meanPeak / 0.9));
      if (score68 > kLmScore) ffcv::toLandmark5(f.landmark68, f.landmark5_68);
      else std::memcpy(f.landmark5_68, f.landmark5, sizeof(f.landmark5_68));
      faces.push_back(f);
    }
    return faces;
  }

  // ---- LivePortrait: FF's lpCrop / lpMotion / lpFeature / lpRender
  void lpCrop(const ffcv::Image& frame, const Face& f, ffcv::Affine* am, std::vector<float>* in) {
    float tmpl[10], lm5[10];
    const float* T = ffcv::warpTemplate(2);  // ffhq_512
    for (int i = 0; i < 10; ++i) tmpl[i] = T[i] * kLpCrop;
    const float nx = f.landmark5_68[4], ny = f.landmark5_68[5];
    for (int i = 0; i < 5; ++i) {
      lm5[i * 2] = (f.landmark5_68[i * 2] - nx) * 1.5f + nx;
      lm5[i * 2 + 1] = (f.landmark5_68[i * 2 + 1] - ny) * 1.5f + ny;
    }
    *am = ffcv::umeyama(lm5, tmpl, 5);
    ffcv::Image crop = ffcv::warpAffine(frame, *am, kLpCrop, kLpCrop, ffcv::BORDER_REPLICATE);
    ffcv::Image small = ffcv::resizeLinear(crop, kLpModel, kLpModel);
    in->resize((size_t)3 * kLpModel * kLpModel);
    for (int y = 0; y < kLpModel; ++y) {
      const uint8_t* row = small.row(y);
      for (int x = 0; x < kLpModel; ++x)
        for (int c = 0; c < 3; ++c)  // BGR -> RGB planes, / 255
          (*in)[(size_t)(2 - c) * kLpModel * kLpModel + (size_t)y * kLpModel + x] = row[x * 3 + c] / 255.0f;
    }
  }

  bool lpMotion(const std::vector<float>& in, ffportrait::Motion* m) {
    std::vector<std::vector<float>> mo;
    if (!exec(motion, {"input"}, {in.data()}, &mo, "LivePortrait motion")) return false;
    const std::vector<std::string> names = nmqnn::outputNames(motion);
    bool complete = names.size() == mo.size();
    auto take = [&](const char* name, float* dst, size_t n) {
      for (size_t i = 0; i < names.size(); ++i)
        if (names[i] == name && mo[i].size() == n) { std::copy(mo[i].begin(), mo[i].end(), dst); return; }
      complete = false;
    };
    constexpr size_t K = ffportrait::kKeypoints * 3;
    take("pitch", &m->pitch, 1);
    take("yaw", &m->yaw, 1);
    take("roll", &m->roll, 1);
    take("scale", &m->scale, 1);
    take("translation", m->translation, 3);
    take("expression", m->expression, K);
    take("motion_points", m->points, K);
    return complete || fail("LivePortrait motion: outputs do not match");
  }

  bool lpFeature(const std::vector<float>& in, std::vector<float>* fv) {
    std::vector<std::vector<float>> fo;
    if (!exec(feat, {"input"}, {in.data()}, &fo, "LivePortrait features")) return false;
    if (fo.empty() || fo[0].size() != (size_t)ffportrait::kFeatureChannels * ffportrait::kVoxels)
      return fail("LivePortrait features: unexpected output");
    fv->swap(fo[0]);
    return true;
  }

  bool lpRender(const std::vector<float>& fv, const float* source, const float* target, const ffcv::Affine& am,
                ffcv::Image& frame) {
    hg.resize((size_t)ffportrait::kVoxels * ffportrait::kHourglassChannels);
    ffportrait::hourglassInput(fv.data(), source, target, hg.data());
    std::vector<std::vector<float>> go;
    if (!exec(gen, {"feature_volume", "source", "target", "hg_input"}, {fv.data(), source, target, hg.data()}, &go,
              "LivePortrait generator"))
      return false;
    const std::vector<std::string> genNames = nmqnn::outputNames(gen);
    const std::vector<float>* g = nullptr;
    for (size_t i = 0; i < genNames.size() && i < go.size(); ++i)
      if (genNames[i] == "output") g = &go[i];
    if (!g || g->size() != (size_t)3 * kLpCrop * kLpCrop) return fail("LivePortrait generator: unexpected output");
    ffcv::MatF out(kLpCrop, kLpCrop, 3);
    for (int y = 0; y < kLpCrop; ++y) {
      float* orow = out.row(y);
      for (int x = 0; x < kLpCrop; ++x)
        for (int c = 0; c < 3; ++c) {
          float v = (*g)[(size_t)c * kLpCrop * kLpCrop + (size_t)y * kLpCrop + x];
          v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
          orow[x * 3 + (2 - c)] = std::floor(v * 255.0f);
        }
    }
    ffcv::pasteBack(frame, out, mask, am);
    return true;
  }

  static const Face* largest(const std::vector<Face>& faces) {
    const Face* best = nullptr;
    float area = -1.f;
    for (const Face& f : faces) {
      const float a = (f.box[2] - f.box[0]) * (f.box[3] - f.box[1]);
      if (a > area) { area = a; best = &f; }
    }
    return best;
  }

  // FF's editFaces. ⚠ Returns the count edited: 0 = no face found, -1 = failure (err).
  int edit(ffcv::Image& frame, const float sliders[ffportrait::kSliderCount], bool largestOnly) {
    std::vector<Face> faces = analyse(frame);
    if (!err.empty()) return -1;
    const Face* only = largestOnly ? largest(faces) : nullptr;
    int n = 0;
    std::vector<float> in, fv;
    for (const Face& f : faces) {
      if (only && &f != only) continue;
      ffcv::Affine am;
      lpCrop(frame, f, &am, &in);
      ffportrait::Motion m;
      if (!lpFeature(in, &fv) || !lpMotion(in, &m)) return -1;
      float source[ffportrait::kKeypoints * 3], target[ffportrait::kKeypoints * 3];
      ffportrait::editKeypoints(m, sliders, f.landmark68, mlps, source, target);
      if (!lpRender(fv, source, target, am, frame)) return -1;
      ++n;
    }
    return n;
  }

  bool setPortrait(const ffcv::Image& photo) {
    P = decltype(P)();
    std::vector<Face> faces = analyse(photo);
    if (!err.empty()) return false;
    const Face* best = largest(faces);
    if (!best) return fail("no face found in the photo");
    std::vector<float> in;
    lpCrop(photo, *best, &P.am, &in);
    if (!lpFeature(in, &P.fv) || !lpMotion(in, &P.motion)) return false;
    P.photo = photo;
    P.ready = true;
    return true;
  }

  // FF's animate: the photo's face moved like the driver's, relative to the driver's FIRST face.
  bool animate(const ffcv::Image& driver, ffcv::Image& out) {
    if (!P.ready) return fail("no portrait set");
    out = P.photo;
    std::vector<Face> faces = analyse(driver);
    if (!err.empty()) return false;
    const Face* best = largest(faces);
    if (!best) return true;  // a frame with no face leaves the photo as it is
    std::vector<float> in;
    ffcv::Affine dam;
    lpCrop(driver, *best, &dam, &in);
    ffportrait::Motion d;
    if (!lpMotion(in, &d)) return false;
    if (!P.haveFirst) { P.first = d; P.haveFirst = true; }
    float source[ffportrait::kKeypoints * 3], target[ffportrait::kKeypoints * 3];
    ffportrait::animateKeypoints(P.motion, P.first, d, mlps, source, target);
    return lpRender(P.fv, source, target, P.am, out);
  }
};

std::mutex g_mu;
std::unique_ptr<Session> g_s;
std::string g_err;

// ⚠ Every JNI entry checks this before [fromArgb], which trusts w*h: a caller whose array is
// shorter than the size it claims was an out-of-bounds read of the Java heap.
bool argbFits(JNIEnv* env, jintArray px, int w, int h) {
  if (!px || w <= 0 || h <= 0) { g_err = "bad picture size"; return false; }
  if ((long long)env->GetArrayLength(px) < (long long)w * h) { g_err = "picture pixels shorter than its size"; return false; }
  return true;
}

ffcv::Image fromArgb(JNIEnv* env, jintArray px, int w, int h) {
  ffcv::Image img(w, h, 3);
  jint* p = env->GetIntArrayElements(px, nullptr);
  for (int y = 0; y < h; ++y) {
    uint8_t* row = img.row(y);
    for (int x = 0; x < w; ++x) {
      uint32_t v = (uint32_t)p[(size_t)y * w + x];
      row[x * 3 + 0] = (uint8_t)(v & 0xFF);          // B
      row[x * 3 + 1] = (uint8_t)((v >> 8) & 0xFF);   // G
      row[x * 3 + 2] = (uint8_t)((v >> 16) & 0xFF);  // R
    }
  }
  env->ReleaseIntArrayElements(px, p, JNI_ABORT);
  return img;
}

jintArray toArgb(JNIEnv* env, const ffcv::Image& img) {
  std::vector<jint> out((size_t)img.w * img.h);
  for (int y = 0; y < img.h; ++y) {
    const uint8_t* row = img.row(y);
    for (int x = 0; x < img.w; ++x)
      out[(size_t)y * img.w + x] =
          (jint)(0xFF000000u | ((uint32_t)row[x * 3 + 2] << 16) | ((uint32_t)row[x * 3 + 1] << 8) | row[x * 3 + 0]);
  }
  jintArray a = env->NewIntArray((jsize)out.size());
  env->SetIntArrayRegion(a, 0, (jsize)out.size(), out.data());
  return a;
}

std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

}  // namespace

extern "C" {

/** Load the six files of [tier] from [dir] (NativeQnn.init first). False: lastError. */
JNIEXPORT jboolean JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_open(JNIEnv* env, jclass, jstring jDir, jstring jTier) {
  std::lock_guard<std::mutex> lk(g_mu);
  const std::string dir = str(env, jDir), tier = str(env, jTier);
  auto s = std::make_unique<Session>();
  auto open = [&](const char* name) -> nmqnn::Handle {
    nmqnn::Handle h = nmqnn::load(dir + "/" + name + "_" + tier + ".bin");
    if (!h) s->err = std::string("open ") + name + " (" + tier + "): " + nmqnn::lastError();
    return h;
  };
  if (!(s->det = open("yoloface")) || !(s->fan = open("fan2d")) || !(s->feat = open("lp_feature")) ||
      !(s->motion = open("lp_motion")) || !(s->gen = open("lp_gencut"))) {
    g_err = s->err;
    return JNI_FALSE;
  }
  std::string why;
  if (!s->mlps.load(dir + "/lp_mlp_" + tier + ".bin", &why)) {
    g_err = "lp_mlp (" + tier + "): " + why;
    return JNI_FALSE;
  }
  const int noPadding[4] = {0, 0, 0, 0};
  s->mask = ffcv::createBoxMask(kLpCrop, kLpCrop, kMaskBlur, noPadding);
  g_s = std::move(s);
  g_err.clear();
  LOGI("LivePortrait %s open from %s", tier.c_str(), dir.c_str());
  return JNI_TRUE;
}

/**
 * The 14 sliders on every face (or the largest), in place. Returns the edited ARGB pixels, or null:
 * lastError says why ("no face found" is an error here, so the caller can say it).
 */
JNIEXPORT jintArray JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_edit(JNIEnv* env, jclass, jintArray px, jint w, jint h,
                                                      jfloatArray jSliders, jboolean largestOnly) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (!g_s) { g_err = "LivePortrait is not open"; return nullptr; }
  if (env->GetArrayLength(jSliders) != ffportrait::kSliderCount) { g_err = "expected 14 sliders"; return nullptr; }
  float sl[ffportrait::kSliderCount];
  env->GetFloatArrayRegion(jSliders, 0, ffportrait::kSliderCount, sl);
  for (float& v : sl) v = std::max(-1.f, std::min(1.f, v));
  if (!argbFits(env, px, w, h)) return nullptr;
  ffcv::Image img = fromArgb(env, px, w, h);
  g_s->err.clear();
  double t0 = nowMs();
  int n = g_s->edit(img, sl, largestOnly == JNI_TRUE);
  g_s->msLast = nowMs() - t0;
  if (n < 0) { g_err = g_s->err; return nullptr; }
  if (n == 0) { g_err = "no face found"; return nullptr; }
  LOGI("edited %d face(s) at %dx%d in %.0f ms", n, w, h, g_s->msLast);
  return toArgb(env, img);
}

JNIEXPORT jboolean JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_setPortrait(JNIEnv* env, jclass, jintArray px, jint w, jint h) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (!g_s) { g_err = "LivePortrait is not open"; return JNI_FALSE; }
  g_s->err.clear();
  if (!argbFits(env, px, w, h)) return JNI_FALSE;
  bool ok = g_s->setPortrait(fromArgb(env, px, w, h));
  if (!ok) g_err = g_s->err;
  return ok ? JNI_TRUE : JNI_FALSE;
}

/** One driver frame → the portrait moved like it, at the portrait's size. Null: lastError. */
JNIEXPORT jintArray JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_animate(JNIEnv* env, jclass, jintArray px, jint w, jint h) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (!g_s) { g_err = "LivePortrait is not open"; return nullptr; }
  g_s->err.clear();
  ffcv::Image out;
  if (!argbFits(env, px, w, h)) return nullptr;
  double t0 = nowMs();
  if (!g_s->animate(fromArgb(env, px, w, h), out)) { g_err = g_s->err; return nullptr; }
  g_s->msLast = nowMs() - t0;
  return toArgb(env, out);
}

/** Milliseconds the last edit or frame took. */
JNIEXPORT jdouble JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_lastMs(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lk(g_mu);
  return g_s ? g_s->msLast : 0.0;
}

JNIEXPORT jstring JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_lastError(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lk(g_mu);
  return env->NewStringUTF(g_err.c_str());
}

/** Frees the five contexts (~300 MB resident). */
JNIEXPORT void JNICALL
Java_com_abrah_nightmare_portrait_NativePortrait_close(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lk(g_mu);
  g_s.reset();
}

}  // extern "C"
