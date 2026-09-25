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

### Motor de loops (`core/audio/src/main/cpp`)

```
Oboe FullDuplexStream (mic + auriculares, LowLatency/Exclusive, float mono)
   └─ onBothStreamsReady ──► LoopCore.process(in, out, fileOut)
                               ├─ out: suma de capas terminadas (+ click al inicio de la vuelta)
                               ├─ capa en grabación[pos - latencia] = input
                               └─ fileOut: lo que se escuchaba + input en vivo (tiempo de input)
                            ──► SpscRingBuffer (lock-free) ──► hilo AudioFileWriter ──► mix.wav (PCM 16-bit)
```

- **Longitud fija:** cada vuelta completa con *overdub* activo cierra una capa, que empieza a sonar en la vuelta siguiente.
- **Compensación de latencia:** al arrancar, los streams corren 300 ms "desarmados" y después se mide la latencia de ida y vuelta (`calculateLatencyMillis` de entrada + salida). El input se escribe en `pos - latencia`.
- **Capas:** hasta 16, con un presupuesto de 64 MB. Con loops largos entran menos. Al llenarse, las vueltas nuevas se suman sobre la última capa.
- **Deshacer:** descarta la última capa terminada y la que se está grabando; la grabación sigue desde la vuelta siguiente.
### Sincronía y export

1. **Audio:** `fileStartNanos` es el instante de captura del primer sample del WAV (`getTimestamp` del input de AAudio, CLOCK_MONOTONIC).
2. **Video:** con `Camera2Interop` se lee el `SENSOR_TIMESTAMP` del primer frame posterior al evento `Start` de CameraX. `SensorClock` lo pasa a CLOCK_MONOTONIC, porque algunos sensores usan BOOTTIME. Si no llega, se usa la hora del evento y la UI avisa que la sincronía es aproximada.
3. **Offset:** `audioOffsetNanos = fileStartNanos - videoStartNanos`.
4. **Alineación:** al detener, se espera a que CameraX finalice el MP4. Después `WavAligner` agrega silencio al principio o recorta el WAV según el offset, y lo ajusta a la duración exacta del video.
5. **Export:** Media3 Transformer arma una `Composition` con dos secuencias: el video, que se copia sin recodificar (`setTransmuxVideo`), y la mezcla alineada, que se codifica a AAC.
6. **Galería:** `GallerySaver` copia el resultado a `Movies/LoopCam` vía MediaStore, sin pedir permisos, y la UI ofrece abrirlo.

Cada sesión queda en `Android/data/io.loopcam.app/files/Movies/sessions/<timestamp>/`, con `video.mp4`, `mix.wav`, `loopcam.mp4` (el resultado) y `session.properties`.

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
adb logcat -s LoopEngine         # ver qué stream abrió Oboe (AAudio/LowLatency/Exclusive) y la latencia medida

# Tests nativos del motor (en la PC, sin Android)
cmake -S core/audio/src/test/cpp -B build/native-tests
cmake --build build/native-tests && ctest --test-dir build/native-tests --output-on-failure
```

## Estado

- [x] Esqueleto multi-módulo, version catalog, Hilt, Compose, permisos, servicio en primer plano
- [x] Stream Oboe de baja latencia (silencio) para validar el camino nativo
- [x] Preview y grabación de video sin audio con CameraX
- [x] Motor full-duplex: capas, overdub, deshacer, click, compensación de latencia, WAV en streaming
- [x] Timestamp real del inicio del audio (AAudio `getTimestamp`)
- [x] Timestamp de sensor del primer frame de video y offset de sincronía
- [x] Export con Media3 Transformer → galería (MediaStore)
- [ ] Grabar con la pantalla apagada (ligar la cámara al servicio)
- [ ] Calibración manual de latencia y de sincronía desde la UI
