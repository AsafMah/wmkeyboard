// JNI surface for the voice activity detectors that do not go through
// sherpa-onnx's own: FireRedVAD's streaming model, run straight on ONNX
// Runtime over kaldi-native-fbank features, and a probe that says whether a
// file the user imported is a Silero or TEN model sherpa-onnx will take.
// Kotlin side: com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
// (nativeVadProbe, nativeFireRed*), driven by core.voice.vad.
//
// FireRedVAD (Xiaohongshu's FireRedTeam, Apache-2.0) is a DFSMN over 80-band
// log-mel filterbanks, 25 ms frames every 10 ms, Kaldi style: samples at
// 16-bit scale, no dither, edges snipped, then CMVN from the model's
// cmvn.ark (read on the Kotlin side). The export used is upstream's own
// fireredvad_stream_vad_with_cache.onnx: `feat` (1, T, 80) and `caches_in`
// (8, 1, 128, 19) in, `probs` (1, T, 1) and `caches_out` of the same shape out.
// Fed in pieces with the caches carried, it gives the same probabilities as
// the whole clip at once (checked to the bit on random features), so the
// microphone's chunks go straight in.
//
// Same rules as omnilingual_jni.cpp: ONNX Runtime is found by soname at load
// time, and nothing here is thread-safe; a handle belongs to one thread.

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <initializer_list>
#include <memory>
#include <string>
#include <vector>

#include "kaldi-native-fbank/csrc/online-feature.h"
#include "onnxruntime_cxx_api.h"

namespace {

Ort::Env& env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-vad");
    return e;
}

std::string utf(JNIEnv* jni, jstring s) {
    if (!s) return {};
    const char* c = jni->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) jni->ReleaseStringUTFChars(s, c);
    return out;
}

// ---- Probe ---------------------------------------------------------------

// What nativeVadProbe says about a file: the kinds sherpa_jni.cpp's newVad
// takes, or neither.
constexpr jint kUnknown = -1;
constexpr jint kSilero = 0;
constexpr jint kTen = 1;

std::vector<std::string> names(Ort::Session& session, bool inputs) {
    Ort::AllocatorWithDefaultOptions allocator;
    std::vector<std::string> out;
    const size_t n = inputs ? session.GetInputCount() : session.GetOutputCount();
    for (size_t i = 0; i < n; ++i) {
        out.emplace_back(inputs ? session.GetInputNameAllocated(i, allocator).get()
                                : session.GetOutputNameAllocated(i, allocator).get());
    }
    return out;
}

// How many comma-separated numbers [text] holds, or -1 when one is not a number.
int countFloats(const std::string& text) {
    if (text.empty()) return 0;
    int count = 0;
    size_t at = 0;
    while (at <= text.size()) {
        size_t end = text.find(',', at);
        if (end == std::string::npos) end = text.size();
        const std::string part = text.substr(at, end - at);
        char* stop = nullptr;
        std::strtof(part.c_str(), &stop);
        if (part.empty() || stop == part.c_str()) return -1;
        ++count;
        at = end + 1;
    }
    return count;
}

// Exactly the checks sherpa-onnx v1.13.8 makes before it exits on a model
// (silero-vad-model.cc Init/CheckV4/CheckV5, ten-vad-model.cc Check), so a
// file that passes here never takes the keyboard down there.
jint probe(const std::string& path) {
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(1);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_DISABLE_ALL);
    Ort::Session session(env(), path.c_str(), options);
    const std::vector<std::string> in = names(session, true), out = names(session, false);
    const auto is = [](const std::vector<std::string>& got, std::initializer_list<const char*> want) {
        if (got.size() != want.size()) return false;
        size_t i = 0;
        for (const char* w : want) {
            if (got[i++] != w) return false;
        }
        return true;
    };
    // Silero v4 as exported by k2-fsa, v4 as published, and v5.
    if (is(in, {"x", "h", "c"}) && is(out, {"prob", "new_h", "new_c"})) return kSilero;
    if (is(in, {"input", "sr", "h", "c"}) && is(out, {"output", "hn", "cn"})) return kSilero;
    if (is(in, {"input", "state", "sr"}) && is(out, {"output", "stateN"})) return kSilero;
    // TEN, with the metadata sherpa-onnx added to its release of the model.
    Ort::ModelMetadata meta = session.GetModelMetadata();
    Ort::AllocatorWithDefaultOptions allocator;
    const auto entry = [&](const char* key) -> std::string {
        auto value = meta.LookupCustomMetadataMapAllocated(key, allocator);
        return value ? std::string(value.get()) : std::string();
    };
    if (entry("model_type") == "ten-vad" && countFloats(entry("mean")) == 41 &&
        countFloats(entry("inv_stddev")) == 41 && countFloats(entry("window")) == 768) {
        return kTen;
    }
    return kUnknown;
}

// ---- FireRedVAD ----------------------------------------------------------

constexpr int kBands = 80;

struct FireRed {
    std::unique_ptr<Ort::Session> session;
    knf::FbankOptions options;
    std::unique_ptr<knf::OnlineFbank> fbank;
    std::vector<float> mean, scale;  // CMVN, one per band
    std::vector<int64_t> cacheShape;
    std::vector<float> caches;
    int32_t taken = 0;  // frames already through the model
};

FireRed* fireRed(jlong h) { return reinterpret_cast<FireRed*>(h); }

void restart(FireRed* f) {
    f->fbank = std::make_unique<knf::OnlineFbank>(f->options);
    std::fill(f->caches.begin(), f->caches.end(), 0.0f);
    f->taken = 0;
}

}  // namespace

extern "C" {

// Which sherpa-onnx detector the file at [model] is: 0 Silero, 1 TEN, -1
// neither (or not a model at all).
JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadProbe(JNIEnv* jni, jclass, jstring model) {
    const std::string path = utf(jni, model);
    if (path.empty()) return kUnknown;
    try {
        return probe(path);
    } catch (...) {
        return kUnknown;
    }
}

// Opens FireRedVAD's streaming model with its CMVN statistics ([mean] and
// [scale], the inverse standard deviation, one per band); 0 when it will not
// load or is not the export this expects.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFireRed(
    JNIEnv* jni, jclass, jstring model, jfloatArray mean, jfloatArray scale, jint threads) {
    const std::string path = utf(jni, model);
    if (path.empty() || !mean || !scale) return 0;
    if (jni->GetArrayLength(mean) != kBands || jni->GetArrayLength(scale) != kBands) return 0;
    try {
        auto f = std::make_unique<FireRed>();
        f->mean.resize(kBands);
        f->scale.resize(kBands);
        jni->GetFloatArrayRegion(mean, 0, kBands, f->mean.data());
        jni->GetFloatArrayRegion(scale, 0, kBands, f->scale.data());
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        f->session = std::make_unique<Ort::Session>(env(), path.c_str(), options);
        const std::vector<std::string> in = names(*f->session, true), out = names(*f->session, false);
        if (in.size() != 2 || in[0] != "feat" || in[1] != "caches_in") return 0;
        if (out.size() != 2 || out[0] != "probs" || out[1] != "caches_out") return 0;
        f->cacheShape = f->session->GetInputTypeInfo(1).GetTensorTypeAndShapeInfo().GetShape();
        size_t cells = 1;
        for (int64_t d : f->cacheShape) {
            if (d <= 0) return 0;
            cells *= static_cast<size_t>(d);
        }
        f->caches.assign(cells, 0.0f);
        f->options.frame_opts.samp_freq = 16000;
        f->options.frame_opts.frame_length_ms = 25;
        f->options.frame_opts.frame_shift_ms = 10;
        f->options.frame_opts.dither = 0;
        f->options.frame_opts.snip_edges = true;
        f->options.mel_opts.num_bins = kBands;
        f->options.mel_opts.debug_mel = false;
        restart(f.get());
        return reinterpret_cast<jlong>(f.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFireRedFree(JNIEnv*, jclass, jlong h) {
    delete fireRed(h);
}

// [n] samples (16 kHz, [-1, 1]) in; the speech probability of each 10 ms
// frame they complete out, oldest first. Empty while no frame is complete,
// and when the run fails.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFireRedFeed(
    JNIEnv* jni, jclass, jlong h, jfloatArray samples, jint n) {
    FireRed* f = fireRed(h);
    std::vector<float> probs;
    if (f && n > 0) {
        std::vector<float> x(static_cast<size_t>(n));
        jni->GetFloatArrayRegion(samples, 0, n, x.data());
        // Kaldi's features are on the 16-bit scale the model was trained on.
        for (float& v : x) v *= 32768.0f;
        try {
            f->fbank->AcceptWaveform(16000, x.data(), n);
            const int32_t ready = f->fbank->NumFramesReady();
            const int32_t frames = ready - f->taken;
            if (frames > 0) {
                std::vector<float> feat(static_cast<size_t>(frames) * kBands);
                for (int32_t t = 0; t < frames; ++t) {
                    const float* row = f->fbank->GetFrame(f->taken + t);
                    for (int b = 0; b < kBands; ++b) {
                        feat[static_cast<size_t>(t) * kBands + b] = (row[b] - f->mean[b]) * f->scale[b];
                    }
                }
                // GetFrame counts from the start of the clip; what has been
                // read can go.
                f->fbank->Pop(frames);
                f->taken = ready;
                auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
                const std::array<int64_t, 3> featShape = {1, frames, kBands};
                std::array<Ort::Value, 2> inputs = {
                    Ort::Value::CreateTensor<float>(memory, feat.data(), feat.size(), featShape.data(), featShape.size()),
                    Ort::Value::CreateTensor<float>(memory, f->caches.data(), f->caches.size(), f->cacheShape.data(),
                                                    f->cacheShape.size()),
                };
                const char* in[] = {"feat", "caches_in"};
                const char* out[] = {"probs", "caches_out"};
                auto result = f->session->Run(Ort::RunOptions{nullptr}, in, inputs.data(), 2, out, 2);
                const size_t got = result[0].GetTensorTypeAndShapeInfo().GetElementCount();
                const float* p = result[0].GetTensorData<float>();
                probs.assign(p, p + got);
                if (result[1].GetTensorTypeAndShapeInfo().GetElementCount() == f->caches.size()) {
                    const float* c = result[1].GetTensorData<float>();
                    std::copy(c, c + f->caches.size(), f->caches.begin());
                }
            }
        } catch (...) {
            probs.clear();
        }
    }
    jfloatArray result = jni->NewFloatArray(static_cast<jsize>(probs.size()));
    if (result && !probs.empty()) {
        jni->SetFloatArrayRegion(result, 0, static_cast<jsize>(probs.size()), probs.data());
    }
    return result;
}

// Forgets the clip so far: features, caches, all of it.
JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFireRedReset(JNIEnv*, jclass, jlong h) {
    if (FireRed* f = fireRed(h)) restart(f);
}

}  // extern "C"
