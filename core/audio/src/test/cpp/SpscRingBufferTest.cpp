#include "SpscRingBuffer.h"

#include <thread>
#include <vector>

#include <gtest/gtest.h>

namespace loopcam {
namespace {

TEST(SpscRingBufferTest, capacityIsRoundedToPowerOfTwo) {
    EXPECT_EQ(8u, SpscRingBuffer(5).capacity());
    EXPECT_EQ(8u, SpscRingBuffer(8).capacity());
}

TEST(SpscRingBufferTest, readsBackInOrderAcrossWrapAround) {
    SpscRingBuffer ring(8);
    const float a[6] = {1, 2, 3, 4, 5, 6};
    float out[8];
    ASSERT_EQ(6u, ring.write(a, 6));
    ASSERT_EQ(4u, ring.read(out, 4));
    const float b[5] = {7, 8, 9, 10, 11};
    ASSERT_EQ(5u, ring.write(b, 5));  // da la vuelta al buffer
    ASSERT_EQ(7u, ring.read(out, 8));
    const float expected[7] = {5, 6, 7, 8, 9, 10, 11};
    for (int i = 0; i < 7; ++i) EXPECT_FLOAT_EQ(expected[i], out[i]);
}

TEST(SpscRingBufferTest, overflowWritesOnlyWhatFits) {
    SpscRingBuffer ring(4);
    const float a[6] = {1, 2, 3, 4, 5, 6};
    EXPECT_EQ(4u, ring.write(a, 6));
    EXPECT_EQ(0u, ring.write(a, 1));
    EXPECT_EQ(4u, ring.available());
}

TEST(SpscRingBufferTest, producerAndConsumerThreadsPreserveSequence) {
    SpscRingBuffer ring(64);
    constexpr int kTotal = 100000;
    std::thread producer([&] {
        for (int i = 0; i < kTotal;) {
            const float v = static_cast<float>(i);
            if (ring.write(&v, 1) == 1) ++i;
        }
    });
    int next = 0;
    float buffer[16];
    while (next < kTotal) {
        const size_t n = ring.read(buffer, 16);
        for (size_t i = 0; i < n; ++i) ASSERT_FLOAT_EQ(static_cast<float>(next++), buffer[i]);
    }
    producer.join();
}

}  // namespace
}  // namespace loopcam
