L Studio

Real-Time Android Microtonal Synthesizer, Looper, Drum Machine, Microphone Recorder and Performance Console

L Studio is a real-time Android music application built around a custom Kotlin audio engine and DSP pipeline.

It combines a microtonal synthesizer, real-time effects, PCM looping, drum sequencing, microphone recording/monitoring, performance-pad control, session/preset management, MIDI export and an integrated real-time audio benchmark.

The project is intentionally built around a custom audio thread rather than a high-level audio framework.

---

Project Status

- Repository: "dudichatam-max/psychic-winner"
- Application: "L Studio"
- Application ID: "com.microtonal.synth"
- Root project: "MicroScaleSynth"
- Main branch: "main"
- Compile / Target SDK: 34
- Minimum SDK: 24
- Java: 17
- Kotlin: 1.9.20
- Android Gradle Plugin: 8.1.4
- UI: Jetpack Compose / Material 3
- Audio output: "AudioTrack"
- Audio input: "AudioRecord"
- Maximum synth voices: 8
- Looper tracks: 20
- Drum tracks: 8
- Microphone tracks: 6

The current "main" branch uses the original integrated UI architecture.

UI Split Status

A UI-splitting architecture was tested and benchmarked, but it was removed from the current implementation.

During real-time playback testing, the split UI introduced audible instability and distortion under sustained/long-running sound. The original "SynthAppUI" architecture was therefore restored and the split-specific UI files were removed.

The current priority is audio correctness and real-time stability, not further UI decomposition.

Any future UI architectural change must demonstrate that it does not alter or degrade real-time audio behavior.

---

Architecture

The application is centered around a single runtime audio coordinator:

                    Jetpack Compose UI
                           │
                           ▼
                    ┌──────────────┐
                    │ SynthAppUI   │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │ SynthEngine  │
                    │ Audio Runtime│
                    └──────┬───────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
    ┌──────────┐     ┌──────────┐    ┌──────────────┐
    │ DspEngine│     │DrumEngine│    │MicCapture    │
    │ Synth/FX │     │ Sequencer│    │Engine        │
    └────┬─────┘     └────┬─────┘    └──────┬───────┘
         │                │                 │
         └────────────────┼─────────────────┘
                          ▼
                  Master Mixing / DSP
                          │
                          ▼
                     AudioTrack

The UI controls the application state, but sample generation and audio mixing are performed by the real-time audio engine.

---

Main Components

Source code is located under:

app/src/main/java/com/microtonal/synth/

Key components:

Component| Responsibility
"MainActivity"| Application lifecycle and engine startup/shutdown
"SplashActivity"| Initial application screen
"SynthAppUI"| Main integrated Compose UI
"SynthEngine"| Central real-time audio coordinator
"DspEngine"| Synthesis, filters, modulation and effects
"DrumEngine"| Drum sequencing, kits and patterns
"MicCaptureEngine"| Microphone capture, monitoring and microphone tracks
"AudioBenchmark"| Real-time audio performance measurement
"BenchmarkReference"| Controlled/reference benchmark workload
"BenchmarkScreen"| Benchmark UI and results
"PresetManager"| Presets and persistent configuration
"SessionSupport"| Session serialization/restoration
"MidiExporter"| MIDI export

---

Real-Time Audio Engine

"SynthEngine" owns the main audio runtime and coordinates:

- Synth voices
- DSP processing
- Drum playback
- Microphone input
- PCM looper tracks
- External audio
- Master mixing
- MIDI recording
- Visualizer data
- Performance-pad routing
- Benchmark instrumentation

The audio thread runs with Android's urgent-audio priority:

Process.setThreadPriority(
    Process.THREAD_PRIORITY_URGENT_AUDIO
)

The engine processes audio in blocks and writes stereo PCM data to an Android "AudioTrack" configured for low-latency streaming.

The primary control target for the real-time path is:

48,000 Hz
512 frames
10.667 ms callback deadline

Actual device configuration may vary according to the device's native audio configuration.

---

DSP

"DspEngine.kt" contains the main synthesis and effects pipeline.

The DSP includes:

- Microtonal oscillator generation
- Frequency/glide processing
- ADSR envelopes
- Resonant/ZDF filtering
- Detuned/unison synthesis
- Sub oscillator
- Piano/harmonic layer
- Divider oscillators
- Modulation
- Delay/echo
- Algorithmic reverb
- Drive/saturation
- Warm/Vibe/Rip/Fuzz effects
- Phaser
- Wah
- Octave
- Chorus
- External audio processing
- Master processing
- Live/looper routing

The DSP is primarily sample-oriented and therefore extremely sensitive to CPU cost, allocations, synchronization and unnecessary work inside the audio callback.

---

Synth Voice Design

The synthesizer supports up to 8 simultaneous voices.

Each voice maintains its own synthesis state, including oscillator phases, frequency state, envelope state and filter state.

The design uses persistent per-voice state rather than rebuilding a complete voice for every sample.

The engine also uses precomputed/cached values where appropriate, including:

- Envelope coefficients
- Oscillator lookup data
- Filter coefficients
- Smoothed control parameters

A 4096-entry sine lookup table is used to avoid repeatedly evaluating expensive trigonometric functions in the hottest oscillator path.

---

Audio Effects

The DSP pipeline contains multiple effect stages, including:

- Drive / saturation
- Delay / echo
- Reverb
- Warm
- Vibe
- Rip
- Fuzz
- Phaser
- Wah
- Octave
- Chorus
- Divider effects

Effect ordering and parameter behavior are part of the current sound design and should be treated as audio behavior, not merely implementation details.

---

PCM Looper

The application provides 20 PCM looper tracks.

The looper supports:

- Recording
- Playback
- Volume
- Pan
- Visualization
- Start/stop
- Clear
- Snapshot/restore
- Persistence
- Playback crossfade
- Performance-pad automation

A completed recording is separated from the active recording buffer so that playback data remains stable while a new recording can be created.

Recorded audio uses the live recording tap, allowing the captured take to preserve the processed sound present during recording.

---

Drum Machine

"DrumEngine" provides the drum sequencing and playback system.

The application currently supports:

- 8 drum tracks
- Multiple patterns
- Multiple drum kits/styles
- Pattern sequencing
- Real-time playback
- Integration with the master audio path

---

Microphone

"MicCaptureEngine" provides microphone recording and monitoring.

The microphone path is integrated into the same overall real-time audio system and can participate in the application's performance/effects routing.

Microphone access requires:

<uses-permission android:name="android.permission.RECORD_AUDIO" />

---

Performance Pad

The application includes a two-dimensional performance control surface.

The pad can modulate different audio targets, including synth, microphone, looper and drum-related processing.

Pad movement can also be recorded with looper performances and reproduced during playback.

Because pad modulation can affect real-time DSP parameters, changes to its routing or update frequency must be evaluated for both audio quality and CPU impact.

---

Presets and Sessions

The project supports persistent musical state through:

- Presets
- Session save/restore
- Looper state
- Synth parameters
- Drum configuration
- Performance state

"PresetManager" and "SessionSupport" contain the persistence logic.

---

MIDI

"MidiExporter" provides MIDI file generation from recorded musical events.

MIDI export is separate from the real-time PCM audio path.

---

Real-Time Performance Benchmark

The project contains an integrated benchmark specifically for measuring the audio callback.

The benchmark tracks metrics such as:

- Processing time
- P50
- P95
- P99
- Maximum callback time
- Deadline misses
- Underruns
- CPU usage
- Benchmark score

The important realtime constraint is:

512 frames / 48 kHz = 10.667 ms

A callback that approaches or exceeds this deadline can produce audible instability, underruns, clicks or other realtime artifacts.

Benchmark Discipline

DSP changes should be evaluated using the same workload and device configuration whenever possible.

Change one DSP factor at a time and compare:

P50
P95
P99
Max
Deadline miss rate
Underruns
CPU average / peak
Benchmark score

A higher benchmark score by itself is not sufficient. The primary goal is:

«Reliable audio-callback completion with meaningful headroom while preserving the existing sound.»

---

Current Performance Investigation

Recent profiling showed that the realtime problem is primarily inside the DSP/audio path rather than being solved by UI restructuring.

In the controlled benchmark configuration, the audio callback budget is only 10.667 ms, while the measured workload was operating very close to or beyond that limit.

The strongest measured DSP hotspot was the area identified as Div core, which consumed approximately 8.8–9.3 ms in the diagnostic runs. That represents roughly 83–87% of the entire callback budget.

This makes DSP optimization the primary performance target.

Potential optimization areas include:

- Expensive per-sample calculations
- Repeated work between voices
- Division/modulo operations
- Transcendental math
- Redundant state calculations
- Memory access patterns
- Temporary allocations
- Synchronization
- Branch-heavy hot loops
- Work that can be moved outside the sample loop
- Incorrectly scoped diagnostic regions

Optimizations must be measured against the same workload and must preserve audible behavior.

---

Audio Quality Is a Hard Constraint

The real-time engine is a musical instrument, not only a CPU benchmark.

Performance changes must not introduce:

- Clicks
- Pops
- Crackling
- Unexpected distortion
- Timing instability
- Envelope changes
- Pitch changes
- Filter behavior changes
- Effect-level changes
- Looper artifacts
- Recording/playback differences

In particular, changes to the DSP hot path must be treated as audio-quality-sensitive even when they appear mathematically equivalent.

The UI architecture is also considered part of the realtime stability boundary because UI scheduling changes have previously affected observable audio behavior.

---

Build

The project is a standard Android Gradle application.

Main configuration:

Compile SDK: 34
Target SDK: 34
Minimum SDK: 24
Java: 17
Kotlin: 1.9.20
Android Gradle Plugin: 8.1.4
Jetpack Compose
Material 3

Build the debug APK with:

./gradlew assembleDebug

The generated application APK is configured as:

L-Studio.apk

---

Development Guidelines

When modifying the project:

1. Protect the realtime audio path.
2. Avoid allocations and unnecessary synchronization inside the audio callback.
3. Do not change DSP behavior without an audio-quality comparison.
4. Benchmark performance-sensitive changes using a controlled workload.
5. Change one major DSP factor at a time when investigating performance.
6. Treat UI architectural changes as potentially relevant to realtime behavior.
7. Prefer measured evidence over assumptions.
8. Do not optimize based solely on a benchmark score.
9. Preserve the existing sound and interaction model unless a change explicitly targets them.

---

Current Architectural Direction

The current "main" branch intentionally favors a stable integrated UI + dedicated realtime audio engine over further UI decomposition.

The next performance work should focus on reducing the cost of the DSP/audio callback and creating real execution headroom below the callback deadline.

The objective is not simply to make the application benchmark faster.

The objective is to make L Studio reliably perform as a real-time musical instrument.
