# DSP Profile — רפרנס B — `482dae8` (Copy tip)

| שדה | ערך |
|-----|------|
| Commit | [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94) |
| Ref | working baseline · כפתור Copy |
| קוד DSP | זהה ל־[`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) (Copy UI בלבד) |
| תאריך | 2026-09-16 |
| מכשיר | 2409BRN2CY · Android 14 · App 1.0 |
| אודיו | 48000 Hz · buffer 512 · deadline **10.667 ms** |
| תרחיש | רפרנס נגינה קבוע **B** · DSP · polyphony Dynamic 1–8 · BPM 70.54 · wave 1 · warm-up none · 48.25s · looper/drums OFF |
| מקור | **Copy** clipboard |

> **ABC:** תווית Looper/Drums ב־Copy **לא** אומרת שהם פעלו — הכפתורים מקושרים ל־Extreme בלבד. ב־A/B/C הלופר והתופים OFF בפועל.

## שלוש הריצות

| Run | Result | Score | Avg | P50 | P95 | P99 | Max | Misses | Miss % | Underruns | CPU avg | CPU peak |
|-----|--------|-------|-----|-----|-----|-----|-----|--------|--------|-----------|---------|----------|
| 1 | FAIL | **20**/100 | 10.744 | 10.644 | 12.540 | 12.860 | 18.783 | 2161 | **48.24** | 89 | 179.49 | 194.61 |
| 2 | FAIL | **21**/100 | 10.757 | 10.680 | 12.471 | 12.701 | 23.366 | 2287 | **51.04** | 86 | 177.91 | 200.50 |
| 3 | FAIL | **21**/100 | 10.759 | 10.697 | 12.448 | 12.735 | 19.406 | 2346 | **52.34** | 83 | 180.59 | 194.82 |

**ממוצע על 3 ריצות:** score ≈ **20.667** · avg ≈ **10.753 ms** · miss ≈ **50.539%** · underruns ≈ **86** · CPU avg ≈ **179.33%**

## צווארי בקבוק (ממוצע ms)

| רכיב | ms |
|------|-----|
| Key-bus Oct | ~3.79 |
| Voice | ~4.76 |
| Key-bus Wah | ~1.814 |
| Osc | ~1.744 |
| Key-bus Cho | ~1.319 |
| Reverb | ~0.656 |
| Phaz | ~0.52 |
| Fuzz | ~0.451 |
| Warm | ~0.441 |

אין Div core. Key-bus + fx פעילים (בניגוד לרפרנס A).

## מסקנה

רפרנס **B** יציב בין ריצות אבל **FAIL**: avg מעל הדדליין, underruns ~80–90, score ~20–21. כבד משמעותית מ־A בגלל key-bus Oct/Wah/Cho ואפקטים.

נתונים מלאים: [`2026-09-16_ref-B_dsp-profile.json`](2026-09-16_ref-B_dsp-profile.json)
