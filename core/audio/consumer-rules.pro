# Los nombres de los métodos JNI deben sobrevivir a R8.
-keepclasseswithmembernames class io.loopcam.core.audio.** {
    native <methods>;
}
