# E5 Auto Scenarios — שימוש

## מה זה
קבצי `e5-scenario` (stimulus/timeline) ואופציונלית `e5-bundle` (scenario + assertions).
אחרי IMPORT + START המערכת מריצה לבד NOTE_ON/OFF/SETTINGS דרך Benchmark replay — **בלי נגינה ידנית**.

## במכשיר (ענף `E5-Reference-JSON`)
1. Benchmark screen → **IMPORT** קובץ מתיקייה זו (או bundle).
2. **START** → מופיע `SCENARIO RUNNING…` → replay אוטומטי + E5 צופה.
3. בסוף התרחיש → STOP אוטומטי + הערכת assertions אם יש reference.
4. **EXPORT** diagnostics כמו קודם (4 כפתורים בלבד).

## קבצים
| קובץ | באג | משך בקירוב |
|------|-----|-------------|
| `bug03-polyphony-climb.json` | BUG-03 1..8 voices | ~25s |
| `bug03-polyphony-climb.bundle.json` | scenario+assertions (≥8) | |
| `bug04-detune-drive.json` | BUG-04 Detune+Drive | ~15s |
| `bug04-detune-drive.bundle.json` | scenario+assertions (≥5) | |
| `bug05-sub-warm.json` | BUG-05 +Sub+Warm | ~13s |
| `bug01-cutoff-ramp-sine.json` | BUG-01 cutoff ramp | ~18s |
| `bug01-cutoff-ramp-sine.bundle.json` | scenario+assertions | |
| `bug01b-cutoff-steps-saw-square.json` | BUG-01b steps | ~11s |

## Schema V1
- `schema`: `e5-scenario` | `e5-bundle` | `e5-test-reference`
- Events: `NOTE_ON` / `NOTE_OFF` (freq>0) / `SETTINGS` (patch)
- `durationUs` או `durationMs`
- Waveform: 0=Sine, 1=Square, 2=Triangle, 3=Saw, 4=Noise

## בדיקת offline
```bash
python3 tools/e5_scenario_offline_check.py
```

**PASS של assertions ≠ "אין באג"** — רק evidence health; אבחון אקוסטי מ־EXPORT.
