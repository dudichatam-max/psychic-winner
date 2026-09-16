# סיכום בסיס עבודה — `482dae8` · ABC (DSP) + Extreme (מצומצם)

Commit: [`482dae8`](https://github.com/dudichatam-max/psychic-winner/commit/482dae8e5cfd0f3d1f4825f2f975015ac3e35d94) · 2026-09-16 · Device 2409BRN2CY · 48 kHz / 512 · deadline 10.667 ms

## טבלת סיכום (ממוצע על 3 ריצות)

| Scenario | Profile | Result | Score | Avg ms | Miss % | Underruns | הערה |
|----------|---------|--------|-------|--------|--------|-----------|------|
| **A** | DSP | WARNING | **67** | **10.631** | 44.5 | **0** | קל; אין Div/key-bus |
| **B** | DSP | FAIL | **20.7** | **10.753** | 50.5 | **86** | Key-bus Oct/Wah/Cho |
| **C** | DSP | FAIL | **12.7** | **11.407** | 74.8 | **588** | Div core ~11.7 ms |
| **Extreme** | limited (לא DSP) | FAIL | **0** | **22.528** | 95.6 | **1530** | אין DSP DIAGNOSTIC |

## הבהרות

1. **ABC:** תווית Looper/Drums ב־Copy היא UI בלבד (מקושרת ל־Extreme) — **לא פעלו** ב־A/B/C.
2. **Extreme:** מצב מצומצם; פלט בלי פירוק DSP. DSP לסטרס — שיפור לסיבוב הבא, לא חוסם את הבסיס הזה.
3. סולם ברור: A כמעט על הדדליין ובלי underruns → B/C נשברים עם עומס אפקטים/Div → Extreme קורס לחלוטין.

## קבצים

- [A](../results/482dae8/2026-09-16_ref-A_dsp-profile.md)
- [B](../results/482dae8/2026-09-16_ref-B_dsp-profile.md)
- [C](../results/482dae8/2026-09-16_ref-C_dsp-profile.md)
- [Extreme](../results/482dae8/2026-09-16_extreme_stress.md)
- [תיקיית 482dae8](../results/482dae8/README.md)
