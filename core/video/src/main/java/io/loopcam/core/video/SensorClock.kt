package io.loopcam.core.video

/**
 * Convierte el SENSOR_TIMESTAMP de Camera2 al reloj de System.nanoTime()
 * (CLOCK_MONOTONIC), que es el mismo que usa el motor de audio.
 *
 * Según el dispositivo, el sensor usa CLOCK_BOOTTIME (fuente REALTIME) o una fuente
 * "UNKNOWN" que en la práctica suele ser CLOCK_MONOTONIC. En vez de confiar en la
 * característica declarada, se elige el reloj con el que el frame tiene una edad
 * plausible (entre 0 y [MAX_FRAME_AGE_NANOS]).
 */
internal object SensorClock {
    const val MAX_FRAME_AGE_NANOS = 2_000_000_000L

    fun toMonotonic(sensorNanos: Long, nowMonotonicNanos: Long, nowBoottimeNanos: Long): Long {
        val monotonicAgeIsPlausible = (nowMonotonicNanos - sensorNanos) in 0..MAX_FRAME_AGE_NANOS
        val boottimeAgeIsPlausible = (nowBoottimeNanos - sensorNanos) in 0..MAX_FRAME_AGE_NANOS
        return if (boottimeAgeIsPlausible && !monotonicAgeIsPlausible) {
            sensorNanos - (nowBoottimeNanos - nowMonotonicNanos)
        } else {
            sensorNanos
        }
    }
}
