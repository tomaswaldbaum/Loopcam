#include <jni.h>

#include "LoopEngine.h"

using loopcam::LoopEngine;

namespace {
LoopEngine *fromHandle(jlong handle) {
    return reinterpret_cast<LoopEngine *>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new LoopEngine());
}

JNIEXPORT jboolean JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeStart(JNIEnv *, jobject, jlong handle,
                                                         jint sampleRate, jint loopFrames) {
    return fromHandle(handle)->start(sampleRate, loopFrames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    fromHandle(handle)->stop();
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    LoopEngine *engine = fromHandle(handle);
    engine->stop();
    delete engine;
}

}  // extern "C"
