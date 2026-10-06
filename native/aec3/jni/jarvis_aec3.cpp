// Narrow JNI wrapper over the standalone WebRTC AudioProcessing (AEC3) from the pinned
// pulseaudio/webrtc-audio-processing v2.1 source. Exposes ONLY: create, processReverse
// (far-end render), processNear (near-end capture), destroy. No audio is stored, no I/O,
// no network. All blocks are exactly 10 ms. Float samples use WebRTC's S16 range.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>

#include "api/audio/audio_processing.h"

namespace {

constexpr int kMaxBlock = 480;  // 10 ms @ 48 kHz

struct Aec {
  decltype(webrtc::AudioProcessingBuilder().Create()) apm;
  webrtc::StreamConfig nearCfg;
  webrtc::StreamConfig farCfg;
  int nearBlock;
  int farBlock;
};

inline float clampS16(float v) {
  return std::min(32767.0f, std::max(-32768.0f, v));
}

}  // namespace

extern "C" {

// Returns an opaque handle, or 0 on failure.
JNIEXPORT jlong JNICALL Java_com_simone_jarvismobile_voice_aec_NativeAec3_nativeCreate(
    JNIEnv*, jclass, jint nearRateHz, jint farRateHz) {
  if (nearRateHz < 8000 || farRateHz < 8000) return 0;
  if (nearRateHz % 100 != 0 || farRateHz % 100 != 0) return 0;  // exact 10 ms blocks only
  if (nearRateHz / 100 > kMaxBlock || farRateHz / 100 > kMaxBlock) return 0;
  auto apm = webrtc::AudioProcessingBuilder().Create();
  if (!apm) return 0;
  webrtc::AudioProcessing::Config cfg;
  cfg.echo_canceller.enabled = true;
  cfg.echo_canceller.mobile_mode = false;
  cfg.high_pass_filter.enabled = true;
  cfg.noise_suppression.enabled = false;
  cfg.gain_controller1.enabled = false;
  cfg.gain_controller2.enabled = false;
  apm->ApplyConfig(cfg);
  Aec* a = new Aec{apm, webrtc::StreamConfig(nearRateHz, 1), webrtc::StreamConfig(farRateHz, 1),
                   nearRateHz / 100, farRateHz / 100};
  return reinterpret_cast<jlong>(a);
}

// Far-end/render block of exactly farRate/100 float samples in [-1,1]. Returns 0 on success.
JNIEXPORT jint JNICALL Java_com_simone_jarvismobile_voice_aec_NativeAec3_nativeProcessReverse(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples) {
  Aec* a = reinterpret_cast<Aec*>(handle);
  if (a == nullptr || samples == nullptr) return -1;
  if (env->GetArrayLength(samples) != a->farBlock) return -2;
  float in[kMaxBlock];
  float out[kMaxBlock];
  env->GetFloatArrayRegion(samples, 0, a->farBlock, in);
  for (int i = 0; i < a->farBlock; ++i) in[i] = clampS16(in[i] * 32768.0f);
  const float* src[1] = {in};
  float* dst[1] = {out};
  return a->apm->ProcessReverseStream(src, a->farCfg, a->farCfg, dst);
}

// Near-end/capture block of exactly nearRate/100 PCM16 samples; cleaned samples in `out`.
JNIEXPORT jint JNICALL Java_com_simone_jarvismobile_voice_aec_NativeAec3_nativeProcessNear(
    JNIEnv* env, jclass, jlong handle, jshortArray inArr, jshortArray outArr) {
  Aec* a = reinterpret_cast<Aec*>(handle);
  if (a == nullptr || inArr == nullptr || outArr == nullptr) return -1;
  if (env->GetArrayLength(inArr) != a->nearBlock || env->GetArrayLength(outArr) != a->nearBlock)
    return -2;
  jshort s16[kMaxBlock];
  float in[kMaxBlock];
  float out[kMaxBlock];
  env->GetShortArrayRegion(inArr, 0, a->nearBlock, s16);
  for (int i = 0; i < a->nearBlock; ++i) in[i] = static_cast<float>(s16[i]);
  const float* src[1] = {in};
  float* dst[1] = {out};
  int rc = a->apm->ProcessStream(src, a->nearCfg, a->nearCfg, dst);
  if (rc != 0) return rc;
  for (int i = 0; i < a->nearBlock; ++i)
    s16[i] = static_cast<jshort>(std::lrintf(clampS16(out[i])));
  env->SetShortArrayRegion(outArr, 0, a->nearBlock, s16);
  return 0;
}

JNIEXPORT void JNICALL Java_com_simone_jarvismobile_voice_aec_NativeAec3_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<Aec*>(handle);
}

}  // extern "C"
