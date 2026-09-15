# חבילת E5 Reference — באגים 1–5 + שאלות מחקר

## חשוב לפני השימוש
1. בנה/התקן APK מ־ענף `E5-Reference-JSON` (ב־main אין IMPORT עדיין).
2. במסך Benchmark: `START | STOP | IMPORT | EXPORT`.
3. `durationMs` בקובץ = מטא־דאטה בלבד — **STOP ידני**.
4. **PASS/FAIL של ה־Reference ≠ "הבאג קיים/לא"**.  
   ה־assertions בודקות רק שב־E5 נאספה ראיה תקינה (counts/overflows).  
   האבחון האקוסטי נעשה על קובץ ה־EXPORT (diagnostics).

## Paths מאושרים בלבד
`e5.sessionActive`, `e5.controlRecordCount`, `e5.pendingWindowCount`,
`e5.audioObservationCount`, `e5.absoluteRenderFrame`,
`e5.controlOverflowCount`, `e5.windowOverflowCount`,
`e5.audioObservationOverflowCount`, `e5.hasExportableData`

## סדר מומלץ במכשיר (APK אחד)
1. IMPORT `bug03-polyphony-clean-baseline.json` → START → 1..8 קולות → STOP → EXPORT
2. IMPORT `bug03-wave-square-stress.json` → … → EXPORT
3. IMPORT `bug04-detune-drive100.json` → … → EXPORT
4. IMPORT `bug05-detune-drive-sub-warm.json` → … → EXPORT
5. IMPORT `bug01-cutoff-sine-triangle.json` → … → EXPORT
6. IMPORT `bug01b-cutoff-saw-square-pops.json` → … → EXPORT
7. IMPORT `rq1-voice-ceiling-evidence.json` / `rq2-cutoff-move-evidence.json` לפי צורך
8. `bug02-…` — persistence של div2/3/4 **לא נמדד ב־E5**; הקובץ רק אוסף evidence סביב נגינה עם dividers

## קבצים
- `bug01-cutoff-sine-triangle.json` — BUG-01 Cutoff realtime — Sine/Triangle
- `bug01b-cutoff-saw-square-pops.json` — BUG-01b Cutoff pops — Saw/Square while moving
- `bug02-dividers-preset-scope-note.json` — BUG-02 Dividers 2/3/4 not saved in presets
- `bug03-polyphony-clean-baseline.json` — BUG-03 Polyphony ceiling — no Drive/Detune/FX
- `bug03-wave-sine-stress.json` — BUG-03 Wave Sine — high polyphony evidence
- `bug03-wave-square-stress.json` — BUG-03 Wave Square — distortion ~7 voices
- `bug04-detune-drive100.json` — BUG-04 Detune ON + Drive 100%
- `bug04-square-detune-drive.json` — BUG-04 Square focus — Detune+Drive100
- `bug05-detune-drive-sub-warm.json` — BUG-05 Detune+Drive100+Sub+Warm
- `bug05-square-full-stack.json` — BUG-05 Square full stack — earliest ceiling
- `rq1-voice-ceiling-evidence.json` — RQ1 Why clicks near max voices?
- `rq2-cutoff-move-evidence.json` — RQ2 Why cutoff move causes distortion?
