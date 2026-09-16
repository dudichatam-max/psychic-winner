# DSP Profile — רפרנס A — `482dae8` (Copy tip)

| שדה | ערך |
|-----|------|
| Commit | [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94) |
| Ref | working baseline · כפתור Copy |
| קוד DSP | זהה ל־[`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) (Copy UI בלבד ב־BenchmarkScreen) |
| תאריך | 2026-09-16 |
| מכשיר | 2409BRN2CY · Android 14 · App 1.0 |
| אודיו | 48000 Hz · buffer 512 · deadline **10.667 ms** |
| תרחיש | רפרנס נגינה קבוע **A** · DSP · polyphony Dynamic 1–8 · BPM 70.54 · wave 1 · warm-up none · 48.25s · looper/drums OFF |
| מקור | **Copy** clipboard (לא צילומים) |

> **ABC:** תווית Looper/Drums ב־Copy **לא** אומרת שהם פעלו — הכפתורים מקושרים ל־Extreme בלבד. ב־A/B/C הלופר והתופים OFF בפועל.

## שלוש הריצות

| Run | Result | Score | Avg | P50 | P95 | P99 | Max | Misses | Miss % | Underruns | CPU avg | CPU peak |
|-----|--------|-------|-----|-----|-----|-----|-----|--------|--------|-----------|---------|----------|
| 1 | WARNING | **67**/100 | 10.630 | 10.630 | 11.118 | 11.618 | 21.518 | 1982 | **43.82** | **0** | 155.95 | 167.00 |
| 2 | WARNING | **67**/100 | 10.633 | 10.628 | 11.135 | 11.533 | 15.400 | 2019 | **44.63** | **0** | 157.63 | 167.90 |
| 3 | WARNING | **67**/100 | 10.631 | 10.634 | 11.120 | 11.404 | 22.054 | 2034 | **44.96** | **0** | 155.30 | 168.50 |

**ממוצע על 3 ריצות:** score **67** · avg ≈ **10.631 ms** · miss ≈ **44.47%** · underruns **0** · CPU avg ≈ **156.3%**

## צווארי בקבוק (ממוצע ms)

| רכיב | ms |
|------|-----|
| Voice | ~4.503 |
| Osc | ~1.678 |
| Drive | ~0.475 |
| Delay | ~0.288 |
| Reverb | ~0.247 |
| Piano | ~0.271 |

אין Div core / key-bus / pad effects פעילים בסשן זה (כולם 0).

## מסקנה

רפרנס **A** יציב מאוד (שלוש ריצות זהות ב־score) וקרוב לדדליין בממוצע (~10.63 ms מול 10.667). **Underruns 0** בכל הריצות — שיפור דרמטי מול רפרנס C ההיסטורי על אותו tip־קוד (~80%+ miss, מאות underruns). עדיין **WARNING** עם miss ~44% (P95/P99 מעל הדדליין).

נתונים מלאים: [`2026-09-16_ref-A_dsp-profile.json`](2026-09-16_ref-A_dsp-profile.json)
