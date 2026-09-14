L Studio

Real-Time Android Microtonal Synthesizer, Looper, Drum Machine, Microphone Recorder and Performance Console

L Studio is a real-time Android music application built around a custom Kotlin audio engine and DSP pipeline.

The application combines a microtonal synthesizer, real-time DSP effects, PCM loop recording/playback, drum sequencing, microphone recording and monitoring, performance-pad modulation, session persistence, MIDI export, and an integrated audio-performance benchmark.

The project is currently implemented as a single Android application module and uses a custom real-time audio thread rather than a high-level audio framework.

---

1. Project Status

Repository: "dudichatam-max/psychic-winner"

Application ID: "com.microtonal.synth"

Application name: "L Studio"

Root Gradle project: "MicroScaleSynth"

Default branch: "main"

Current Android target: SDK 34

Minimum Android version: API 24

JVM: Java 17

Kotlin: 1.9.20

Android Gradle Plugin: 8.1.4

UI framework: Jetpack Compose / Material 3

Audio output: Android "AudioTrack"

Audio input: Android "AudioRecord"

Primary sample-rate policy: Uses the device's native output sample rate when available, otherwise 44.1 kHz.

Maximum simultaneous synthesizer voices: 8

PCM looper tracks: 20

Drum tracks: 8

Drum patterns: 8 per kit

Drum kits/styles: 8

Microphone tracks: 6

---

2. System Overview

At a high level, the application is structured as follows:

                         ┌─────────────────────┐
                         │    Jetpack Compose  │
                         │       UI Layer      │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │    SynthEngine      │
                         │ Application / Audio │
                         │     Coordinator     │
                         └──────────┬──────────┘
                                    │
             ┌──────────────────────┼──────────────────────┐
             │                      │                      │
             ▼                      ▼                      ▼
      ┌─────────────┐       ┌─────────────┐       ┌─────────────┐
      │ DspEngine   │       │ DrumEngine  │       │ MicCapture  │
      │ Synth + FX  │       │ Sequencer   │       │ Audio Input │
      └──────┬──────┘       └──────┬──────┘       └──────┬──────┘
             │                     │                     │
             └─────────────────────┼─────────────────────┘
                                   ▼
                         ┌─────────────────────┐
                         │   Audio Mixing /    │
                         │ Master Processing   │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │      AudioTrack     │
                         │  Stereo PCM Output  │
                         └─────────────────────┘

The audio path is intentionally designed around a dedicated high-priority audio thread.

The UI does not directly generate audio samples. Instead, UI state is transferred into the audio engine, where sample generation and mixing occur.

---

3. Main Components

The main implementation is located under:

app/src/main/java/com/microtonal/synth/

Current major components include:

AudioBenchmark.kt
BenchmarkReference.kt
BenchmarkScreen.kt
DrumEngine.kt
DrumTab.kt
DspEngine.kt
LoopTab.kt
MainActivity.kt
MicCaptureEngine.kt
MicTab.kt
MidiExporter.kt
PadTab.kt
PresetManager.kt
SessionSupport.kt
SplashActivity.kt
SynthAppUI.kt
SynthEngine.kt
SynthKnob.kt
SynthUiShared.kt

The most important architectural components are:

Component| Responsibility
"MainActivity"| Application lifecycle and engine startup/shutdown
"SplashActivity"| Initial splash screen
"SynthAppUI"| Main Compose UI
"SynthEngine"| Central audio engine and real-time coordinator
"DspEngine"| Synth voice generation, filters, modulation and master/live DSP
"DrumEngine"| Drum sequencing, kits, patterns and samples
"MicCaptureEngine"| Microphone capture, monitoring and microphone tracks
"LooperPcmTrack"| PCM recording/playback implementation
"AudioBenchmark"| Real-time performance benchmark
"BenchmarkReference"| Deterministic reference/session workload support
"PresetManager"| Presets, drum kits and session persistence
"SessionSupport"| Complete session serialization/restoration
"MidiExporter"| MIDI file generation
"SynthAppUI"| User-facing control surface
"BenchmarkScreen"| Benchmark UI and results

---

4. Application Lifecycle

"MainActivity" owns the main "SynthEngine".

Startup sequence:

Android
  │
  ▼
SplashActivity
  │
  │ ~2 seconds
  ▼
MainActivity
  │
  ▼
SynthEngine(context)
  │
  ├── DspEngine
  ├── DrumEngine
  ├── MicCaptureEngine
  ├── Looper tracks
  └── AudioTrack
  │
  ▼
SynthEngine.start()
  │
  ▼
Real-time audio thread

"MainActivity" also keeps the screen awake while the synthesizer is active.

On destruction:

MainActivity.onDestroy()
        │
        ▼
SynthEngine.stop()

The application manifest exposes both the splash activity and the main activity.

Microphone permission is declared through:

<uses-permission android:name="android.permission.RECORD_AUDIO" />

---

5. Audio Engine

5.1 "SynthEngine"

"SynthEngine" is the central runtime coordinator.

It owns:

- "DspEngine"
- "DrumEngine"
- "MicCaptureEngine"
- 20 PCM looper tracks
- audio output
- synth voice slots
- recording state
- MIDI event recording
- visualizer buffers
- benchmark infrastructure
- external audio playback
- performance-pad routing
- master mixing

The synthesizer currently uses:

maxVoices = 8

Each voice is represented by a "NoteSlot".

---

6. Real-Time Audio Thread

The primary audio processing loop is created by "SynthEngine.start()".

The thread is explicitly promoted to Android's urgent-audio priority:

android.os.Process.setThreadPriority(
    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
)

The engine processes audio in blocks of:

512 frames

The output is stereo 16-bit PCM.

The output object is an Android "AudioTrack" configured for:

CHANNEL_OUT_STEREO
ENCODING_PCM_16BIT
MODE_STREAM
PERFORMANCE_MODE_LOW_LATENCY

The actual device sample rate and native buffer size are queried from "AudioManager".

---

7. DSP Engine

"DspEngine.kt" contains the main real-time synthesis and effects implementation.

The DSP engine operates sample-by-sample inside the audio callback.

Its responsibilities include:

- oscillator generation
- frequency/glide handling
- ADSR envelope processing
- filter processing
- resonance
- detuned/unison oscillator
- sub oscillator
- piano-like harmonic layer
- divider oscillators
- modulation
- delay/echo
- reverb
- saturation/drive
- warm effect
- vibe effect
- rip effect
- fuzz
- phaser
- wah
- octave
- chorus
- external audio playback
- master processing
- live/looper routing
- DSP profiling

---

8. Synth Voice Architecture

Each "NoteSlot" stores both synthesis state and filter/envelope state.

A voice contains, among other fields:

baseFreq
targetFreq
currentFreq

phase
phase2
phaseSub

phaseP2
phaseP3
phaseP4

phase2P2
phase2P3
phase2P4

hammerEnv
envelopeVolume
envState

zdfState1
zdfState2

zdfCachedCutoff
zdfCachedRes
zdfG
zdfK
zdfH

This means the engine preserves per-voice state rather than recomputing a voice from scratch every sample.

---

9. Envelope

The synth supports:

- Attack
- Decay
- Sustain
- Release

The coefficients are calculated when a voice is activated rather than repeatedly calculating exponential coefficients inside the sample loop.

The relevant state is stored directly on "NoteSlot":

attackCoeff
decayCoeff
releaseCoeff

This is an important part of the real-time design because expensive coefficient calculation is kept outside the hottest sample-processing path.

---

10. Oscillator Architecture

The synth uses multiple oscillator paths.

The implementation includes:

Main oscillator

The primary voice oscillator.

Detune / Unison

An additional oscillator phase is maintained:

phase2

This allows a second lightly-detuned oscillator to be layered onto the main voice.

Sub oscillator

A separate phase is maintained for the sub oscillator:

phaseSub

Piano layer

Additional phase accumulators:

phaseP2
phaseP3
phaseP4
phase2P2
phase2P3
phase2P4

are used for the piano-style harmonic component.

Divider oscillators

The system supports:

÷2
÷3
÷4

with corresponding per-voice state.

---

11. Sine Lookup Table

The DSP engine precomputes a sine lookup table:

4096 samples

instead of repeatedly calling "sin()" for the main oscillator path.

Conceptually:

phase
  │
  ▼
4096-entry LUT
  │
  ▼
sample

The LUT uses a power-of-two size and mask for efficient index wrapping.

---

12. Fast Math Approximations

The DSP implementation contains optimized approximations for expensive math operations.

For example:

private inline fun fastTan(x: Double): Double {
    val x2 = x * x
    return x * (15.0 - x2) / (15.0 - 6.0 * x2)
}

The purpose is to avoid calling "Math.tan()" repeatedly in the real-time filter path.

The DSP source explicitly documents the intent as maintaining the required frequency accuracy while avoiding expensive trigonometric work inside the sample loop.

Any future modification to this approximation must therefore be treated as an audio-quality-sensitive change.

---

13. ZDF Filter

The synth contains a zero-delay-feedback-style filter implementation.

Each voice maintains:

zdfState1
zdfState2
zdfG
zdfK
zdfH

The implementation also caches filter coefficients.

Current code defines:

private const val ZDF_COEFF_UPDATE_INTERVAL = 8

This means coefficient rebuilding is deliberately decoupled from the per-sample state update.

The intended architecture is:

UI cutoff/resonance
        │
        ▼
smoothed voice parameters
        │
        ▼
cached ZDF coefficients
        │
        ▼
per-sample filter state update

This distinction is important when optimizing the DSP.

Changing the coefficient update rate can alter audible behavior and therefore must not be considered a purely performance-only change.

---

14. Smoothing

A substantial amount of the real-time engine uses smoothing rather than immediately jumping from one control value to another.

Examples include:

volume
echo
reverb
performance pad
looper performance
drive
piano wet amount
divider wet amounts
wah
octave
chorus

The purpose is to prevent discontinuities caused by UI/control changes from becoming audible clicks or modulation artifacts.

---

15. Drive / Saturation

The engine includes a drive/saturation stage.

The public control is:

driveAmount

and the master processing includes nonlinear amplitude shaping.

This stage is particularly important for audio-quality preservation because changing its transfer curve changes harmonic content, perceived loudness and clipping behavior.

---

16. Reverb

The reverb is implemented directly in "DspEngine".

The current structure uses:

4 comb delay lines
+
2 all-pass stages

with damping and feedback.

The buffers are scaled according to the actual sample rate.

This is a self-contained algorithmic reverb rather than an Android platform effect.

---

17. Delay / Echo

The engine maintains a dedicated delay buffer.

The current fixed delay is approximately:

280 ms

scaled to the active sample rate.

There are separate smoothed echo controls for:

live
looper

This allows live synthesis and recorded PCM loops to have independent echo levels.

---

18. Live Effects

The current live sound engine exposes multiple color/effect stages:

SUB
WARM
VIBE
RIP
FUZZ
PHAZ
PIANO
÷2
÷3
÷4

Additional performance-pad effects include:

WAH
OCT
CHO

The exact ordering of these stages is part of the current sound design and should be considered part of the audio contract.

---

19. Performance Pad

The application contains a two-dimensional performance pad.

The pad is not treated simply as an XY controller.

The engine maintains:

busPadX
busPadY

padOriginX
padOriginY

padModX
padModY

padWah
padOct
padCho

The system also distinguishes between different pad targets:

padTargetKey
padTargetMic
padTargetLoop
padTargetDrum

This allows the same performance surface to control different audio stems.

The pad can also drive a low-frequency modulation signal.

---

20. Stutter / Roll Processing

The system contains "SliceRoll", a short audio-buffer-based rhythmic effect.

It can operate on:

4
8
16
32

note divisions.

The effect records a short grain into an internal buffer and then repeatedly plays the captured segment.

Separate handling exists for the main bus and drum bus.

---

21. PCM Looper

The looper implementation is based on "LooperPcmTrack".

There are:

20 total looper tracks
4 pages
5 tracks per page

Each track supports:

- PCM recording
- PCM playback
- volume
- pan
- visualization
- pad automation
- start/stop
- clear
- snapshot
- restore
- persistence
- playback crossfade

Each track has a default maximum recording duration of:

30 seconds

---

22. Looper Buffer Ownership

The PCM looper deliberately separates:

recordingBuffer
playbackBuffer

A completed take is published as the playback buffer rather than being reused as the writable recording buffer.

This is important because benchmark/reference playback can retain a previously recorded buffer while another take is being created.

The implementation uses atomic state and a control lock around buffer ownership transitions.

The intended state machine is:

IDLE
 │
 ▼
RECORDING
 │
 ▼
STOPPING
 │
 ▼
IDLE

There is also an automatic completion state when the hard maximum recording length is reached.

---

23. Looper Recording Signal

The PCM looper does not simply record the raw oscillator.

The architecture intentionally captures the live wet bus through:

liveRecordTap

This means a recorded take can preserve the sound as it existed at recording time.

The source code explicitly describes this as freezing elements such as:

waveform
pad
LFO
drive
detune

into the recorded PCM.

This is a key design property of the looper.

Changing live controls after recording should therefore not retroactively change the audio content already captured into a PCM track.

---

24. Looper Playback

Each looper track has:

volume
pan
play position
playback gain
fade state

Playback includes a short crossfade around the loop boundary to reduce discontinuities.

The pan uses equal-power-style coefficients.

---

25. Looper Pad Automation

A looper track can store pad movements while recording.

Pad events are stored using:

timestamp
x
y

The implementation limits the event rate to avoid unnecessarily dense automation.

During playback, the recorded pad state can be reconstructed from the timestamps.

Therefore a recorded loop can reproduce both:

audio
+
performance-pad movement

---

26. External Audio

The system supports loading external PCM audio into the DSP engine.

"DspEngine" maintains:

externalAudioBuffer
externalAudioPos
isExternalAudioPlaying
isExternalAudioLooping

External audio is therefore treated as another audio source inside the real-time pipeline.

---

27. Drum Machine

"DrumEngine" implements an integrated step sequencer.

The current system has:

8 drum tracks
16 steps per pattern
8 patterns
8 kits/styles

Default track names are:

Kick
Snare
Hi-Hat
Perc
Extra 1
Extra 2
Extra 3
Extra 4

Each pattern contains:

16-step Boolean grid
BPM
master volume
track volumes
track pans
repeat count

---

28. Drum Timing

Drum timing is calculated from:

BPM
+
swing
+
sample rate

Step increments are cached rather than recalculated for every sample.

Swing modifies the timing of alternating steps.

---

29. Drum Kits

There are 8 drum kits/styles.

Each kit contains 8 patterns.

Each kit can also persist its own sample set.

Samples are stored in the application's internal storage under:

drum_kits/

The current implementation stores PCM sample arrays directly.

---

30. Drum Pattern Generation

The drum engine includes a constrained random pattern generator.

The generator does not simply randomize every step.

It applies musical constraints such as:

- kick downbeats
- snare backbeats
- controlled hi-hat density
- sparse percussion
- guaranteed non-empty kick/snare/hat foundations

This creates a usable groove rather than purely random noise.

---

31. Microphone Engine

"MicCaptureEngine" provides microphone input and monitoring.

It uses Android:

AudioRecord

and attempts several audio input sources:

MIC
UNPROCESSED
CAMCORDER

The engine also attempts to disable Android Automatic Gain Control when available.

---

32. Microphone Tracks

The microphone subsystem contains:

2 pages
3 tracks per page
6 tracks total

The first page is intended for longer vocal takes.

The second page is intended for guitar/bass and similar recordings.

Maximum track lengths are bounded.

---

33. Microphone FX

Each microphone track has a "MicChannelFx" instance.

Available processing includes:

High-pass filter
Gate
Presence shaping
Compression-like level reduction
Volume
Pan

The monitor path also has dedicated lightweight processing.

---

34. Microphone Monitoring

The monitor path uses a ring buffer between the capture thread and audio output.

The design maintains an explicit monitor latency target:

512 samples

The monitor can therefore operate independently from recording state.

---

35. Microphone Monitor Presets

There are 3 monitor presets.

Each preset can store:

monitor volume
input gain
high-pass frequency
gate threshold
low gain
presence gain
compression amount

---

36. Session System

The project has a complete session persistence layer.

"SessionSupport.kt" defines the serialized application state.

A session can contain:

selected tab
frequencies
scale
waveform
volume
ADSR
drive
reverb
detune
sub
warm
vibe
rip
fuzz
phaz
piano
divider effects
cutoff
resonance
echo
glide
octave
looper project
looper page
looper channel names
drum selection
drum repeats
microphone project
microphone page
microphone names
microphone volumes
microphone pans
microphone monitor state
microphone monitor volume
microphone gain
pad effects

---

37. Session Storage

Sessions are stored internally under:

console_sessions/

A session may contain:

console.json
looper/
drums/
mic/
presets.json

The application also supports a legacy JSON representation.

---

38. Session Import / Export

The session system supports ZIP-based export/import.

The export process recursively packages the session directory.

The import process validates the target path against the session root before writing files.

The implementation explicitly rejects path traversal entries containing:

..

and verifies that the resulting canonical path remains under the session root.

---

39. Preset Management

"PresetManager" handles persistent drum kit and session state.

Drum patterns are serialized into "SharedPreferences".

Each pattern stores:

64 grid cells
BPM
master volume
8 track volumes
8 track pans
repeat count

The current kit and pattern selections are also persisted.

---

40. MIDI Recording

The synth records MIDI-like note events during performance.

Events contain:

timestamp
note-on / note-off
frequency
waveform
octave

These events are stored separately from the generated audio.

---

41. MIDI Export

"MidiExporter" generates a standard MIDI file.

The current implementation writes:

MThd
MTrk

with:

480 ticks per quarter note

The exported note number is derived from the recorded frequency.

This allows the microtonal performance representation to be converted into conventional MIDI note numbers for external applications.

---

42. Benchmark System

The project contains a substantial integrated benchmark subsystem.

Relevant files:

AudioBenchmark.kt
BenchmarkReference.kt
BenchmarkScreen.kt

The benchmark is designed to measure the actual audio workload rather than only synthetic CPU microbenchmarks.

The current benchmark defines:

WORKLOAD_ID = real-session-4-loopers-v1
STRESS_WORKLOAD_ID = stress-max-6-loopers-v2

The standard measurement structure is:

3 seconds warmup
+
30 seconds measurement

---

43. Benchmark Philosophy

The benchmark measures real-time audio performance using the actual audio engine.

The report tracks:

average buffer time
p50
p95
p99
maximum
buffer misses
miss rate
CPU average
CPU peak
underrun information

This is more useful for the application than a generic CPU benchmark because the primary constraint is real-time audio deadline compliance.

---

44. Benchmark Score

The benchmark generates a score from:

real-time delivery
deadline misses
p95 latency
p99 latency

The scoring model is explicitly separated into:

actual audio delivery failures
+
processing pressure
+
tail latency

This is important because a workload can consume substantial CPU without necessarily causing audible audio failure.

Conversely, a small number of missed deadlines can be much more important than a modest increase in average CPU cost.

---

45. Deep DSP Profiling

"DspEngine" contains sampled deep profiling.

Profiling is intentionally sampled rather than performed on every sample.

The profiler measures individual DSP regions including:

voice
ZDF
external audio
live FX
delay
reverb
master
voice frequency
envelope
oscillator
modulation
voice mix
main oscillator
piano
sub
detune
dividers
÷2
÷3
÷4
vibe
warm
rip
fuzz
phaser
wah
octave
chorus
drive

The purpose is to identify where real-time CPU time is actually being spent.

---

46. Profiling Safety

The profiling implementation attempts to minimize its own impact on the real-time audio thread.

Deep profiling is only activated under benchmark conditions and is sampled.

This distinction is important:

Normal production audio path
        ≠
Deep profiling path

Performance conclusions should therefore always specify whether profiling was enabled.

---

47. Reference Workload

The benchmark contains a deterministic reference workload.

It defines:

- fixed frequencies
- fixed voice event timing
- fixed loop recording windows
- fixed master recording windows
- reference fixture frequencies
- reference pan positions

The intent is to make before/after performance measurements reproducible.

This is particularly useful when modifying the DSP engine.

---

48. Audio Quality Contract

The DSP system should be treated as an audio-quality-sensitive system.

The following are behavioral contracts, not merely implementation details:

Must remain stable unless intentionally changed

- oscillator pitch
- oscillator waveform character
- envelope timing
- filter cutoff behavior
- filter resonance
- ZDF stability
- detune amount
- sub oscillator relationship
- effect ordering
- effect wet/dry behavior
- delay time
- reverb character
- saturation curve
- looper recorded sound
- looper playback timing
- loop boundary behavior
- drum timing
- swing behavior
- microphone recording signal
- monitoring behavior

A CPU optimization is not considered successful if it changes the audible result beyond the project's accepted tolerance.

---

49. Performance-Sensitive Design Rules

The audio thread is the most sensitive execution context in the project.

Changes inside the audio path should avoid:

heap allocation
unnecessary object creation
blocking locks
disk I/O
network I/O
UI calls
unbounded loops
unexpected garbage collection
expensive logging
uncontrolled exceptions

Any change to a hot DSP loop should be evaluated against both:

CPU cost
+
audio output equivalence

---

50. Threading Model

The project currently has several distinct execution contexts.

Conceptually:

Main/UI thread
    │
    ├── Compose
    ├── user interaction
    └── state management

Audio thread
    │
    ├── SynthEngine
    ├── DspEngine
    ├── DrumEngine
    ├── looper playback
    └── final AudioTrack output

Microphone capture thread
    │
    └── AudioRecord

Background/coroutine work
    │
    ├── file operations
    ├── import/export
    └── longer-running session operations

The audio thread must remain isolated from slow operations.

---

51. Visualizers

The application exposes multiple real-time visualizer buffers.

Examples include:

liveVisualizerBuffer
looperVisualizerBuffer
drumVisualizerBuffer
externalVisualizerBuffer
monitorVisualizer

The UI periodically reads these buffers to render visual feedback.

The visualizer data is deliberately decimated rather than updating the UI on every audio sample.

---

52. UI Architecture

The UI is implemented with Jetpack Compose.

The primary UI file is:

SynthAppUI.kt

Additional UI components are separated into:

SynthKnob.kt
SynthUiShared.kt
BenchmarkScreen.kt
DrumTab.kt
LoopTab.kt
MicTab.kt
PadTab.kt

The application behaves as a music console rather than a traditional settings-based Android application.

---

53. Build Configuration

Root build configuration:

com.android.application 8.1.4
org.jetbrains.kotlin.android 1.9.20

The Android application uses:

compileSdk 34
minSdk 24
targetSdk 34
Java 17
Kotlin JVM target 17

Jetpack Compose is enabled.

---

54. Dependencies

The application currently uses the following main Android dependencies:

androidx.core:core-ktx
androidx.lifecycle:lifecycle-runtime-ktx
androidx.activity:activity-compose
androidx.compose.ui
androidx.compose.ui-graphics
androidx.compose.ui-tooling-preview
androidx.compose.material3

Compose uses a BOM-based dependency configuration.

There is no large third-party audio/DSP framework in the current architecture.

The core audio engine is custom Kotlin/Android code.

---

55. Continuous Integration

GitHub Actions contains an Android CI workflow.

The workflow runs on:

ubuntu-latest

It installs:

JDK 17 / Temurin
Gradle 8.4

and executes:

gradle :app:assembleDebug

The resulting APK is uploaded as:

L-Studio

The current CI workflow is primarily a build verification workflow.

It does not currently represent a comprehensive automated DSP/audio-quality test suite.

---

56. Debug vs Release

The application currently has:

release {
    minifyEnabled false
}

The CI workflow builds the Debug variant.

The output APK is renamed:

L-Studio.apk

There is currently no documented production signing configuration in the repository.

---

57. Current Repository Structure

.
├── .github/
│   └── workflows/
│       └── android.yml
│
├── app/
│   ├── build.gradle
│   │
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           │
│           ├── java/
│           │   └── com/
│           │       └── microtonal/
│           │           └── synth/
│           │               ├── AudioBenchmark.kt
│           │               ├── BenchmarkReference.kt
│           │               ├── BenchmarkScreen.kt
│           │               ├── DrumEngine.kt
│           │               ├── DrumTab.kt
│           │               ├── DspEngine.kt
│           │               ├── LoopTab.kt
│           │               ├── MainActivity.kt
│           │               ├── MicCaptureEngine.kt
│           │               ├── MicTab.kt
│           │               ├── MidiExporter.kt
│           │               ├── PadTab.kt
│           │               ├── PresetManager.kt
│           │               ├── SessionSupport.kt
│           │               ├── SplashActivity.kt
│           │               ├── SynthAppUI.kt
│           │               ├── SynthEngine.kt
│           │               ├── SynthKnob.kt
│           │               └── SynthUiShared.kt
│           │
│           └── res/
│               ├── drawable/
│               └── raw/
│
├── build.gradle
├── gradle.properties
└── settings.gradle

---

58. Important Data Ownership

The current architecture has several distinct forms of state.

Live synth state

Owned primarily by:

SynthEngine
DspEngine
NoteSlot

Looper audio state

Owned by:

LooperPcmTrack

Drum state

Owned by:

DrumEngine

Microphone state

Owned by:

MicCaptureEngine
MicChannelFx
LooperPcmTrack

UI/session state

Owned by:

SynthAppUI
SessionSupport
PresetManager

Benchmark state

Owned by:

AudioBenchmark
BenchmarkReference

---

59. Important Real-Time Ownership Principle

A particularly important property of the current implementation is the separation between:

control-plane operations

and:

audio-plane operations

For example, PCM buffer replacement is protected by a control-side lock, while the audio callback uses atomic state and avoids taking the same blocking control lock.

This pattern should be preserved when modifying the looper.

---

60. Audio Quality Verification

Any DSP optimization should be evaluated using at least three layers.

Layer 1 — Functional correctness

Verify:

app builds
engine starts
audio output works
notes play
loops record/play
drums run
microphone works
sessions save/load
MIDI export works

Layer 2 — Real-time performance

Compare:

average buffer time
p95
p99
maximum
miss rate
underruns
CPU average
CPU peak

Layer 3 — Audio equivalence

Compare the actual audio generated before and after the change.

The performance benchmark alone is not sufficient to establish audio equivalence.

---

61. Recommended DSP Regression Method

For every DSP optimization:

Baseline
   │
   ├── benchmark
   ├── render reference audio
   └── capture metrics
          │
          ▼
     Apply ONE change
          │
          ▼
     Build and run
          │
          ├── benchmark
          ├── render same reference
          └── compare output
                  │
                  ▼
             Accept / Reject

Only one material DSP change should be evaluated at a time when determining causality.

---

62. Recommended Audio Comparison Metrics

For a DSP change, useful measurements include:

RMS difference
Peak difference
Maximum absolute sample difference
Normalized error
Signal-to-noise ratio
Spectral difference
FFT magnitude difference
THD changes where applicable
DC offset
frequency/pitch deviation
envelope timing deviation

For nonlinear effects such as:

drive
fuzz
phaser
wah
reverb

waveform-level comparison alone may be insufficient.

The comparison should also examine spectral and perceptual characteristics.

---

63. Performance Optimization Boundaries

The following areas are generally lower-risk optimization targets:

reducing redundant control reads
caching immutable constants
moving coefficient calculations outside loops
avoiding repeated allocation
using existing lookup tables
reducing unnecessary profiling overhead
avoiding redundant state traversal
improving non-audio-thread persistence

The following areas are high-risk:

oscillator mathematics
filter mathematics
envelope equations
effect transfer functions
effect ordering
oversampling/interpolation
sample-rate conversion
delay interpolation
reverb feedback coefficients
drive curve
voice summing
headroom calculation

High-risk areas require audio regression evidence.

---

64. Known Architectural Characteristics

The current project is intentionally compact and application-centric.

Several major subsystems are implemented directly in Kotlin files rather than separated into a large number of independent modules.

This makes the current project relatively straightforward to navigate, but it also means that "SynthEngine.kt", "DspEngine.kt", and "SynthAppUI.kt" contain substantial amounts of functionality.

Those files should therefore be treated as high-impact files when making changes.

---

65. Known Build/Engineering Limitations

The current repository provides build CI, but the CI workflow currently performs:

Debug APK compilation

rather than full automated audio regression.

There is no evidence in the current repository of a complete automated test suite covering:

sample-accurate DSP regression
golden audio files
FFT regression
automated latency validation on physical devices
automated underrun testing

Consequently, audio correctness currently depends heavily on the benchmark/reference infrastructure and manual/engineering validation.

---

66. Development Guidelines

Before modifying the audio engine:

1. Establish a benchmark baseline.
2. Establish an audio/reference baseline.
3. Change one subsystem at a time.
4. Avoid mixing UI refactors with DSP changes.
5. Avoid changing multiple DSP algorithms in one optimization.
6. Measure on the same device where possible.
7. Compare both average and tail latency.
8. Check for underruns.
9. Compare output audio.
10. Keep the change reversible.

---

67. DSP Change Acceptance Criteria

A DSP optimization should only be accepted when all applicable conditions are satisfied:

BUILD
PASS

FUNCTIONAL BEHAVIOR
PASS

REAL-TIME DEADLINE
PASS

UNDERRUN REGRESSION
NONE

AUDIO OUTPUT
WITHIN DEFINED TOLERANCE

PITCH / TIMING
UNCHANGED

EFFECT BEHAVIOR
UNCHANGED

MEMORY / THREAD SAFETY
PASS

A lower CPU number by itself is not sufficient.

---

68. What This Application Is

L Studio is effectively a compact real-time digital music workstation for Android combining:

Microtonal synthesizer
+
Custom DSP
+
Performance pad
+
PCM looper
+
Drum machine
+
Microphone recorder
+
Monitor mixer
+
Session system
+
MIDI export
+
Audio benchmark

The central engineering challenge is therefore not simply generating audio.

The system must simultaneously preserve:

real-time deadlines
+
audio quality
+
musical timing
+
state consistency
+
recording integrity
+
interactive responsiveness

---

69. Primary Engineering Principle

The most important principle for future development is:

«Performance optimization must never be considered successful until both real-time performance and audio behavior have been verified.»

The audio engine is a real-time system.

A mathematically smaller or faster implementation is not automatically equivalent to the existing implementation.

Every optimization that touches the DSP path should therefore be treated as a potentially audible change until proven otherwise.

---

70. Quick Start

Requirements

Recommended development environment:

Android Studio
JDK 17
Android SDK 34
Gradle 8.4

Build

From the repository root:

gradle :app:assembleDebug

The APK is generated under:

app/build/outputs/apk/debug/

The configured output name is:

L-Studio.apk

Install

adb install -r app/build/outputs/apk/debug/L-Studio.apk

The application requires microphone permission for microphone recording and monitoring.

---

71. Repository Reference

The canonical source repository is:

https://github.com/dudichatam-max/psychic-winner

The repository's current main branch contains the Android application described in this document.

---

72. Final Architecture Summary

                         L STUDIO
                            │
             ┌──────────────┴──────────────┐
             │                             │
             ▼                             ▼
        Compose UI                    MainActivity
             │                             │
             └──────────────┬──────────────┘
                            ▼
                       SynthEngine
                            │
       ┌────────────────────┼─────────────────────┐
       │                    │                     │
       ▼                    ▼                     ▼
   DspEngine           DrumEngine          MicCaptureEngine
       │                    │                     │
       │                    │                     │
       ▼                    ▼                     ▼
  8 Synth Voices      8 Tracks / 16 Steps     6 Mic Tracks
       │
       ├── Oscillators
       ├── ADSR
       ├── ZDF Filter
       ├── Detune
       ├── Sub
       ├── Piano
       ├── Dividers
       ├── Delay
       ├── Reverb
       ├── Drive
       ├── Warm
       ├── Vibe
       ├── Rip
       ├── Fuzz
       ├── Phaser
       ├── Wah
       ├── Octave
       └── Chorus
                            │
                            ▼
                     PCM Looper System
                            │
                      20 Tracks / 30s
                            │
                            ▼
                     Master Audio Path
                            │
                            ▼
                       AudioTrack
                            │
                            ▼
                    Android Audio Output

The project also contains a parallel engineering/verification layer:

AudioBenchmark
BenchmarkReference
BenchmarkScreen
        │
        ├── workload definition
        ├── warmup
        ├── measurement
        ├── deadline analysis
        ├── underrun tracking
        ├── CPU analysis
        └── sampled DSP profiling

Together, these components form the current L Studio architecture.
