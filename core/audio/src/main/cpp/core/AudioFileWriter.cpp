#include "AudioFileWriter.h"

#include <chrono>

namespace loopcam {

bool AudioFileWriter::start(const std::string &path, int32_t sampleRate) {
    stop();
    if (!mWav.open(path, sampleRate)) return false;
    mDroppedFrames.store(0);
    mFramesWritten.store(0);
    mRunning.store(true);
    mThread = std::thread(&AudioFileWriter::run, this);
    return true;
}

void AudioFileWriter::stop() {
    if (mThread.joinable()) {
        mRunning.store(false);
        mThread.join();
    }
    drain();
    mWav.close();
}

void AudioFileWriter::run() {
    while (mRunning.load()) {
        drain();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

void AudioFileWriter::drain() {
    float chunk[2048];
    size_t n;
    while ((n = mRing.read(chunk, sizeof(chunk) / sizeof(chunk[0]))) > 0) {
        if (mWav.isOpen()) mWav.write(chunk, n);
    }
    mFramesWritten.store(mWav.framesWritten(), std::memory_order_relaxed);
}

}  // namespace loopcam
