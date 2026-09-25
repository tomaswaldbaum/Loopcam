#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

namespace loopcam {

/**
 * Lógica pura del looper (sin Oboe ni I/O) para poder testearla en el host.
 *
 * Modelo:
 * - El loop tiene una longitud fija de L frames. Cada vuelta completa con overdub
 *   activo graba una capa nueva; al terminar la vuelta la capa se suma a la mezcla.
 * - Compensación de latencia: el sample que entra ahora fue tocado por el usuario
 *   escuchando la posición (pos - latency), así que se escribe ahí.
 * - Las capas nuevas se escriben con '=' (sin necesidad de limpiarlas), porque cada
 *   capa recibe exactamente L samples: L - latency en su vuelta y el resto
 *   ("cola") durante los primeros `latency` frames de la vuelta siguiente.
 * - Cuando se llega al máximo de capas, las nuevas se suman sobre la última.
 *
 * Hilos: prepare()/setLatencyFrames() con el audio detenido; process() en el hilo
 * de audio; el resto desde cualquier hilo.
 */
class LoopCore {
public:
    void prepare(int32_t loopFrames, int32_t maxLayers, int32_t sampleRate);
    void setLatencyFrames(int32_t latencyFrames);

    /**
     * Procesa un bloque.
     * @param in       input del micrófono (numFrames samples, mono)
     * @param out      mezcla que suena en los auriculares
     * @param fileOut  mezcla alineada en "tiempo de input" (lo que se escuchaba +
     *                 lo que se tocaba) para el archivo. Capacidad >= numFrames.
     * @return cantidad de samples escritos en fileOut.
     */
    int32_t process(const float *in, float *out, float *fileOut, int32_t numFrames);

    void setOverdub(bool enabled) { mOverdub.store(enabled, std::memory_order_relaxed); }
    void setClickEnabled(bool enabled) { mClick.store(enabled, std::memory_order_relaxed); }
    /** Descarta la última capa terminada y la que se está grabando. */
    void requestUndo() { mUndoRequests.fetch_add(1, std::memory_order_relaxed); }

    int32_t loopFrames() const { return mLoopFrames; }
    int32_t maxLayers() const { return mMaxLayers; }
    int32_t latencyFrames() const { return mLatency; }
    int32_t committedLayers() const { return mCommittedPublic.load(std::memory_order_relaxed); }
    int32_t position() const { return mPosPublic.load(std::memory_order_relaxed); }
    bool isRecordingLayer() const { return mRecordingPublic.load(std::memory_order_relaxed); }

    /** Solo para tests. */
    float sampleAt(int32_t layer, int32_t index) const {
        return mLayers[static_cast<size_t>(layer) * mLoopFrames + index];
    }

private:
    enum class WriteMode { Replace, Add };

    float mixAt(int32_t index, int32_t numLayers) const;
    void write(int32_t slot, WriteMode mode, int32_t index, float value);
    void applyUndo();
    void onWrap();
    void startRecordingIfEnabled();

    std::vector<float> mLayers;  // maxLayers * loopFrames, aplanado
    std::vector<float> mClickSamples;
    int32_t mLoopFrames = 0;
    int32_t mMaxLayers = 0;
    int32_t mLatency = 0;

    // Estado del hilo de audio.
    int32_t mPos = 0;
    int64_t mCycle = 0;
    int32_t mCommitted = 0;
    int32_t mRecSlot = -1;
    WriteMode mRecMode = WriteMode::Replace;
    int32_t mTailSlot = -1;
    WriteMode mTailMode = WriteMode::Replace;
    int32_t mTailMixLayers = 0;

    // Controles y estado publicado.
    std::atomic<bool> mOverdub{true};
    std::atomic<bool> mClick{true};
    std::atomic<int32_t> mUndoRequests{0};
    std::atomic<int32_t> mCommittedPublic{0};
    std::atomic<int32_t> mPosPublic{0};
    std::atomic<bool> mRecordingPublic{false};
};

/** Saturación suave: lineal hasta 0.8 y curva tanh arriba. */
float softClip(float x);

}  // namespace loopcam
