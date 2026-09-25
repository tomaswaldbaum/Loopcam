#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <oboe/Oboe.h>

#include "core/AudioFileWriter.h"
#include "core/LoopCore.h"

namespace loopcam {

struct EngineStatus {
    int32_t phase = 0;  // LoopCore::Phase
    int32_t countInBeatsRemaining = 0;
    int32_t currentBeat = 0;
    int64_t cycle = 0;
    bool layersFull = false;
    int32_t beatsPerLoop = 0;
    int32_t beatsPerBar = 0;
    int32_t countInBeats = 0;
    int32_t committedLayers = 0;
    int32_t maxLayers = 0;
    int32_t position = 0;
    int32_t loopFrames = 0;
    int32_t sampleRate = 0;
    int32_t latencyFrames = 0;
    bool recordingLayer = false;
    bool disconnected = false;
    int64_t droppedFrames = 0;
    /** Instante de captura (CLOCK_MONOTONIC) del primer sample del WAV; 0 si no se conoce. */
    int64_t fileStartNanos = 0;
};

/**
 * Stream full-duplex de Oboe (entrada + salida de baja latencia) que alimenta al
 * LoopCore y manda la mezcla al AudioFileWriter.
 */
class LoopEngine : public oboe::FullDuplexStream,
                   public oboe::AudioStreamErrorCallback {
public:
    ~LoopEngine() override { stopSession(); }

    /**
     * Abre los streams, mide la latencia y arranca el loop. Bloquea unos ~300 ms:
     * llamar fuera del hilo principal.
     */
    struct SessionParams {
        int32_t sampleRate = 48000;
        double loopSeconds = 4.0;
        int32_t beatsPerLoop = 4;
        int32_t beatsPerBar = 4;
        int32_t countInBeats = 0;
        int32_t maxLayers = 1;
        int32_t latencyOffsetFrames = 0;
    };

    bool startSession(const SessionParams &params, const std::string &wavPath);
    void stopSession();

    void setOverdub(bool enabled) { mCore.setOverdub(enabled); }
    void setMetronome(bool inCountIn, bool whileLooping) { mCore.setMetronome(inCountIn, whileLooping); }
    void undoLastLayer() { mCore.requestUndo(); }

    EngineStatus status() const;

    oboe::DataCallbackResult onBothStreamsReady(const void *inputData, int numInputFrames,
                                                void *outputData, int numOutputFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openStreams(int32_t sampleRate);
    void closeStreams();
    int32_t measureLatencyFrames();
    int64_t computeFileStartNanos() const;

    std::shared_ptr<oboe::AudioStream> mOutput;
    std::shared_ptr<oboe::AudioStream> mInput;
    LoopCore mCore;
    AudioFileWriter mWriter;

    std::vector<float> mInputScratch;
    std::vector<float> mFileScratch;
    int32_t mSampleRate = 0;

    std::atomic<bool> mArmed{false};
    std::atomic<bool> mDisconnected{false};
    std::atomic<int64_t> mFileStartInputFrame{-1};
    std::atomic<int64_t> mFileStartNanos{0};
};

}  // namespace loopcam
