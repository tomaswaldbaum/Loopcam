#include <jni.h>

#include <string>

#include "LoopEngine.h"

using loopcam::EngineStatus;
using loopcam::LoopEngine;

namespace {
LoopEngine *fromHandle(jlong handle) {
    return reinterpret_cast<LoopEngine *>(handle);
}

// Orden de los campos en el LongArray de status (ver NativeAudioEngine.kt).
enum StatusField {
    kCommittedLayers,
    kMaxLayers,
    kPosition,
    kLoopFrames,
    kSampleRate,
    kLatencyFrames,
    kRecordingLayer,
    kDisconnected,
    kDroppedFrames,
    kFileStartNanos,
    kStatusFieldCount
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new LoopEngine());
}

JNIEXPORT jboolean JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeStart(JNIEnv *env, jobject, jlong handle,
                                                         jint sampleRate, jdouble loopSeconds,
                                                         jstring wavPath, jint latencyOffsetFrames) {
    const char *path = env->GetStringUTFChars(wavPath, nullptr);
    const std::string pathString(path);
    env->ReleaseStringUTFChars(wavPath, path);
    return fromHandle(handle)->startSession(sampleRate, loopSeconds, pathString, latencyOffsetFrames)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    fromHandle(handle)->stopSession();
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeSetOverdub(JNIEnv *, jobject, jlong handle,
                                                              jboolean enabled) {
    fromHandle(handle)->setOverdub(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeSetClickEnabled(JNIEnv *, jobject, jlong handle,
                                                                   jboolean enabled) {
    fromHandle(handle)->setClickEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeUndo(JNIEnv *, jobject, jlong handle) {
    fromHandle(handle)->undoLastLayer();
}

JNIEXPORT jlongArray JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeGetStatus(JNIEnv *env, jobject, jlong handle) {
    const EngineStatus s = fromHandle(handle)->status();
    jlong values[kStatusFieldCount];
    values[kCommittedLayers] = s.committedLayers;
    values[kMaxLayers] = s.maxLayers;
    values[kPosition] = s.position;
    values[kLoopFrames] = s.loopFrames;
    values[kSampleRate] = s.sampleRate;
    values[kLatencyFrames] = s.latencyFrames;
    values[kRecordingLayer] = s.recordingLayer ? 1 : 0;
    values[kDisconnected] = s.disconnected ? 1 : 0;
    values[kDroppedFrames] = s.droppedFrames;
    values[kFileStartNanos] = s.fileStartNanos;
    jlongArray result = env->NewLongArray(kStatusFieldCount);
    env->SetLongArrayRegion(result, 0, kStatusFieldCount, values);
    return result;
}

JNIEXPORT void JNICALL
Java_io_loopcam_core_audio_NativeAudioEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
