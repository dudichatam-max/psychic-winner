# DSP Profile — רפרנס C — `482dae8` (Copy tip)

| שדה | ערך |
|-----|------|
| Commit | [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94) |
| Ref | working baseline · כפתור Copy |
| קוד DSP | זהה ל־[`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) (Copy UI בלבד) |
| תאריך | 2026-09-16 |
| מכשיר | 2409BRN2CY · Android 14 · App 1.0 |
| אודיו | 48000 Hz · buffer 512 · deadline **10.667 ms** |
| תרחיש | רפרנס נגינה קבוע **C** · DSP · BPM 70.54 · wave 1 · warm-up none · 48.25s |
| Looper / Drums | **OFF בפועל** (ב־ABC הכפתורים מקושרים רק ל־Extreme; תווית Copy עלולה להציג ON בטעות) |
| מקור | **Copy** clipboard |

> **הבהרה:** בסשני A/B/C אין להתייחס ל־`Looper replay` / `Drums replay` בטקסט Copy כאילו פעלו. הם לא פעלו. הקישור האמיתי הוא לבדיקת **Extreme** בלבד. ב־C2–C3 Copy הציג ON/ON — זה UI בלבד.

## שלוש הריצות

| Run | Copy UI (Looper/Drums) | בפועל | Result | Score | Avg | P50 | P95 | P99 | Max | Misses | Miss % | Underruns | CPU avg | CPU peak |
|-----|------------------------|-------|--------|-------|-----|-----|-----|-----|-----|--------|--------|-----------|---------|----------|
| 1 | OFF / OFF | OFF / OFF | FAIL | **12**/100 | 11.395 | 10.773 | 14.099 | 14.382 | 21.138 | 3079 | **72.74** | 583 | 184.09 | 198.10 |
| 2 | ON / ON *(תווית)* | OFF / OFF | FAIL | **13**/100 | 11.409 | 10.780 | 14.074 | 14.321 | 29.682 | 3174 | **75.09** | 588 | 183.08 | 199.20 |
| 3 | ON / ON *(תווית)* | OFF / OFF | FAIL | **13**/100 | 11.416 | 10.785 | 14.101 | 14.319 | 27.494 | 3235 | **76.57** | 594 | 182.22 | 197.41 |

**ממוצע על 3 ריצות:** score ≈ **12.667** · avg ≈ **11.407 ms** · miss ≈ **74.798%** · underruns ≈ **588.333** · CPU avg ≈ **183.13%**

## צווארי בקבוק (ממוצע ms)

| רכיב | ms |
|------|-----|
| Div core | ~11.697 |
| Voice | ~7.668 |
| Osc | ~4.414 |
| Div2 contrib | ~3.862 |
| Key-bus Wah | ~1.254 |
| Div3 contrib | ~1.151 |
| Div4 contrib | ~1.111 |
| Key-bus Cho | ~0.912 |
| Key-bus Oct | ~0.839 |
| Reverb | ~0.684 |

Div core לבדו מעל הדדליין (~11.7 ms).

## מסקנה

רפרנס **C** על `482dae8` — **FAIL** כבד (score ~12–13, underruns ~590, miss ~75%). דומה לסשני C ההיסטוריים. אין לייחס הבדלים בין ריצות ללופר/תופים — הם לא היו פעילים ב־ABC.

נתונים מלאים: [`2026-09-16_ref-C_dsp-profile.json`](2026-09-16_ref-C_dsp-profile.json)
