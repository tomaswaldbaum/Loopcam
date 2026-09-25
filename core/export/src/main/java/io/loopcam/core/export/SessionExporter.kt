package io.loopcam.core.export

import java.io.File

/**
 * Qué combinar al final de una sesión.
 *
 * @param audioOffsetNanos cuánto después del inicio del video arrancó el audio
 *   (negativo si arrancó antes).
 */
data class ExportRequest(
    val video: File,
    val audioMix: File,
    val audioOffsetNanos: Long,
    val output: File,
)

/** Une el video continuo con la mezcla de loops en un único MP4 (Media3 Transformer). */
interface SessionExporter {
    suspend fun export(request: ExportRequest): Result<File>
}
