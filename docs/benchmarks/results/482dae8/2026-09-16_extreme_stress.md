# Extreme / Stress — `482dae8` (מצב מצומצם, לא DSP)

| שדה | ערך |
|-----|------|
| Commit | [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94) |
| Ref | working baseline · כפתור Copy |
| פרופיל | **מצומצם / limited** — אין פרופיל DSP לסטרס בבילד זה |
| תאריך | 2026-09-16 |
| מכשיר | 2409BRN2CY · Android 14 · App 1.0 |
| אודיו | 48000 Hz · buffer 512 · deadline **10.667 ms** |
| תרחיש | Stress workload: generated benchmark · warm-up 3s · measurement 30s · polyphony Dynamic 1–8 |
| מקור | **Copy** clipboard |
| DSP DIAGNOSTIC | **אין** בפלט |

> סשן **Extreme** (תקרת עומס). בניגוד ל־ABC, כאן לופר/תופים אמורים להיות חלק מתכנון הבדיקה. פרופיל DSP לסטרס נדחה לסיבוב הבא.

## שלוש הריצות

| Run | Result | Score | Avg | P50 | P95 | P99 | Max | Misses | Miss % | Underruns | CPU avg | CPU peak |
|-----|--------|-------|-----|-----|-----|-----|-----|--------|--------|-----------|---------|----------|
| 1 | FAIL | **0**/100 | 22.690 | 23.291 | 31.311 | 33.524 | 58.287 | 1262 | **95.53** | 1528 | 192.62 | 203.88 |
| 2 | FAIL | **0**/100 | 22.367 | 22.842 | 31.343 | 31.750 | 32.801 | 1281 | **95.60** | 1559 | 192.56 | 201.59 |
| 3 | FAIL | **0**/100 | 22.526 | 23.196 | 31.588 | 32.036 | 36.688 | 1272 | **95.57** | 1503 | 192.51 | 202.89 |

**ממוצע על 3 ריצות:** score **0** · avg ≈ **22.528 ms** · miss ≈ **95.566%** · underruns ≈ **1530** · CPU avg ≈ **192.563%**

## מסקנה

תקרת סטרס יציבה וקטסטרופלית: ~2.1× מעל הדדליין, miss ~95.6%, אלפי underruns, score 0 בכל ריצה. מספיק כגבול עליון לבסיס העבודה יחד עם ABC ב־DSP; חסר פירוק רכיבים — מומלץ DSP/diagnostic לסטרס בסיבוב הבא.

נתונים מלאים: [`2026-09-16_extreme_stress.json`](2026-09-16_extreme_stress.json)
