#include "LoopEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <ctime>
#include <thread>

#include <android/log.h>

#define LOG_TAG "LoopEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace loopcam {

namespace {
// Memoria máxima para capas: 16 M samples float = 64 MB.
constexpr int64_t kLayerBudgetSamples = 16LL * 1024 * 1024;
constexpr int32_t kMaxLayers = 16;
// Tiempo para que los streams se estabilicen antes de medir la latencia.
constexpr auto kWarmUp = std::chrono::milliseconds(300);
}  // namespace

bool LoopEngine::openStreams(int32_t sampleRate) {
    oboe::AudioStreamBuilder out;
    out.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = out.openStream(mOutput);
    if (result != oboe::Result::OK) {
        LOGE("Output openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    oboe::AudioStreamBuilder in;
    in.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(mOutput->getSampleRate())
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            // Sin AGC ni supresión de ruido: queremos el sonido tal cual.
            ->setInputPreset(oboe::InputPreset::Unprocessed)
            ->setBufferCapacityInFrames(mOutput->getBufferCapacityInFrames() * 2);
    result = in.openStream(mInput);
    if (result != oboe::Result::OK) {
        LOGE("Input openStream failed: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }

    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);
    setSharedInputStream(mInput);
    setSharedOutputStream(mOutput);

    LOGI("Oboe %s | out: %s %s %s burst=%d | in: %s %s rate=%d",
         oboe::getVersionText(),
         mOutput->usesAAudio() ? "AAudio" : "OpenSL ES",
         oboe::convertToText(mOutput->getPerformanceMode()),
         oboe::convertToText(mOutput->getSharingMode()),
         mOutput->getFramesPerBurst(),
         oboe::convertToText(mInput->getPerformanceMode()),
         oboe::convertToText(mInput->getSharingMode()),
         mOutput->getSampleRate());
    return true;
}

bool LoopEngine::startSession(int32_t sampleRate, double loopSeconds, const std::string &wavPath,
                       int32_t latencyOffsetFrames) {
    stopSession();
    mDisconnected.store(false);
    if (!openStreams(sampleRate)) return false;

    mSampleRate = mOutput->getSampleRate();
    const auto loopFrames = static_cast<int32_t>(std::lround(loopSeconds * mSampleRate));
    const auto maxLayers = static_cast<int32_t>(
            std::clamp<int64_t>(kLayerBudgetSamples / std::max(loopFrames, 1), 1, kMaxLayers));

    // Todo lo que usa el callback se aloca acá, antes de arrancar.
    const int32_t capacity = std::max(mOutput->getBufferCapacityInFrames(), 4096);
    mInputScratch.assign(capacity, 0.0f);
    mFileScratch.assign(capacity, 0.0f);
    mCore.prepare(loopFrames, maxLayers, mSampleRate);
    mFileStartInputFrame.store(-1);
    mFileStartNanos = 0;

    if (!mWriter.start(wavPath, mSampleRate)) {
        LOGE("No se pudo crear %s", wavPath.c_str());
        closeStreams();
        return false;
    }

    const oboe::Result result = FullDuplexStream::start();
    if (result != oboe::Result::OK) {
        LOGE("FullDuplexStream start failed: %s", oboe::convertToText(result));
        stopSession();
        return false;
    }

    // Los streams corren "desarmados" (silencio) mientras se estabilizan.
    std::this_thread::sleep_for(kWarmUp);
    const int32_t latency = std::max(0, measureLatencyFrames() + latencyOffsetFrames);
    mCore.setLatencyFrames(latency);
    mArmed.store(true, std::memory_order_release);

    LOGI("Loop armado: loopFrames=%d maxLayers=%d latency=%d frames (%.1f ms)",
         loopFrames, maxLayers, mCore.latencyFrames(),
         1000.0 * mCore.latencyFrames() / mSampleRate);

    // Esperar al primer callback armado para poder fechar el inicio del archivo.
    for (int i = 0; i < 50 && mFileStartInputFrame.load() < 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    mFileStartNanos = computeFileStartNanos();
    return true;
}

void LoopEngine::stopSession() {
    mArmed.store(false, std::memory_order_release);
    closeStreams();
    mWriter.stop();
}

void LoopEngine::closeStreams() {
    // FullDuplexStream::stop() de Oboe 1.9 no detiene el input: lo hacemos a mano.
    if (mOutput) {
        mOutput->stop();
        mOutput->close();
        mOutput.reset();
    }
    if (mInput) {
        mInput->stop();
        mInput->close();
        mInput.reset();
    }
}

int32_t LoopEngine::measureLatencyFrames() {
    double totalMs = 0.0;
    for (auto &stream : {mOutput, mInput}) {
        if (!stream) continue;
        auto latency = stream->calculateLatencyMillis();
        if (latency) {
            totalMs += latency.value();
        } else {
            // Sin timestamps (p. ej. OpenSL ES): estimar por el tamaño del buffer.
            totalMs += 1000.0 * stream->getBufferSizeInFrames() / mSampleRate;
            LOGW("calculateLatencyMillis no disponible: %s", oboe::convertToText(latency.error()));
        }
    }
    return static_cast<int32_t>(std::lround(totalMs * mSampleRate / 1000.0));
}

int64_t LoopEngine::computeFileStartNanos() const {
    const int64_t fileStartFrame = mFileStartInputFrame.load();
    if (!mInput || fileStartFrame < 0) return 0;
    auto timestamp = mInput->getTimestamp(CLOCK_MONOTONIC);
    if (!timestamp) {
        LOGW("getTimestamp del input no disponible: %s", oboe::convertToText(timestamp.error()));
        return 0;
    }
    const auto &ts = timestamp.value();
    const double deltaFrames = static_cast<double>(fileStartFrame - ts.position);
    return ts.timestamp + static_cast<int64_t>(deltaFrames * 1e9 / mSampleRate);
}

EngineStatus LoopEngine::status() const {
    EngineStatus s;
    s.committedLayers = mCore.committedLayers();
    s.maxLayers = mCore.maxLayers();
    s.position = mCore.position();
    s.loopFrames = mCore.loopFrames();
    s.sampleRate = mSampleRate;
    s.latencyFrames = mCore.latencyFrames();
    s.recordingLayer = mCore.isRecordingLayer();
    s.disconnected = mDisconnected.load();
    s.droppedFrames = static_cast<int64_t>(mWriter.droppedFrames());
    s.fileStartNanos = mFileStartNanos;
    return s;
}

oboe::DataCallbackResult LoopEngine::onBothStreamsReady(const void *inputData, int numInputFrames,
                                                        void *outputData, int numOutputFrames) {
    // Hilo de tiempo real: sin locks, sin allocations, sin I/O.
    auto *out = static_cast<float *>(outputData);
    if (!mArmed.load(std::memory_order_acquire) ||
        numOutputFrames > static_cast<int>(mFileScratch.size())) {
        std::fill_n(out, numOutputFrames, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    if (mFileStartInputFrame.load(std::memory_order_relaxed) < 0) {
        // El primer sample del archivo es el input que llega `latency` frames después del arranque.
        const int64_t firstInputFrame = getInputStream()->getFramesRead() - numInputFrames;
        mFileStartInputFrame.store(firstInputFrame + mCore.latencyFrames(), std::memory_order_relaxed);
    }

    // Si el input trae menos frames que el output, completar con silencio.
    const auto *in = static_cast<const float *>(inputData);
    if (numInputFrames < numOutputFrames) {
        std::copy_n(in, std::max(numInputFrames, 0), mInputScratch.data());
        std::fill(mInputScratch.begin() + std::max(numInputFrames, 0),
                  mInputScratch.begin() + numOutputFrames, 0.0f);
        in = mInputScratch.data();
    }

    const int32_t fileFrames = mCore.process(in, out, mFileScratch.data(), numOutputFrames);
    mWriter.push(mFileScratch.data(), static_cast<size_t>(fileFrames));
    return oboe::DataCallbackResult::Continue;
}

void LoopEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Típicamente: se desconectaron los auriculares. Kotlin lo ve en el status.
    LOGE("Stream cerrado con error: %s", oboe::convertToText(error));
    mArmed.store(false, std::memory_order_release);
    mDisconnected.store(true);
}

}  // namespace loopcam
