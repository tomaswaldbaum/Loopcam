import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.loopcam.core.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // Oboe (Prefab) requiere la STL compartida.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // -Ploopcam.abis=arm64-v8a limita la compilación nativa (el CI lo usa para una APK más liviana).
            val abis = (findProperty("loopcam.abis") as String?)?.split(",")
                ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            abiFilters += abis
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.oboe)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
