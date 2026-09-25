# Tests nativos del motor de audio que corren en el host (sin Android):
#   cmake -S core/audio/src/test/cpp -B build/native-tests
#   cmake --build build/native-tests && ctest --test-dir build/native-tests --output-on-failure
cmake_minimum_required(VERSION 3.22.1)
project(loopcam_audio_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(googletest)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/core)

add_executable(audio_core_tests
        LoopCoreTest.cpp
        SpscRingBufferTest.cpp
        WavWriterTest.cpp
        ${CORE_DIR}/LoopCore.cpp
        ${CORE_DIR}/WavWriter.cpp
        ${CORE_DIR}/AudioFileWriter.cpp)
target_include_directories(audio_core_tests PRIVATE ${CORE_DIR})
target_compile_options(audio_core_tests PRIVATE -Wall -Wextra -Werror)
target_link_libraries(audio_core_tests PRIVATE GTest::gtest_main)

enable_testing()
include(GoogleTest)
gtest_discover_tests(audio_core_tests)
