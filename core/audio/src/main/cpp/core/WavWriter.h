#pragma once

#include <cstdint>
#include <cstdio>
#include <string>

namespace loopcam {

/** WAV PCM 16-bit mono. El header se completa al cerrar. */
class WavWriter {
public:
    ~WavWriter() { close(); }

    bool open(const std::string &path, int32_t sampleRate);
    /** Convierte de float [-1, 1] a int16. */
    bool write(const float *samples, size_t count);
    void close();

    bool isOpen() const { return mFile != nullptr; }
    uint32_t framesWritten() const { return mDataBytes / 2; }

private:
    void writeHeader();

    FILE *mFile = nullptr;
    int32_t mSampleRate = 0;
    uint32_t mDataBytes = 0;
};

}  // namespace loopcam
