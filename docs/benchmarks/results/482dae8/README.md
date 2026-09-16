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
| **B** · DSP | OFF | OFF | recorded B (fixed) | **done** FAIL score ~20.7 · avg 10.753 · underruns ~86 | [`2026-09-16_ref-B_dsp-profile.json`](2026-09-16_ref-B_dsp-profile.json) |
| **C** · DSP | OFF (UI may show ON) | OFF | recorded C (fixed) | **done** FAIL score ~12.7 · avg 11.407 · underruns ~588 | [`2026-09-16_ref-C_dsp-profile.json`](2026-09-16_ref-C_dsp-profile.json) |
| **Extreme** · limited | intended ON | intended ON | stress generated benchmark | **done** FAIL score 0 · avg 22.528 · underruns ~1530 | [`2026-09-16_extreme_stress.json`](2026-09-16_extreme_stress.json) |

## How to add a session

1. Run 3× on device (same scenario).
2. Use **Copy** after each run (or once per run) and paste the three reports.
3. Files land under this folder; update `docs/benchmarks/INDEX.md`.

Older tips (`982bee7`, `e40627e`, `d99c18d`) stay as history under `docs/benchmarks/results/`.

## ABC vs Extreme — Looper / Drums

בסשני **A / B / C** כפתורי Looper ו־Drums **מקושרים רק לבדיקת Extreme**. גם אם טקסט Copy מציג `Looper replay: ON` / `Drums replay: ON`, **הם לא פעלו בפועל** בסשן ABC. אין לפרש את התווית כעומס לופר/תופים. רק בסשן **Extreme** הלופר והתופים פעילים באמת (רפרנס extreme קבוע במערכת).

## סיכום

[ABC + Extreme summary](../../comparisons/482dae8_ABC-Extreme_summary.md)
