#pragma once

#include <memory>

#include <oboe/Oboe.h>

namespace loopcam {

// Motor de loops. En esta etapa solo abre un stream de salida de baja latencia
// que emite silencio, para validar el camino Oboe/AAudio en el dispositivo.
// El full-duplex, las capas y el overdub llegan en la siguiente iteración.
class LoopEngine : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    // Devuelve true si el stream quedó corriendo.
    bool start(int32_t sampleRate, int32_t loopFrames);
    void stop();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mOutput;
    int32_t mLoopFrames = 0;
};

}  // namespace loopcam
