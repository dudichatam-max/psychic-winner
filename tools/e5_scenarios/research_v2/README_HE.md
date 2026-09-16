# E5 Research Refs v2 — באגים 1–5 + RQ

חבילת **e5-bundle** מדויקת למערכת המעודכנת (הרצה אוטומטית).

## שימוש
1. APK מ־`E5-Reference-JSON`
2. IMPORT את הקובץ `.bundle.json`
3. START — בלי לנגן
4. אחרי סיום אוטומטי → EXPORT לניתוח במחלקת מחקר

## אינדקס גלים
`0=Sine, 1=Square, 2=Triangle, 3=Saw`

## מה מחפשים ב־EXPORT (שורש באג)
| באג | מיקוד בניתוח |
|-----|----------------|
| 01 | השוואת אנרגיה/ספקטרום PCM מול ציר `SETTINGS.cutoff` — low vs high, Sine/Triangle |
| 01b | אי-רציפות ב־PCM סביב timestamps של SETTINGS — Saw/Square |
| 02 | שדות div ב־BEFORE/AFTER של controlRecords (לא בדיקת שמירת preset) |
| 03 | חלון PCM לפי event של קול N — מתי מופיעים artifacts (סף ~7) |
| 04 | אותו ניתוח עם Detune+Drive1.0 — סף מוקדם יותר |
| 05 | +Sub+Warm — סף עוד מוקדם יותר |
| RQ1/RQ2 | חבילות ממוקדות Saw |

## קבצים
- `bug01-cutoff-ramp-sine-low.bundle.json` — BUG-01 Cutoff ramp — sine @ low (110.0Hz) (NOTE_ON×1, SETTINGS×41)
- `bug01-cutoff-ramp-sine-high.bundle.json` — BUG-01 Cutoff ramp — sine @ high (1760.0Hz) (NOTE_ON×1, SETTINGS×41)
- `bug01-cutoff-ramp-triangle-low.bundle.json` — BUG-01 Cutoff ramp — triangle @ low (110.0Hz) (NOTE_ON×1, SETTINGS×41)
- `bug01-cutoff-ramp-triangle-high.bundle.json` — BUG-01 Cutoff ramp — triangle @ high (1760.0Hz) (NOTE_ON×1, SETTINGS×41)
- `bug01b-cutoff-jitter-saw.bundle.json` — BUG-01b Cutoff jitter pops — saw (NOTE_ON×1, SETTINGS×16)
- `bug01b-cutoff-jitter-square.bundle.json` — BUG-01b Cutoff jitter pops — square (NOTE_ON×1, SETTINGS×16)
- `bug02-dividers-on-evidence.bundle.json` — BUG-02 Dividers ON — snapshot evidence (not preset IO) (NOTE_ON×4, SETTINGS×0)
- `bug03-polyphony-sine-1to8.bundle.json` — BUG-03 Clean polyphony — sine 1..8 (NOTE_ON×8, SETTINGS×0)
- `bug03-polyphony-square-1to8.bundle.json` — BUG-03 Clean polyphony — square 1..8 (NOTE_ON×8, SETTINGS×0)
- `bug03-polyphony-triangle-1to8.bundle.json` — BUG-03 Clean polyphony — triangle 1..8 (NOTE_ON×8, SETTINGS×0)
- `bug03-polyphony-saw-1to8.bundle.json` — BUG-03 Clean polyphony — saw 1..8 (NOTE_ON×8, SETTINGS×0)
- `bug04-detune-drive100-sine-1to8.bundle.json` — BUG-04 Detune+Drive100% — sine 1..8 (NOTE_ON×8, SETTINGS×0)
- `bug04-detune-drive100-square-1to6.bundle.json` — BUG-04 Detune+Drive100% — square 1..6 (NOTE_ON×6, SETTINGS×0)
- `bug04-detune-drive100-triangle-1to7.bundle.json` — BUG-04 Detune+Drive100% — triangle 1..7 (NOTE_ON×7, SETTINGS×0)
- `bug04-detune-drive100-saw-1to7.bundle.json` — BUG-04 Detune+Drive100% — saw 1..7 (NOTE_ON×7, SETTINGS×0)
- `bug05-fullstack-sine-1to7.bundle.json` — BUG-05 Detune+Drive100+Sub+Warm — sine 1..7 (NOTE_ON×7, SETTINGS×0)
- `bug05-fullstack-square-1to5.bundle.json` — BUG-05 Detune+Drive100+Sub+Warm — square 1..5 (NOTE_ON×5, SETTINGS×0)
- `bug05-fullstack-triangle-1to6.bundle.json` — BUG-05 Detune+Drive100+Sub+Warm — triangle 1..6 (NOTE_ON×6, SETTINGS×0)
- `bug05-fullstack-saw-1to6.bundle.json` — BUG-05 Detune+Drive100+Sub+Warm — saw 1..6 (NOTE_ON×6, SETTINGS×0)
- `rq1-voice-ceiling-saw-clean.bundle.json` — RQ1 Voice ceiling — Saw clean climb (NOTE_ON×8, SETTINGS×0)
- `rq2-cutoff-move-saw.bundle.json` — RQ2 Cutoff move — Saw jitter (NOTE_ON×1, SETTINGS×16)
