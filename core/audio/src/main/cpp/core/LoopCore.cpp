#include "LoopCore.h"

#include <algorithm>
#include <cmath>

namespace loopcam {

namespace {
constexpr float kAccentFrequencyHz = 1500.0f;
constexpr float kNormalFrequencyHz = 1000.0f;
constexpr float kAccentGain = 0.4f;
constexpr float kNormalGain = 0.28f;
constexpr int32_t kClickDivisor = 66;  // clicks de ~15 ms

void fillClick(std::vector<float> &click, float frequency, float gain, float rate) {
    const auto length = static_cast<int32_t>(click.size());
    for (int32_t i = 0; i < length; ++i) {
        const float t = static_cast<float>(i) / rate;
        const float envelope = 1.0f - static_cast<float>(i) / static_cast<float>(length);
        click[i] = gain * envelope * envelope * std::sin(2.0f * static_cast<float>(M_PI) * frequency * t);
    }
}
}  // namespace

float softClip(float x) {
    constexpr float kThreshold = 0.8f;
    const float a = std::fabs(x);
    if (a <= kThreshold) return x;
    const float y = kThreshold + (1.0f - kThreshold) * std::tanh((a - kThreshold) / (1.0f - kThreshold));
    return std::copysign(y, x);
}

void LoopCore::prepare(const LoopParams &params) {
    mBeatsPerLoop = std::max(params.beatsPerLoop, 1);
    mBeatsPerBar = std::max(params.beatsPerBar, 1);
    // El loop es un múltiplo exacto del tiempo, así el metrónomo no se corre vuelta a vuelta.
    mBeatFrames = std::max(params.loopFrames / mBeatsPerLoop, 2);
    mLoopFrames = mBeatFrames * mBeatsPerLoop;
    mCountInBeats = std::max(params.countInBeats, 0);
    mCountInFrames = mCountInBeats * mBeatFrames;
    mMaxLayers = std::max(params.maxLayers, 1);
    mLayers.assign(static_cast<size_t>(mLoopFrames) * mMaxLayers, 0.0f);

    const int32_t rate = std::max(params.sampleRate, 1);
    const int32_t clickLength = std::max(1, std::min(rate / kClickDivisor, mBeatFrames / 2));
    mAccentClick.assign(clickLength, 0.0f);
    mNormalClick.assign(clickLength, 0.0f);
    fillClick(mAccentClick, kAccentFrequencyHz, kAccentGain, static_cast<float>(rate));
    fillClick(mNormalClick, kNormalFrequencyHz, kNormalGain, static_cast<float>(rate));

    mLatency = 0;
    mCountInPos = 0;
    mPos = 0;
    mCycle = 0;
    mCommitted = 0;
    mTailSlot = -1;
    mTailMixLayers = 0;
    mUndoRequests.store(0);
    startRecordingIfEnabled();
    mPhasePublic.store(static_cast<int32_t>(mCountInFrames > 0 ? Phase::CountIn : Phase::Looping));
    mCommittedPublic.store(0);
    mPosPublic.store(0);
    mCyclePublic.store(0);
}

void LoopCore::setLatencyFrames(int32_t latencyFrames) {
    // La cola de una capa se escribe mientras se reproduce el principio de la
    // siguiente vuelta; con latency <= L/2 nunca se pisa lo que se está leyendo.
    mLatency = std::clamp(latencyFrames, 0, mLoopFrames / 2);
}

int32_t LoopCore::currentBeat() const {
    return mBeatFrames > 0 ? position() / mBeatFrames : 0;
}

int32_t LoopCore::countInBeatsRemaining() const {
    if (phase() != Phase::CountIn) return 0;
    return mCountInBeats - currentBeat();
}

float LoopCore::mixAt(int32_t index, int32_t numLayers) const {
    float sum = 0.0f;
    const float *base = mLayers.data() + index;
    for (int32_t slot = 0; slot < numLayers; ++slot) {
        sum += base[static_cast<size_t>(slot) * mLoopFrames];
    }
    return sum;
}

float LoopCore::metronomeAt(int32_t framesIntoSection) const {
    const int32_t inBeat = framesIntoSection % mBeatFrames;
    if (inBeat >= static_cast<int32_t>(mNormalClick.size())) return 0.0f;
    const int32_t beat = framesIntoSection / mBeatFrames;
    return (beat % mBeatsPerBar == 0) ? mAccentClick[inBeat] : mNormalClick[inBeat];
}

void LoopCore::startRecordingIfEnabled() {
    const bool full = mCommitted >= mMaxLayers;
    mRecSlot = (mOverdub.load(std::memory_order_relaxed) && !full) ? mCommitted : -1;
    mRecordingPublic.store(mRecSlot >= 0, std::memory_order_relaxed);
    mLayersFullPublic.store(full, std::memory_order_relaxed);
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
    mRecordingPublic.store(false, std::memory_order_relaxed);
    mLayersFullPublic.store(mCommitted >= mMaxLayers, std::memory_order_relaxed);
}

void LoopCore::onWrap() {
    const int32_t committedBefore = mCommitted;
    if (mRecSlot >= 0) {
        ++mCommitted;
        mTailSlot = mRecSlot;
    } else {
        mTailSlot = -1;
    }
    mTailMixLayers = committedBefore;
    ++mCycle;
    startRecordingIfEnabled();
}

int32_t LoopCore::process(const float *in, float *out, float *fileOut, int32_t numFrames) {
    if (mUndoRequests.load(std::memory_order_relaxed) > 0) applyUndo();

    const bool metronomeCountIn = mMetronomeCountIn.load(std::memory_order_relaxed);
    const bool metronomeLooping = mMetronomeLooping.load(std::memory_order_relaxed);
    int32_t fileFrames = 0;
    int32_t i = 0;

    // Cuenta regresiva: solo metrónomo; el input se descarta.
    for (; i < numFrames && mCountInPos < mCountInFrames; ++i) {
        out[i] = metronomeCountIn ? metronomeAt(mCountInPos) : 0.0f;
        ++mCountInPos;
    }
    if (mCountInPos < mCountInFrames) {
        mPosPublic.store(mCountInPos, std::memory_order_relaxed);
        return 0;
    }

    for (; i < numFrames; ++i) {
        const int32_t p = mPos;

        // Lo que suena: capas terminadas (+ metrónomo, que no va al archivo).
        float playback = mixAt(p, mCommitted);
        if (metronomeLooping) playback += metronomeAt(p);
        out[i] = softClip(playback);

        // Lo que entra: se ubica en la posición que el usuario estaba escuchando.
        const float x = in[i];
        if (p >= mLatency) {
            const int32_t w = p - mLatency;
            if (mRecSlot >= 0) mLayers[static_cast<size_t>(mRecSlot) * mLoopFrames + w] = x;
            fileOut[fileFrames++] = softClip(mixAt(w, mCommitted) + x);
        } else if (mCycle > 0) {
            // Cola de la vuelta anterior.
            const int32_t w = p - mLatency + mLoopFrames;
            if (mTailSlot >= 0) mLayers[static_cast<size_t>(mTailSlot) * mLoopFrames + w] = x;
            fileOut[fileFrames++] = softClip(mixAt(w, mTailMixLayers) + x);
        }
        // En la vuelta 0, los primeros `latency` samples son previos al arranque: se descartan.

        if (++mPos == mLoopFrames) {
            mPos = 0;
            onWrap();
        }
    }

    mPhasePublic.store(static_cast<int32_t>(Phase::Looping), std::memory_order_relaxed);
    mCommittedPublic.store(mCommitted, std::memory_order_relaxed);
    mPosPublic.store(mPos, std::memory_order_relaxed);
    mCyclePublic.store(mCycle, std::memory_order_relaxed);
    return fileFrames;
}

}  // namespace loopcam
