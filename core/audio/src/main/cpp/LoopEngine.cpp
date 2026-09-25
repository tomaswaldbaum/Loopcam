#include "LoopEngine.h"

#include <algorithm>

#include <android/log.h>

#define LOG_TAG "LoopEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace loopcam {

bool LoopEngine::start(int32_t sampleRate, int32_t loopFrames) {
    if (mOutput) return true;
    mLoopFrames = loopFrames;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(sampleRate)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mOutput);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        mOutput.reset();
        return false;
    }

    // Doble burst: el mínimo estable recomendado para baja latencia.
    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);

    LOGI("Oboe %s | api=%s perf=%s sharing=%s rate=%d burst=%d loopFrames=%d",
         oboe::getVersionText(),
         mOutput->usesAAudio() ? "AAudio" : "OpenSL ES",
         oboe::convertToText(mOutput->getPerformanceMode()),
         oboe::convertToText(mOutput->getSharingMode()),
         mOutput->getSampleRate(),
         mOutput->getFramesPerBurst(),
         mLoopFrames);

    result = mOutput->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        stop();
        return false;
    }
    return true;
}

void LoopEngine::stop() {
    if (!mOutput) return;
    mOutput->stop();
    mOutput->close();
    mOutput.reset();
}

oboe::DataCallbackResult LoopEngine::onAudioReady(oboe::AudioStream *stream,
                                                  void *audioData,
                                                  int32_t numFrames) {
    // Hilo de tiempo real: sin locks, sin allocations, sin I/O.
    auto *out = static_cast<float *>(audioData);
    std::fill_n(out, numFrames * stream->getChannelCount(), 0.0f);
    return oboe::DataCallbackResult::Continue;
}

void LoopEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Típicamente: se desconectaron los auriculares. Se reabrirá desde Kotlin.
    LOGE("Stream closed with error: %s", oboe::convertToText(error));
    mOutput.reset();
}

}  // namespace loopcam
