#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

namespace loopcam {

/** Parámetros de una sesión de loop. */
struct LoopParams {
    int32_t loopFrames = 0;     // se redondea a un múltiplo de beatsPerLoop
    int32_t beatsPerLoop = 4;
    int32_t beatsPerBar = 4;
    int32_t countInBeats = 0;   // 0 = sin cuenta regresiva
    int32_t maxLayers = 1;
    int32_t sampleRate = 48000;
};

/**
 * Lógica pura del looper (sin Oboe ni I/O) para poder testearla en el host.
 *
 * Modelo:
 * - Opcionalmente, una cuenta regresiva de `countInBeats` tiempos en la que solo
 *   suena el metrónomo: no se graban capas ni se escribe el archivo.
 * - Después, el loop de longitud fija (L frames = beatsPerLoop × beatFrames). Cada
 *   vuelta completa con overdub activo graba una capa nueva, que se suma a la mezcla
 *   al terminar la vuelta. Al llegar a `maxLayers` se deja de grabar.
 * - Compensación de latencia: el sample que entra ahora fue tocado por el usuario
 *   escuchando la posición (pos - latency), así que se escribe ahí. Cada capa recibe
 *   exactamente L samples: L - latency en su vuelta y el resto ("cola") durante los
 *   primeros `latency` frames de la vuelta siguiente, por eso se escribe con '='.
 * - El metrónomo solo va a la salida (auriculares), nunca al archivo.
 *
 * Hilos: prepare()/setLatencyFrames() con el audio detenido; process() en el hilo
 * de audio; el resto desde cualquier hilo.
 */
class LoopCore {
public:
    enum class Phase : int32_t { CountIn = 0, Looping = 1 };

    void prepare(const LoopParams &params);
    void setLatencyFrames(int32_t latencyFrames);

    /**
     * Procesa un bloque.
     * @param in       input del micrófono (numFrames samples, mono)
     * @param out      lo que suena en los auriculares (capas + metrónomo)
     * @param fileOut  mezcla alineada en "tiempo de input" (lo que se escuchaba +
     *                 lo que se tocaba, sin metrónomo) para el archivo. Capacidad >= numFrames.
     * @return cantidad de samples escritos en fileOut.
     */
    int32_t process(const float *in, float *out, float *fileOut, int32_t numFrames);

    void setOverdub(bool enabled) { mOverdub.store(enabled, std::memory_order_relaxed); }
    void setMetronome(bool inCountIn, bool whileLooping) {
        mMetronomeCountIn.store(inCountIn, std::memory_order_relaxed);
        mMetronomeLooping.store(whileLooping, std::memory_order_relaxed);
    }
    /** Descarta la última capa terminada y la que se está grabando. */
    void requestUndo() { mUndoRequests.fetch_add(1, std::memory_order_relaxed); }

    int32_t loopFrames() const { return mLoopFrames; }
    int32_t beatFrames() const { return mBeatFrames; }
    int32_t beatsPerLoop() const { return mBeatsPerLoop; }
    int32_t beatsPerBar() const { return mBeatsPerBar; }
    int32_t countInBeats() const { return mCountInBeats; }
    int32_t countInFrames() const { return mCountInFrames; }
    int32_t maxLayers() const { return mMaxLayers; }
    int32_t latencyFrames() const { return mLatency; }

    Phase phase() const { return static_cast<Phase>(mPhasePublic.load(std::memory_order_relaxed)); }
    int32_t committedLayers() const { return mCommittedPublic.load(std::memory_order_relaxed); }
    /** Posición dentro del loop (o dentro de la cuenta regresiva). */
    int32_t position() const { return mPosPublic.load(std::memory_order_relaxed); }
    /** Tiempo actual: dentro del loop, o dentro de la cuenta regresiva. */
    int32_t currentBeat() const;
    /** Tiempos que faltan para que arranque el loop (incluye el actual); 0 fuera de la cuenta. */
    int32_t countInBeatsRemaining() const;
    int64_t cycle() const { return mCyclePublic.load(std::memory_order_relaxed); }
    bool isRecordingLayer() const { return mRecordingPublic.load(std::memory_order_relaxed); }
    bool layersFull() const { return mLayersFullPublic.load(std::memory_order_relaxed); }

    /** Solo para tests. */
    float sampleAt(int32_t layer, int32_t index) const {
        return mLayers[static_cast<size_t>(layer) * mLoopFrames + index];
    }
    /** Solo para tests: el click que suena al inicio de un tiempo. */
    float clickSample(bool accent, int32_t index) const {
        return accent ? mAccentClick[index] : mNormalClick[index];
    }
    int32_t clickLength() const { return static_cast<int32_t>(mNormalClick.size()); }

private:
    float mixAt(int32_t index, int32_t numLayers) const;
    float metronomeAt(int32_t framesIntoSection) const;
    void applyUndo();
    void onWrap();
    void startRecordingIfEnabled();

    std::vector<float> mLayers;  // maxLayers * loopFrames, aplanado
    std::vector<float> mAccentClick;
    std::vector<float> mNormalClick;
    int32_t mLoopFrames = 0;
    int32_t mBeatFrames = 0;
    int32_t mBeatsPerLoop = 1;
    int32_t mBeatsPerBar = 1;
    int32_t mCountInBeats = 0;
    int32_t mCountInFrames = 0;
    int32_t mMaxLayers = 1;
    int32_t mLatency = 0;

    // Estado del hilo de audio.
    int32_t mCountInPos = 0;
    int32_t mPos = 0;
    int64_t mCycle = 0;
    int32_t mCommitted = 0;
    int32_t mRecSlot = -1;
    int32_t mTailSlot = -1;
    int32_t mTailMixLayers = 0;

    // Controles y estado publicado.
    std::atomic<bool> mOverdub{true};
    std::atomic<bool> mMetronomeCountIn{true};
    std::atomic<bool> mMetronomeLooping{true};
    std::atomic<int32_t> mUndoRequests{0};
    std::atomic<int32_t> mPhasePublic{0};
    std::atomic<int32_t> mCommittedPublic{0};
    std::atomic<int32_t> mPosPublic{0};
    std::atomic<int64_t> mCyclePublic{0};
    std::atomic<bool> mRecordingPublic{false};
    std::atomic<bool> mLayersFullPublic{false};
};

/** Saturación suave: lineal hasta 0.8 y curva tanh arriba. */
float softClip(float x);

}  // namespace loopcam
