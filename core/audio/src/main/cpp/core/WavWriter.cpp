#include "WavWriter.h"

#include <algorithm>
#include <cmath>

namespace loopcam {

namespace {
void putU32(FILE *f, uint32_t v) {
    const uint8_t b[4] = {uint8_t(v), uint8_t(v >> 8), uint8_t(v >> 16), uint8_t(v >> 24)};
    std::fwrite(b, 1, 4, f);
}

void putU16(FILE *f, uint16_t v) {
    const uint8_t b[2] = {uint8_t(v), uint8_t(v >> 8)};
    std::fwrite(b, 1, 2, f);
}
}  // namespace

bool WavWriter::open(const std::string &path, int32_t sampleRate) {
    close();
    mFile = std::fopen(path.c_str(), "wb");
    if (!mFile) return false;
    mSampleRate = sampleRate;
    mDataBytes = 0;
    writeHeader();
    return true;
}

bool WavWriter::write(const float *samples, size_t count) {
    if (!mFile) return false;
    int16_t chunk[1024];
    size_t done = 0;
    while (done < count) {
        const size_t n = std::min(count - done, sizeof(chunk) / sizeof(chunk[0]));
        for (size_t i = 0; i < n; ++i) {
            const float s = std::clamp(samples[done + i], -1.0f, 1.0f);
            chunk[i] = static_cast<int16_t>(std::lrint(s * 32767.0f));
        }
        if (std::fwrite(chunk, sizeof(int16_t), n, mFile) != n) return false;
        mDataBytes += static_cast<uint32_t>(n * sizeof(int16_t));
        done += n;
    }
    return true;
}

void WavWriter::close() {
    if (!mFile) return;
    std::fseek(mFile, 0, SEEK_SET);
    writeHeader();
    std::fclose(mFile);
    mFile = nullptr;
}

void WavWriter::writeHeader() {
    constexpr uint16_t kChannels = 1;
    constexpr uint16_t kBitsPerSample = 16;
    constexpr uint16_t kBlockAlign = kChannels * kBitsPerSample / 8;
    std::fwrite("RIFF", 1, 4, mFile);
    putU32(mFile, 36 + mDataBytes);
    std::fwrite("WAVE", 1, 4, mFile);
    std::fwrite("fmt ", 1, 4, mFile);
    putU32(mFile, 16);
    putU16(mFile, 1);  // PCM
    putU16(mFile, kChannels);
    putU32(mFile, static_cast<uint32_t>(mSampleRate));
    putU32(mFile, static_cast<uint32_t>(mSampleRate) * kBlockAlign);
    putU16(mFile, kBlockAlign);
    putU16(mFile, kBitsPerSample);
    std::fwrite("data", 1, 4, mFile);
    putU32(mFile, mDataBytes);
}

}  // namespace loopcam
