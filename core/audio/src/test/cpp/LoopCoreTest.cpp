#include "LoopCore.h"

#include <cmath>
#include <vector>

#include <gtest/gtest.h>

namespace loopcam {
namespace {

constexpr int32_t kLoop = 100;
constexpr int32_t kLatency = 10;
constexpr int32_t kBlock = 16;
constexpr float kImpulse = 0.5f;

struct Processed {
    std::vector<float> out;
    std::vector<float> file;
};

// Procesa `input` en bloques, como lo haría el callback de audio.
Processed process(LoopCore &core, const std::vector<float> &input) {
    Processed run;
    std::vector<float> out(kBlock), file(kBlock);
    for (size_t start = 0; start < input.size(); start += kBlock) {
        const auto n = static_cast<int32_t>(std::min<size_t>(kBlock, input.size() - start));
        const int32_t fileFrames = core.process(input.data() + start, out.data(), file.data(), n);
        run.out.insert(run.out.end(), out.begin(), out.begin() + n);
        run.file.insert(run.file.end(), file.begin(), file.begin() + fileFrames);
    }
    return run;
}

class LoopCoreTest : public ::testing::Test {
protected:
    void SetUp() override {
        core.setClickEnabled(false);
        core.setOverdub(true);
        core.prepare(kLoop, /*maxLayers=*/4, /*sampleRate=*/48000);
        core.setLatencyFrames(kLatency);
    }

    LoopCore core;
};

TEST_F(LoopCoreTest, inputIsWrittenAtPositionShiftedByLatency) {
    std::vector<float> input(kLoop, 0.0f);
    input[30] = kImpulse;
    process(core, input);
    EXPECT_FLOAT_EQ(kImpulse, core.sampleAt(0, 30 - kLatency));
    EXPECT_FLOAT_EQ(0.0f, core.sampleAt(0, 30));
}

TEST_F(LoopCoreTest, layerIsCommittedAtWrapAndPlaysOnNextCycle) {
    std::vector<float> input(2 * kLoop, 0.0f);
    input[30] = kImpulse;
    const Processed run = process(core, input);

    // Cada vuelta completa con overdub cierra una capa (la segunda está vacía).
    EXPECT_EQ(2, core.committedLayers());
    // Durante la vuelta 0 no suena nada; en la vuelta 1 suena en la posición compensada.
    for (int32_t i = 0; i < kLoop; ++i) EXPECT_FLOAT_EQ(0.0f, run.out[i]) << i;
    EXPECT_FLOAT_EQ(kImpulse, run.out[kLoop + 30 - kLatency]);
}

TEST_F(LoopCoreTest, tailOfLayerIsWrittenAtStartOfNextCycle) {
    std::vector<float> input(kLoop + kLatency, 0.0f);
    input[kLoop + 5] = kImpulse;  // llega en la vuelta 1, pero se tocó al final de la 0
    process(core, input);
    EXPECT_FLOAT_EQ(kImpulse, core.sampleAt(0, kLoop - kLatency + 5));
    EXPECT_FLOAT_EQ(0.0f, core.sampleAt(1, 5));
}

TEST_F(LoopCoreTest, preRollBeforeLatencyIsDroppedFromFile) {
    std::vector<float> input(kLoop, 0.0f);
    const Processed run = process(core, input);
    EXPECT_EQ(static_cast<size_t>(kLoop - kLatency), run.file.size());
}

TEST_F(LoopCoreTest, fileContainsWhatWasHeardPlusLiveInput) {
    std::vector<float> input(2 * kLoop, 0.0f);
    input[30] = kImpulse;          // capa 0, índice 20
    input[kLoop + 50] = 0.25f;     // en vuelta 1, índice 40
    const Processed run = process(core, input);

    // La vuelta 0 empieza en el archivo en el índice 0 del loop.
    EXPECT_FLOAT_EQ(kImpulse, run.file[20]);
    // En la vuelta 1 el archivo tiene la capa 0 (escuchada) + el input en vivo.
    const size_t cycle1 = kLoop;  // (L - latency) de la vuelta 0 + latency de cola
    EXPECT_FLOAT_EQ(kImpulse, run.file[cycle1 + 20]);
    EXPECT_FLOAT_EQ(0.25f, run.file[cycle1 + 40]);
}

TEST_F(LoopCoreTest, layersAccumulateAndMixIsSoftClipped) {
    std::vector<float> input(4 * kLoop, 0.0f);
    input[30] = 0.5f;
    input[kLoop + 30] = 0.5f;
    const Processed run = process(core, input);
    EXPECT_EQ(4, core.committedLayers());
    // 0.5 + 0.5 = 1.0 supera el umbral lineal, así que se satura suavemente.
    EXPECT_FLOAT_EQ(softClip(1.0f), run.out[3 * kLoop + 20]);
    EXPECT_LT(run.out[3 * kLoop + 20], 1.0f);
}

TEST_F(LoopCoreTest, undoDiscardsLastLayerAndRestartsRecordingNextCycle) {
    std::vector<float> input(kLoop + kLatency + 20, 0.0f);
    input[30] = kImpulse;
    process(core, input);
    ASSERT_EQ(1, core.committedLayers());

    core.requestUndo();
    std::vector<float> rest(kLoop - kLatency - 20, 0.0f);
    rest[0] = kImpulse;  // se ignora: la grabación está suspendida hasta la próxima vuelta
    const Processed run = process(core, rest);
    EXPECT_EQ(0, core.committedLayers());
    for (float s : run.out) EXPECT_FLOAT_EQ(0.0f, s);

    std::vector<float> next(kLoop, 0.0f);
    next[60] = kImpulse;
    process(core, next);
    EXPECT_EQ(1, core.committedLayers());
    EXPECT_FLOAT_EQ(kImpulse, core.sampleAt(0, 60 - kLatency));
    EXPECT_FLOAT_EQ(0.0f, core.sampleAt(0, 20));
}

TEST_F(LoopCoreTest, whenLayersAreFullNewCyclesAreAddedToLastLayer) {
    LoopCore small;
    small.setClickEnabled(false);
    small.prepare(kLoop, /*maxLayers=*/2, 48000);
    small.setLatencyFrames(0);
    const std::vector<float> input(4 * kLoop, 0.1f);
    process(small, input);
    EXPECT_EQ(2, small.committedLayers());
    EXPECT_FLOAT_EQ(0.1f, small.sampleAt(0, 50));
    EXPECT_NEAR(0.3f, small.sampleAt(1, 50), 1e-6f);  // vueltas 1, 2 y 3
}

TEST_F(LoopCoreTest, overdubOffKeepsPlayingWithoutRecording) {
    core.setOverdub(false);
    core.prepare(kLoop, 4, 48000);
    const std::vector<float> input(3 * kLoop, 0.3f);
    process(core, input);
    EXPECT_EQ(0, core.committedLayers());
    EXPECT_FALSE(core.isRecordingLayer());
}

TEST_F(LoopCoreTest, latencyIsClampedToHalfTheLoop) {
    core.setLatencyFrames(kLoop);
    EXPECT_EQ(kLoop / 2, core.latencyFrames());
}

TEST_F(LoopCoreTest, clickSoundsOnlyAtLoopStart) {
    core.setClickEnabled(true);
    const std::vector<float> input(kLoop, 0.0f);
    const Processed run = process(core, input);
    float clickEnergy = 0.0f;
    for (int32_t i = 0; i < kLoop / 2; ++i) clickEnergy += std::fabs(run.out[i]);
    EXPECT_GT(clickEnergy, 0.0f);
    for (int32_t i = kLoop / 2; i < kLoop; ++i) EXPECT_FLOAT_EQ(0.0f, run.out[i]);
}

TEST(SoftClipTest, isLinearBelowThresholdAndBoundedAbove) {
    EXPECT_FLOAT_EQ(0.5f, softClip(0.5f));
    EXPECT_FLOAT_EQ(-0.7f, softClip(-0.7f));
    EXPECT_LT(softClip(1.0f), 1.0f);
    EXPECT_LE(softClip(10.0f), 1.0f);
    EXPECT_GE(softClip(-10.0f), -1.0f);
}

}  // namespace
}  // namespace loopcam
