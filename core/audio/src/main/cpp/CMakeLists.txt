cmake_minimum_required(VERSION 3.22.1)
project(loopcam_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(loopcam_audio SHARED
        jni_bridge.cpp
        LoopEngine.cpp
        core/LoopCore.cpp
        core/WavWriter.cpp
        core/AudioFileWriter.cpp)

target_compile_options(loopcam_audio PRIVATE -Wall -Wextra -O2)
target_link_libraries(loopcam_audio PRIVATE oboe::oboe log)
