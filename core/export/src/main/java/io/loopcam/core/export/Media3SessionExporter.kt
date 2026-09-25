package io.loopcam.core.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export con Media3 Transformer: una secuencia con el video (sin recodificar) y otra
 * con la mezcla ya alineada, que se codifica a AAC.
 */
@OptIn(UnstableApi::class)
class Media3SessionExporter(private val context: Context) : SessionExporter {

    override suspend fun export(request: ExportRequest, onProgress: (Float) -> Unit): Result<File> = try {
        Result.success(exportOrThrow(request, onProgress))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun exportOrThrow(request: ExportRequest, onProgress: (Float) -> Unit): File {
        val aligned = File(request.output.parentFile, ALIGNED_AUDIO_FILE)
        withContext(Dispatchers.IO) {
            val format = WavAligner.readFormat(request.audioMix)
            WavAligner.align(
                input = request.audioMix,
                output = aligned,
                offsetFrames = WavAligner.nanosToFrames(request.audioOffsetNanos, format.sampleRate),
                targetFrames = WavAligner.nanosToFrames(request.videoDurationNanos, format.sampleRate),
            )
            request.output.delete()
        }
        try {
            withContext(Dispatchers.Main) { transform(buildComposition(request.video, aligned), request.output, onProgress) }
        } finally {
            aligned.delete()
        }
        return request.output
    }

    private fun buildComposition(video: File, audio: File): Composition {
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video)))
            .setRemoveAudio(true)
            .build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audio))).build()
        return Composition.Builder(
            EditedMediaItemSequence.Builder(videoItem).build(),
            EditedMediaItemSequence.Builder(audioItem).build(),
        )
            // El video ya está codificado por CameraX: se copia tal cual.
            .setTransmuxVideo(true)
            .build()
    }

    /** Debe correr en el hilo principal: el Transformer se usa desde el Looper donde se creó. */
    private suspend fun transform(composition: Composition, output: File, onProgress: (Float) -> Unit) {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        done.completeExceptionally(exportException)
                    }
                },
            )
            .build()
        transformer.start(composition, output.absolutePath)

        coroutineScope {
            val progressHolder = ProgressHolder()
            val progressJob = launch {
                while (isActive) {
                    if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress / 100f)
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            } finally {
                progressJob.cancel()
            }
        }
        onProgress(1f)
    }

    private companion object {
        const val ALIGNED_AUDIO_FILE = "mix_aligned.wav"
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
