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
        core.setMetronome(false, false);
        core.setOverdub(true);
        core.prepare(params());
        core.setLatencyFrames(kLatency);
    }

    static LoopParams params(int32_t maxLayers = 4, int32_t countInBeats = 0) {
        LoopParams p;
        p.loopFrames = kLoop;
        p.beatsPerLoop = 4;  // 25 frames por tiempo
        p.beatsPerBar = 2;
        p.countInBeats = countInBeats;
        p.maxLayers = maxLayers;
        p.sampleRate = 48000;
        return p;
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

TEST_F(LoopCoreTest, stopsRecordingWhenMaxLayersIsReached) {
    core.prepare(params(/*maxLayers=*/2));
    core.setLatencyFrames(0);
    process(core, std::vector<float>(2 * kLoop, 0.1f));
    EXPECT_EQ(2, core.committedLayers());
    EXPECT_TRUE(core.layersFull());
    EXPECT_FALSE(core.isRecordingLayer());

    // Las vueltas siguientes no graban nada: las capas quedan intactas.
    process(core, std::vector<float>(2 * kLoop, 0.7f));
    EXPECT_EQ(2, core.committedLayers());
    EXPECT_FLOAT_EQ(0.1f, core.sampleAt(0, 50));
    EXPECT_FLOAT_EQ(0.1f, core.sampleAt(1, 50));
}

TEST_F(LoopCoreTest, undoFreesALayerWhenFull) {
    core.prepare(params(/*maxLayers=*/1));
    core.setLatencyFrames(0);
    process(core, std::vector<float>(kLoop, 0.1f));
    ASSERT_TRUE(core.layersFull());

    core.requestUndo();
    process(core, std::vector<float>(kLoop, 0.0f));  // resto de la vuelta: suspendida
    EXPECT_EQ(0, core.committedLayers());
    EXPECT_FALSE(core.layersFull());

    process(core, std::vector<float>(kLoop, 0.3f));
    EXPECT_EQ(1, core.committedLayers());
    EXPECT_FLOAT_EQ(0.3f, core.sampleAt(0, 50));
}

TEST_F(LoopCoreTest, overdubOffKeepsPlayingWithoutRecording) {
    core.setOverdub(false);
    core.prepare(params());
    const std::vector<float> input(3 * kLoop, 0.3f);
    process(core, input);
    EXPECT_EQ(0, core.committedLayers());
    EXPECT_FALSE(core.isRecordingLayer());
}

TEST_F(LoopCoreTest, latencyIsClampedToHalfTheLoop) {
    core.setLatencyFrames(kLoop);
    EXPECT_EQ(kLoop / 2, core.latencyFrames());
}

TEST_F(LoopCoreTest, loopIsRoundedToWholeBeats) {
    LoopParams p = params();
    p.loopFrames = 103;  // no divisible por 4 tiempos
    core.prepare(p);
    EXPECT_EQ(25, core.beatFrames());
    EXPECT_EQ(100, core.loopFrames());
}

TEST_F(LoopCoreTest, metronomeClicksOnEveryBeatWithAccentOnBarStart) {
    core.setMetronome(false, true);
    const Processed run = process(core, std::vector<float>(kLoop, 0.0f));
    const int32_t beat = core.beatFrames();
    const int32_t clickLength = core.clickLength();
    ASSERT_GT(clickLength, 1);
    for (int32_t b = 0; b < 4; ++b) {
        const bool accent = (b % 2) == 0;  // compás de 2 tiempos
        for (int32_t i = 0; i < clickLength; ++i) {
            EXPECT_FLOAT_EQ(softClip(core.clickSample(accent, i)), run.out[b * beat + i]) << b << ":" << i;
        }
        for (int32_t i = clickLength; i < beat; ++i) EXPECT_FLOAT_EQ(0.0f, run.out[b * beat + i]);
    }
    EXPECT_NE(core.clickSample(true, 1), core.clickSample(false, 1));
}

TEST_F(LoopCoreTest, metronomeNeverReachesTheFile) {
    core.prepare(params(4, /*countInBeats=*/2));
    core.setLatencyFrames(kLatency);
    core.setMetronome(true, true);
    const Processed run = process(core, std::vector<float>(2 * core.beatFrames() + 2 * kLoop, 0.0f));
    ASSERT_FALSE(run.file.empty());
    for (float s : run.file) EXPECT_FLOAT_EQ(0.0f, s);
    float outEnergy = 0.0f;
    for (float s : run.out) outEnergy += std::fabs(s);
    EXPECT_GT(outEnergy, 0.0f);
}

TEST_F(LoopCoreTest, countInPlaysOnlyMetronomeThenLoopStartsOnTheExactFrame) {
    core.prepare(params(4, /*countInBeats=*/2));
    core.setLatencyFrames(0);
    core.setMetronome(true, false);
    const int32_t countIn = core.countInFrames();
    ASSERT_EQ(50, countIn);
    EXPECT_EQ(LoopCore::Phase::CountIn, core.phase());

    std::vector<float> input(countIn + kLoop, 0.0f);
    input[10] = kImpulse;            // durante la cuenta: se descarta
    input[countIn + 30] = kImpulse;  // índice 30 de la capa 0
    const Processed run = process(core, input);

    EXPECT_EQ(LoopCore::Phase::Looping, core.phase());
    EXPECT_EQ(static_cast<size_t>(kLoop), run.file.size());  // el archivo arranca con el loop
    EXPECT_FLOAT_EQ(kImpulse, run.file[30]);
    EXPECT_FLOAT_EQ(kImpulse, core.sampleAt(0, 30));
    EXPECT_FLOAT_EQ(0.0f, core.sampleAt(0, 10));
    // Clicks en la cuenta (acento en el primer tiempo) y silencio en el loop.
    EXPECT_FLOAT_EQ(core.clickSample(true, 1), run.out[1]);
    EXPECT_FLOAT_EQ(core.clickSample(false, 1), run.out[core.beatFrames() + 1]);
    for (int32_t i = countIn; i < countIn + kLoop; ++i) EXPECT_FLOAT_EQ(0.0f, run.out[i]);
}

TEST_F(LoopCoreTest, countInReportsRemainingBeats) {
    core.prepare(params(4, /*countInBeats=*/3));
    EXPECT_EQ(3, core.countInBeatsRemaining());
    process(core, std::vector<float>(core.beatFrames() + 1, 0.0f));
    EXPECT_EQ(2, core.countInBeatsRemaining());
    process(core, std::vector<float>(2 * core.beatFrames(), 0.0f));
    EXPECT_EQ(0, core.countInBeatsRemaining());
    EXPECT_EQ(LoopCore::Phase::Looping, core.phase());
}

TEST_F(LoopCoreTest, reportsCurrentBeatAndCycle) {
    process(core, std::vector<float>(kLoop + 2 * core.beatFrames() + 3, 0.0f));
    EXPECT_EQ(1, core.cycle());
    EXPECT_EQ(2, core.currentBeat());
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
