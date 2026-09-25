package io.loopcam.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.loopcam.core.audio.AudioEngine
import io.loopcam.core.audio.NativeAudioEngine
import io.loopcam.core.export.Media3SessionExporter
import io.loopcam.core.export.SessionExporter
import io.loopcam.core.video.CameraVideoRecorder
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioEngine(): AudioEngine = NativeAudioEngine()

    @Provides
    @Singleton
    fun provideVideoRecorder(@ApplicationContext context: Context): CameraVideoRecorder =
        CameraVideoRecorder(context)

    @Provides
    @Singleton
    fun provideSessionExporter(@ApplicationContext context: Context): SessionExporter =
        Media3SessionExporter(context)
}
