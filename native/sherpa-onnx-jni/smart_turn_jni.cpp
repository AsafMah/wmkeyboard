// JNI surface for Smart Turn v3 (pipecat-ai, BSD-2-Clause), run straight on
// ONNX Runtime: given the last 8 seconds of a dictation, the chance that the
// speaker has finished what they were saying, rather than paused mid-thought.
// Kotlin side: com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
// (nativeSmartTurn*), driven by core.voice.vad.SmartTurn.
//
// The model is a Whisper-tiny encoder with a classifier head. It reads
// `input_features` (1, 80, 800) and returns the probability (1, 1), already
// through its sigmoid. The features follow pipecat's own preprocessing
// (pipecat/audio/turn/smart_turn/_whisper_features.py, which mirrors
// transformers' WhisperFeatureExtractor(chunk_length=8)):
//
//   the last 8 s of audio, zeros in front when there is less;
//   the waveform to zero mean and unit variance;
//   a centred STFT (reflect padding), 400-sample periodic Hann, hop 160;
//   power through 80 Slaney-normalised mel filters, 0 to 8 kHz;
//   log10 with a 1e-10 floor, the last frame dropped, clamped to 8 decades
//   below the peak and scaled as (x + 4) / 4.
//
// Same rules as omnilingual_jni.cpp: ONNX Runtime is found by soname at load
// time. A session runs one call at a time; the Kotlin side keeps it so.

#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

constexpr int kRate = 16000;
constexpr int kSamples = kRate * 8;
constexpr int kFft = 400;
constexpr int kHop = 160;
constexpr int kBins = kFft / 2 + 1;
constexpr int kMels = 80;
constexpr int kFrames = kSamples / kHop;  // 800, after the last of 801 is dropped

using cd = std::complex<double>;

Ort::Env& env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-turn");
    return e;
}

double hzToMel(double hz) {
    // Slaney: linear below 1 kHz, logarithmic above.
    if (hz < 1000.0) return 3.0 * hz / 200.0;
    return 15.0 + std::log(hz / 1000.0) * (27.0 / std::log(6.4));
}

double melToHz(double mel) {
    if (mel < 15.0) return 200.0 * mel / 3.0;
    return 1000.0 * std::exp((std::log(6.4) / 27.0) * (mel - 15.0));
}

struct SmartTurn {
    std::unique_ptr<Ort::Session> session;
    std::string input, output;
    std::vector<double> window;   // kFft
    std::vector<double> filters;  // kMels * kBins, mel-major
    std::vector<cd> twiddle;      // e^(-2 pi i k / kFft)
};

SmartTurn* turn(jlong h) { return reinterpret_cast<SmartTurn*>(h); }

void buildTables(SmartTurn* s) {
    s->window.resize(kFft);
    for (int i = 0; i < kFft; ++i) s->window[i] = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / kFft);
    s->twiddle.resize(kFft);
    for (int k = 0; k < kFft; ++k) s->twiddle[k] = std::polar(1.0, -2.0 * M_PI * k / kFft);
    // Triangles between kMels + 2 points evenly spaced in mels, each scaled
    // to unit area (Slaney's normalisation), sampled at the FFT's bins.
    std::array<double, kMels + 2> edges{};
    const double lo = hzToMel(0.0), hi = hzToMel(kRate / 2.0);
    for (int m = 0; m < kMels + 2; ++m) edges[m] = melToHz(lo + (hi - lo) * m / (kMels + 1));
    s->filters.assign(static_cast<size_t>(kMels) * kBins, 0.0);
    for (int m = 0; m < kMels; ++m) {
        const double enorm = 2.0 / (edges[m + 2] - edges[m]);
        for (int k = 0; k < kBins; ++k) {
            const double f = (kRate / 2.0) * k / (kBins - 1);
            const double down = (f - edges[m]) / (edges[m + 1] - edges[m]);
            const double up = (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1]);
            s->filters[static_cast<size_t>(m) * kBins + k] = std::max(0.0, std::min(down, up)) * enorm;
        }
    }
}

// Out-of-place FFT of the [n] values x[0], x[stride], ... into out[0..n):
// halves while it can, and finishes with a plain DFT on the odd part
// (400 = 16 x 25). [twiddle] is for the full size, so index * stride reads
// the size at this level.
void fft(const cd* x, int n, int stride, cd* out, const std::vector<cd>& twiddle) {
    if (n % 2 == 1) {
        for (int k = 0; k < n; ++k) {
            cd sum = 0;
            for (int j = 0; j < n; ++j) sum += x[j * stride] * twiddle[(static_cast<long>(k) * j * stride) % kFft];
            out[k] = sum;
        }
        return;
    }
    const int half = n / 2;
    fft(x, half, stride * 2, out, twiddle);
    fft(x + stride, half, stride * 2, out + half, twiddle);
    for (int k = 0; k < half; ++k) {
        const cd e = out[k];
        const cd o = out[k + half] * twiddle[static_cast<size_t>(k) * stride];
        out[k] = e + o;
        out[k + half] = e - o;
    }
}

// The model's input for [audio] (its last kSamples, zeros in front of less),
// (kMels, kFrames) mel-major.
std::vector<float> features(const SmartTurn& s, const float* audio, int n) {
    std::vector<float> x(kSamples, 0.0f);
    const int keep = std::min(n, kSamples);
    std::copy(audio + (n - keep), audio + n, x.begin() + (kSamples - keep));
    double sum = 0;
    for (float v : x) sum += v;
    const double mean = sum / kSamples;
    double squares = 0;
    for (float v : x) squares += (v - mean) * (v - mean);
    const double norm = 1.0 / std::sqrt(squares / kSamples + 1e-7);
    std::vector<double> y(kSamples);
    for (int i = 0; i < kSamples; ++i) y[i] = static_cast<float>((x[i] - mean) * norm);

    // Centred frames: kFft / 2 reflected on each side.
    const int pad = kFft / 2;
    const auto at = [&](int i) {
        int j = i - pad;
        if (j < 0) j = -j;
        if (j >= kSamples) j = 2 * (kSamples - 1) - j;
        return y[static_cast<size_t>(j)];
    };
    std::vector<double> mel(static_cast<size_t>(kMels) * kFrames);
    std::vector<cd> frame(kFft), spectrum(kFft);
    std::array<double, kBins> power{};
    for (int t = 0; t < kFrames; ++t) {
        for (int i = 0; i < kFft; ++i) frame[i] = at(t * kHop + i) * s.window[i];
        fft(frame.data(), kFft, 1, spectrum.data(), s.twiddle);
        for (int k = 0; k < kBins; ++k) power[k] = std::norm(spectrum[k]);
        for (int m = 0; m < kMels; ++m) {
            const double* row = s.filters.data() + static_cast<size_t>(m) * kBins;
            double e = 0;
            for (int k = 0; k < kBins; ++k) e += row[k] * power[k];
            mel[static_cast<size_t>(m) * kFrames + t] = std::log10(std::max(e, 1e-10));
        }
    }
    const double floor = *std::max_element(mel.begin(), mel.end()) - 8.0;
    std::vector<float> out(mel.size());
    for (size_t i = 0; i < mel.size(); ++i) out[i] = static_cast<float>((std::max(mel[i], floor) + 4.0) / 4.0);
    return out;
}

}  // namespace

extern "C" {

// Opens a Smart Turn v3 model with [threads] intra-op threads; 0 when it will
// not load or does not take (1, 80, 800).
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeSmartTurn(
    JNIEnv* jni, jclass, jstring model, jint threads) {
    const char* m = jni->GetStringUTFChars(model, nullptr);
    const std::string path = m ? m : "";
    if (m) jni->ReleaseStringUTFChars(model, m);
    if (path.empty()) return 0;
    try {
        auto s = std::make_unique<SmartTurn>();
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        options.SetInterOpNumThreads(1);
        options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        s->session = std::make_unique<Ort::Session>(env(), path.c_str(), options);
        if (s->session->GetInputCount() != 1 || s->session->GetOutputCount() != 1) return 0;
        const std::vector<int64_t> shape = s->session->GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
        if (shape.size() != 3 || shape[1] != kMels || shape[2] != kFrames) return 0;
        Ort::AllocatorWithDefaultOptions allocator;
        s->input = s->session->GetInputNameAllocated(0, allocator).get();
        s->output = s->session->GetOutputNameAllocated(0, allocator).get();
        buildTables(s.get());
        return reinterpret_cast<jlong>(s.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeSmartTurnFree(JNIEnv*, jclass, jlong h) {
    delete turn(h);
}

// The chance, 0 to 1, that the speaker of the first [n] samples of [samples]
// (16 kHz, [-1, 1], the latest last) has finished; below 0 when the run fails.
JNIEXPORT jfloat JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeSmartTurnRun(
    JNIEnv* jni, jclass, jlong h, jfloatArray samples, jint n) {
    SmartTurn* s = turn(h);
    if (!s || n <= 0) return -1.0f;
    std::vector<float> audio(static_cast<size_t>(n));
    jni->GetFloatArrayRegion(samples, 0, n, audio.data());
    try {
        std::vector<float> input = features(*s, audio.data(), n);
        auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        const std::array<int64_t, 3> shape = {1, kMels, kFrames};
        Ort::Value tensor = Ort::Value::CreateTensor<float>(memory, input.data(), input.size(), shape.data(), shape.size());
        const char* in = s->input.c_str();
        const char* out = s->output.c_str();
        auto result = s->session->Run(Ort::RunOptions{nullptr}, &in, &tensor, 1, &out, 1);
        if (result[0].GetTensorTypeAndShapeInfo().GetElementCount() < 1) return -1.0f;
        const float p = result[0].GetTensorData<float>()[0];
        return std::isfinite(p) ? std::min(1.0f, std::max(0.0f, p)) : -1.0f;
    } catch (...) {
        return -1.0f;
    }
}

}  // extern "C"
