# DSP Profile — רפרנס C — `d99c18d` (main tip)

| שדה | ערך |
|-----|------|
| Commit | [`d99c18d`](https://github.com/dudichatam-max/psychic-winner/commit/d99c18d5a9f7d50b45bc43b0120b88f27fc440f2) |
| Ref | main tip |
| קוד DSP | זהה ל־[`e40627e`](https://github.com/dudichatam-max/psychic-winner/commit/e40627ee2cd29a642161c7a5f1220f47bb480a73) (docs/README בלבד אחריו) |
| תאריך | 2026-09-16 |
| מכשיר | 2409BRN2CY · Android 14 · App 1.0 |
| אודיו | 48000 Hz · buffer 512 · deadline **10.667 ms** |
| תרחיש | רפרנס נגינה קבוע C · DSP profile · polyphony Dynamic 1–8 · BPM 70.54 · wave 1 · warm-up none · ~48.25s |

## שלוש הריצות

| Run | Result | Score | Avg | P50 | P95 | P99 | Max | Misses | Miss % | Underruns | CPU avg | CPU peak |
|-----|--------|-------|-----|-----|-----|-----|-----|--------|--------|-----------|---------|----------|
| 1 | FAIL | **9**/100 | 11.583 | 10.813 | 14.630 | 15.274 | 41.094 | 3258 | **78.32** | 712 | 180.77 | 203.19 |
| 2 | FAIL | **9**/100 | 11.676 | 10.894 | 14.743 | 15.565 | 27.055 | 3474 | **84.20** | 784 | 181.81 | 205.99 |
| 3 | FAIL | **2**/100 | 12.109 | 11.053 | 15.441 | 20.489 | 47.185 | 3416 | **85.92** | 1031 | 185.16 | 236.46 |

**ממוצע על 3 ריצות:** score ≈ **6.7** · avg ≈ **11.79 ms** · miss ≈ **82.8%** · underruns ≈ **842** · CPU avg ≈ **182.6%**

## צווארי בקבוק (ממוצע ms)

| רכיב | ms |
|------|-----|
| Div core | ~10.12 |
| Voice | ~7.56 |
| Osc | ~3.97 |
| Div2 contrib | ~2.77 |
| Key-bus Oct | ~2.20 |
| Key-bus Wah | ~1.64 |
| Key-bus Cho | ~1.51 |

ריצה 3 חריגה: Div core **11.398 ms** (מעל הדדליין לבדו) ו־CPU peak **236%**.

## מסקנה

עדיין **FAIL** על tip ה־main. שתי ריצות יציבות יחסית (score 9) וריצה אחת גרועה (score 2) — שונות בין ריצות גבוהה יותר מסשן `e40627e` הקודם. ממוצע עדיין בטווח דומה ל־`e40627e` וטוב יותר מ־`982bee7`, אבל לא realtime יציב.

השוואה: [982bee7 / e40627e / d99c18d](../../comparisons/ref-C_dsp-profile_661-681-d99.md)

נתונים מלאים: [`2026-09-16_ref-C_dsp-profile.json`](2026-09-16_ref-C_dsp-profile.json)
