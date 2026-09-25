#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

#include "SpscRingBuffer.h"
#include "WavWriter.h"

namespace loopcam {

/**
 * Saca el I/O del hilo de audio: el callback hace push() al ring buffer y un
 * hilo normal lo vuelca al WAV.
 */
class AudioFileWriter {
public:
    explicit AudioFileWriter(size_t ringCapacity = 1 << 18) : mRing(ringCapacity) {}
    ~AudioFileWriter() { stop(); }

    bool start(const std::string &path, int32_t sampleRate);
    /** Vacía lo pendiente, cierra el archivo y termina el hilo. */
    void stop();

    /** Hilo de audio. Devuelve false si hubo overflow (se perdieron samples). */
    bool push(const float *samples, size_t count) {
        const size_t written = mRing.write(samples, count);
        if (written < count) {
            mDroppedFrames.fetch_add(count - written, std::memory_order_relaxed);
            return false;
        }
        return true;
    }

    uint64_t droppedFrames() const { return mDroppedFrames.load(std::memory_order_relaxed); }
    uint32_t framesWritten() const { return mFramesWritten.load(std::memory_order_relaxed); }

private:
    void run();
    void drain();

    SpscRingBuffer mRing;
    WavWriter mWav;
    std::thread mThread;
    std::atomic<bool> mRunning{false};
    std::atomic<uint64_t> mDroppedFrames{0};
    std::atomic<uint32_t> mFramesWritten{0};
};

}  // namespace loopcam
