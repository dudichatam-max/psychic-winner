# אינדקס בייצ׳מארקים

## Working baseline — `482dae8` (Copy)

Tip נוכחי לבניית בסיס עבודה. פירוט: [results/482dae8/README.md](results/482dae8/README.md)

| Scenario | Commit | n | Profile | Looper/Drums | סטטוס |
|----------|--------|---|--------|--------------|--------|
| Reference **A** | `482dae8` | 3 | DSP | OFF (UI-only in ABC) | **WARNING** score **67** · avg **10.631** · miss **44.5%** · underruns **0** — [פירוט](results/482dae8/2026-09-16_ref-A_dsp-profile.md) |
| Reference **B** | `482dae8` | 3 | DSP | OFF (UI-only in ABC) | **FAIL** score ≈**20.7** · avg **10.753** · miss **50.5%** · underruns ≈**86** — [פירוט](results/482dae8/2026-09-16_ref-B_dsp-profile.md) |
| Reference **C** | `482dae8` | 3 | DSP | OFF (UI-only in ABC) | **FAIL** score ≈**12.7** · avg **11.407** · miss **74.8%** · underruns ≈**588** — [פירוט](results/482dae8/2026-09-16_ref-C_dsp-profile.md) |
| **Extreme** | `482dae8` | 3 | limited (לא DSP) | Stress generated | **FAIL** score **0** · avg **22.528** · miss **95.6%** · underruns ≈**1530** — [פירוט](results/482dae8/2026-09-16_extreme_stress.md) |

בסיס מלא: [סיכום ABC+Extreme](comparisons/482dae8_ABC-Extreme_summary.md). קבצים תחת `results/482dae8/`.

> **ABC:** תווית Looper/Drums ב־Copy **לא משקפת הפעלה** — הכפתורים מקושרים ל־Extreme בלבד. רק בסשן Extreme הלופר והתופים באמת ON.

---

## היסטוריה — רפרנס C / DSP (לפני Copy tip)

| Commit | Ref | תאריך | n | Score mean | Avg ms | Miss % | Underruns | סטטוס |
|--------|-----|--------|---|------------|--------|--------|-----------|--------|
| [`982bee7`](https://github.com/dudichatam-max/psychic-winner/commit/982bee72860d9f090c6adda14031545c35070e11) | #661 | 2026-09-16 | 3 | 2.3 | 12.756 | 86.01 | 1473 | FAIL — [פירוט](results/982bee7/2026-09-16_ref-C_dsp-profile.md) |
| [`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) | #681 | 2026-09-16 | 3 | 8.3 | 11.701 | 78.88 | 790 | FAIL — [פירוט](results/e40627e/2026-09-16_ref-C_dsp-profile.md) |
| [`d99c18d`](https://github.com/dudichatam-max/psychic-winner/commit/d99c18d5a9f7d50b45bc43b0120b88f27fc440f2) | main tip אז | 2026-09-16 | 3 | 6.7 | 11.789 | 82.81 | 842 | FAIL — [פירוט](results/d99c18d/2026-09-16_ref-C_dsp-profile.md) |

## השוואות ישנות

- [982bee7 → e40627e](comparisons/982bee7-vs-e40627e_ref-C_dsp-profile.md)
- [982bee7 / e40627e / d99c18d](comparisons/ref-C_dsp-profile_661-681-d99.md)

## הערות

- דדליין: **10.667 ms** (48 kHz / buffer 512).
- מכשיר ייחוס: `2409BRN2CY` / Android 14.
- מ־`482dae8`: עדיף **Copy** לטקסט מלא במקום צילומי מסך.
