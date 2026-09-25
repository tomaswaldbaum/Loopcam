#include "LoopCore.h"

#include <algorithm>
#include <cmath>

namespace loopcam {

namespace {
constexpr float kClickFrequencyHz = 1000.0f;
constexpr float kClickGain = 0.3f;
constexpr int32_t kClickDivisor = 100;  // click de 10 ms
}  // namespace

float softClip(float x) {
    constexpr float kThreshold = 0.8f;
    const float a = std::fabs(x);
    if (a <= kThreshold) return x;
    const float y = kThreshold + (1.0f - kThreshold) * std::tanh((a - kThreshold) / (1.0f - kThreshold));
    return std::copysign(y, x);
}

void LoopCore::prepare(int32_t loopFrames, int32_t maxLayers, int32_t sampleRate) {
    mLoopFrames = std::max(loopFrames, 2);
    mMaxLayers = std::max(maxLayers, 1);
    mLayers.assign(static_cast<size_t>(mLoopFrames) * mMaxLayers, 0.0f);

    const float rate = static_cast<float>(std::max(sampleRate, 1));
    const int32_t clickLength = std::min(std::max(sampleRate, 1) / kClickDivisor, mLoopFrames / 2);
    mClickSamples.resize(clickLength);
    for (int32_t i = 0; i < clickLength; ++i) {
        const float t = static_cast<float>(i) / rate;
        const float envelope = 1.0f - static_cast<float>(i) / clickLength;
        mClickSamples[i] = kClickGain * envelope * std::sin(2.0f * static_cast<float>(M_PI) * kClickFrequencyHz * t);
    }

    mLatency = 0;
    mPos = 0;
    mCycle = 0;
    mCommitted = 0;
    mTailSlot = -1;
    mTailMixLayers = 0;
    mUndoRequests.store(0);
    startRecordingIfEnabled();
    mCommittedPublic.store(0);
    mPosPublic.store(0);
}

void LoopCore::setLatencyFrames(int32_t latencyFrames) {
    // La cola de una capa se escribe mientras se reproduce el principio de la
    // siguiente vuelta; con latency <= L/2 nunca se pisa lo que se está leyendo.
    mLatency = std::clamp(latencyFrames, 0, mLoopFrames / 2);
}

float LoopCore::mixAt(int32_t index, int32_t numLayers) const {
    float sum = 0.0f;
    const float *base = mLayers.data() + index;
    for (int32_t slot = 0; slot < numLayers; ++slot) {
        sum += base[static_cast<size_t>(slot) * mLoopFrames];
    }
    return sum;
}

void LoopCore::write(int32_t slot, WriteMode mode, int32_t index, float value) {
    float &sample = mLayers[static_cast<size_t>(slot) * mLoopFrames + index];
    sample = (mode == WriteMode::Replace) ? value : sample + value;
}

void LoopCore::startRecordingIfEnabled() {
    if (!mOverdub.load(std::memory_order_relaxed)) {
        mRecSlot = -1;
    } else if (mCommitted < mMaxLayers) {
        mRecSlot = mCommitted;
        mRecMode = WriteMode::Replace;
    } else {
        mRecSlot = mMaxLayers - 1;
        mRecMode = WriteMode::Add;
    }
    mRecordingPublic.store(mRecSlot >= 0, std::memory_order_relaxed);
}

void LoopCore::applyUndo() {
    int32_t requests = mUndoRequests.exchange(0, std::memory_order_relaxed);
    while (requests-- > 0) {
        // La capa en curso se descarta; se vuelve a grabar desde la próxima vuelta.
        mRecSlot = -1;
        if (mCommitted > 0) --mCommitted;
        if (mTailSlot >= mCommitted) mTailSlot = -1;
        mTailMixLayers = std::min(mTailMixLayers, mCommitted);
    }
    mRecordingPublic.store(mRecSlot >= 0, std::memory_order_relaxed);
}

void LoopCore::onWrap() {
    const int32_t committedBefore = mCommitted;
    if (mRecSlot >= 0) {
        if (mRecMode == WriteMode::Replace) ++mCommitted;
        mTailSlot = mRecSlot;
        mTailMode = mRecMode;
    } else {
        mTailSlot = -1;
    }
    mTailMixLayers = committedBefore;
    ++mCycle;
    startRecordingIfEnabled();
}

int32_t LoopCore::process(const float *in, float *out, float *fileOut, int32_t numFrames) {
    if (mUndoRequests.load(std::memory_order_relaxed) > 0) applyUndo();

    const bool click = mClick.load(std::memory_order_relaxed);
    const auto clickLength = static_cast<int32_t>(mClickSamples.size());
    int32_t fileFrames = 0;

    for (int32_t i = 0; i < numFrames; ++i) {
        const int32_t p = mPos;

        // Lo que suena: capas terminadas (+ click al inicio de cada vuelta).
        float playback = mixAt(p, mCommitted);
        if (click && p < clickLength) playback += mClickSamples[p];
        out[i] = softClip(playback);

        // Lo que entra: se ubica en la posición que el usuario estaba escuchando.
        const float x = in[i];
        if (p >= mLatency) {
            const int32_t w = p - mLatency;
            if (mRecSlot >= 0) write(mRecSlot, mRecMode, w, x);
            fileOut[fileFrames++] = softClip(mixAt(w, mCommitted) + x);
        } else if (mCycle > 0) {
            // Cola de la vuelta anterior.
            const int32_t w = p - mLatency + mLoopFrames;
            if (mTailSlot >= 0) write(mTailSlot, mTailMode, w, x);
            fileOut[fileFrames++] = softClip(mixAt(w, mTailMixLayers) + x);
        }
        // En la vuelta 0, los primeros `latency` samples son previos al arranque: se descartan.

        if (++mPos == mLoopFrames) {
            mPos = 0;
            onWrap();
        }
    }

    mCommittedPublic.store(mCommitted, std::memory_order_relaxed);
    mPosPublic.store(mPos, std::memory_order_relaxed);
    return fileFrames;
}

}  // namespace loopcam
