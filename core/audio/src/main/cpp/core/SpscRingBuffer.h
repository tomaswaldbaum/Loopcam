#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <vector>

namespace loopcam {

/**
 * Ring buffer de un productor y un consumidor, sin locks. El productor es el
 * callback de audio; el consumidor, el hilo que escribe a disco.
 */
class SpscRingBuffer {
public:
    /** La capacidad se redondea a la potencia de 2 siguiente. */
    explicit SpscRingBuffer(size_t minCapacity) {
        size_t capacity = 1;
        while (capacity < minCapacity) capacity <<= 1;
        mBuffer.resize(capacity);
        mMask = capacity - 1;
    }

    size_t capacity() const { return mBuffer.size(); }

    /** Productor. Devuelve cuántos samples entraron (menos que n = overflow). */
    size_t write(const float *data, size_t n) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        const size_t toWrite = std::min(n, capacity() - (head - tail));
        for (size_t i = 0; i < toWrite; ++i) mBuffer[(head + i) & mMask] = data[i];
        mHead.store(head + toWrite, std::memory_order_release);
        return toWrite;
    }

    /** Consumidor. Devuelve cuántos samples se leyeron. */
    size_t read(float *data, size_t n) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        const size_t toRead = std::min(n, head - tail);
        for (size_t i = 0; i < toRead; ++i) data[i] = mBuffer[(tail + i) & mMask];
        mTail.store(tail + toRead, std::memory_order_release);
        return toRead;
    }

    size_t available() const {
        return mHead.load(std::memory_order_acquire) - mTail.load(std::memory_order_acquire);
    }

private:
    std::vector<float> mBuffer;
    size_t mMask = 0;
    alignas(64) std::atomic<size_t> mHead{0};  // índices monótonos; el overflow de size_t es seguro
    alignas(64) std::atomic<size_t> mTail{0};
};

}  // namespace loopcam
