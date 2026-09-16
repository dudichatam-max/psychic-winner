# Working baseline — `482dae8` (Copy button tip)

Commit: [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94)  
Change: Benchmark **Copy** button — full on-screen report (incl. DSP diagnostic) to clipboard.

This tip is the **working baseline** for ABC + Extreme sessions. Paste Copy text here instead of screenshots when possible.

## Device / audio (default)

| Field | Value |
|-------|--------|
| Device | 2409BRN2CY |
| Android | 14 |
| App | 1.0 |
| Sample rate | 48000 Hz |
| Buffer | 512 |
| Deadline | 10.667 ms |
| Profile | DSP |
| Warm-up | none |
| n per scenario | **3** |

## Session matrix (planned → fill)

| Scenario | Looper | Drums | Reference | Status | File |
|----------|--------|-------|-----------|--------|------|
| **A** · DSP | OFF | OFF | recorded A (fixed) | **done** WARNING score 67 · avg 10.631 · underruns 0 | [`2026-09-16_ref-A_dsp-profile.json`](2026-09-16_ref-A_dsp-profile.json) |
| **B** · DSP | OFF | OFF | recorded B (fixed) | pending | `YYYY-MM-DD_ref-B_dsp-profile.json` |
| **C** · DSP | OFF | OFF | recorded C (fixed) | pending | `YYYY-MM-DD_ref-C_dsp-profile.json` |
| **Extreme** · DSP | **ON** | **ON** | system fixed **extreme** | pending | `YYYY-MM-DD_extreme_dsp-profile.json` |

## How to add a session

1. Run 3× on device (same scenario).
2. Use **Copy** after each run (or once per run) and paste the three reports.
3. Files land under this folder; update `docs/benchmarks/INDEX.md`.

Older tips (`982bee7`, `e40627e`, `d99c18d`) stay as history under `docs/benchmarks/results/`.
