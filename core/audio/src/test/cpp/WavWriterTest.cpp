#include "AudioFileWriter.h"
#include "WavWriter.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <gtest/gtest.h>

namespace loopcam {
namespace {

std::vector<uint8_t> readAll(const std::string &path) {
    std::vector<uint8_t> bytes;
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return bytes;
    uint8_t buffer[4096];
    size_t n;
    while ((n = std::fread(buffer, 1, sizeof(buffer), f)) > 0) bytes.insert(bytes.end(), buffer, buffer + n);
    std::fclose(f);
    return bytes;
}

uint32_t u32(const std::vector<uint8_t> &b, size_t at) {
    return b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (uint32_t(b[at + 3]) << 24);
}

int16_t s16(const std::vector<uint8_t> &b, size_t at) {
    return static_cast<int16_t>(b[at] | (b[at + 1] << 8));
}

std::string tempPath(const char *name) {
    return ::testing::TempDir() + name;
}

TEST(WavWriterTest, writesValidPcm16Header) {
    const std::string path = tempPath("header.wav");
    WavWriter wav;
    ASSERT_TRUE(wav.open(path, 48000));
    const float samples[3] = {0.0f, 1.0f, -2.0f};
    ASSERT_TRUE(wav.write(samples, 3));
    wav.close();

    const auto b = readAll(path);
    ASSERT_EQ(44u + 6u, b.size());
    EXPECT_EQ(0, std::memcmp(b.data(), "RIFF", 4));
    EXPECT_EQ(36u + 6u, u32(b, 4));
    EXPECT_EQ(0, std::memcmp(b.data() + 8, "WAVE", 4));
    EXPECT_EQ(48000u, u32(b, 24));
    EXPECT_EQ(6u, u32(b, 40));
    EXPECT_EQ(0, s16(b, 44));
    EXPECT_EQ(32767, s16(b, 46));
    EXPECT_EQ(-32767, s16(b, 48));  // saturado a -1
}

TEST(AudioFileWriterTest, drainsEverythingPushedBeforeStop) {
    const std::string path = tempPath("writer.wav");
    AudioFileWriter writer(1024);
    ASSERT_TRUE(writer.start(path, 48000));
    std::vector<float> block(256, 0.5f);
    for (int i = 0; i < 40; ++i) {
        while (!writer.push(block.data(), block.size())) {
            // El hilo de escritura todavía no vació el ring; en el callback real esto sería overflow.
        }
    }
    writer.stop();
    EXPECT_EQ(40u * 256u, writer.framesWritten());
    EXPECT_EQ(44u + 40u * 256u * 2u, readAll(path).size());
}

}  // namespace
}  // namespace loopcam
