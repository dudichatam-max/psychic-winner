# אינדקס בייצ׳מארקים

השוואה מהירה בין קומיטים. פירוט מלא בכל קובץ סשן.

## רפרנס C / DSP profile

| Commit | Ref | תאריך | n | Score (min–max / mean) | Avg ms (mean) | Miss % (mean) | Underruns (mean) | CPU avg % | סטטוס |
|--------|-----|--------|---|-------------------------|---------------|---------------|------------------|-----------|--------|
| [`982bee7`](https://github.com/dudichatam-max/psychic-winner/commit/982bee72860d9f090c6adda14031545c35070e11) | #661 | 2026-09-16 | 3 | 1–4 / 2.3 | 12.756 | 86.01 | 1473 | 181.5 | FAIL בסיס ישן — [פירוט](results/982bee7/2026-09-16_ref-C_dsp-profile.md) |
| [`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) | #681 | 2026-09-16 | 3 | 7–10 / 8.3 | 11.701 | 78.88 | 790 | 181.8 | FAIL — [פירוט](results/e40627e/2026-09-16_ref-C_dsp-profile.md) |
| [`d99c18d`](https://github.com/dudichatam-max/psychic-winner/commit/d99c18d5a9f7d50b45bc43b0120b88f27fc440f2) | main tip | 2026-09-16 | 3 | 2–9 / 6.7 | 11.789 | 82.81 | 842 | 182.6 | FAIL tip נוכחי — [פירוט](results/d99c18d/2026-09-16_ref-C_dsp-profile.md) |

## רפרנס A / B

| תרחיש | Commit | n | סטטוס |
|--------|--------|---|--------|
| reference A · DSP profile | `d99c18d` (מתוכנן) | 3 | ממתין לתוצאות |
| reference B · DSP profile | `d99c18d` (מתוכנן) | 3 | ממתין לתוצאות |

## השוואות

- [982bee7 (#661) → e40627e (#681)](comparisons/982bee7-vs-e40627e_ref-C_dsp-profile.md)
- [982bee7 / e40627e / d99c18d](comparisons/ref-C_dsp-profile_661-681-d99.md)

## הערות

- דדליין: **10.667 ms** (48 kHz / buffer 512).
- מכשיר ייחוס: `2409BRN2CY` / Android 14.
- `d99c18d` tip של main; קוד DSP זהה ל־`e40627e`.
