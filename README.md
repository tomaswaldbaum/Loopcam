# LoopCam

App Android que graba **video continuo** y, en simultáneo, **audio en capas de loop**: la primera capa se graba durante N segundos y empieza a sonar en loop, y cada vuelta siguiente se graba encima. El resultado es un MP4 con el video completo y la mezcla que sonaba en cada momento.

## Arquitectura

```
app/            UI en Compose, Hilt, SessionController, RecordingService (foreground camera|microphone)
core/audio/     Motor de loops en C++ con Oboe (AAudio, baja latencia) + puente JNI
core/video/     Preview y grabación de video sin audio con CameraX
core/export/    Une video + mezcla de audio en un MP4 (Media3 Transformer)
```

- El audio nunca pasa por el pipeline de la cámara. Lo produce el motor nativo y se muxea al final.
- Video y audio registran su inicio con el mismo reloj (`CLOCK_MONOTONIC` / `System.nanoTime()`), y el export los alinea con esa diferencia.
- El callback de audio es de tiempo real: sin locks, sin allocations y sin I/O.

## Requisitos

- Android Studio (última versión estable), con el JDK embebido.
- SDK Manager: la API 36, el **NDK (Side by side)** y **CMake 3.22.1**.
- Un teléfono Android 10+ físico con la depuración USB activada. El emulador no sirve para medir latencia.
- Auriculares con cable. Con el parlante, el micrófono vuelve a grabar el loop, y con Bluetooth la latencia es demasiado alta.

## Uso

```bash
./gradlew assembleDebug          # compila
./gradlew testDebugUnitTest      # tests unitarios
./gradlew installDebug           # instala en el teléfono conectado
adb logcat -s LoopEngine         # ver qué stream abrió Oboe (AAudio/LowLatency/Exclusive)
```

## Estado

- [x] Esqueleto multi-módulo, version catalog, Hilt, Compose, permisos, servicio en primer plano
- [x] Stream Oboe de baja latencia (silencio) para validar el camino nativo
- [x] Preview y grabación de video sin audio con CameraX
- [ ] Motor full-duplex: capas, overdub, compensación de latencia, WAV en streaming
- [ ] Sincronía por timestamps reales (primer frame de video / frame 0 de AAudio)
- [ ] Export con Media3 Transformer → MediaStore
