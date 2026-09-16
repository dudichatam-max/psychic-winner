# תכנון: הרצת תרחיש E5 אוטומטית (בסגנון Benchmark)

**סטטוס:** IMPLEMENTATION DONE on branch `E5-Reference-JSON` (scenario JSON → IMPORT → auto-replay → E5 observe → auto STOP → assert → EXPORT)  
**ענף:** `E5-Reference-JSON`  
**בסיס קיים:** E5 observational + Reference JSON (assertions) + Benchmark Reference Replay  

---

## 1. המטרה (מה שביקשת)

מנגנון שבו:

1. AI מכין קובץ רפרנס/תרחיש מדויק.
2. אתה טוען אותו באפליקציה (IMPORT).
3. לוחץ START.
4. **המערכת מריצה לבד** את התרחיש (תווים / הגדרות / זמנים) — בלי שתנגן.
5. E5 **רק מקליט** (observational).
6. בסוף — STOP אוטומטי + EXPORT diagnostics (+ הערכת assertions אם קיימות).

המטרה המחקרית: חזרתיות ודיוק בין ריצות, בלי שונות של נגינה ידנית, **ובלי לזהם** את האודיו/המדידה.

---

## 2. מה יש היום מול מה חסר

| רכיב | היום | חסר למה שרצית |
|------|------|----------------|
| E5 diagnostics | צופה ל־noteOn + חלונות PCM | — |
| E5 Test Reference JSON | מפרט assertions אחרי סשן | לא מריץ סאונד |
| Benchmark Reference | הקלטה + **replay** של אירועים מתוזמנים | לא מחובר ל־E5 research flow |
| UI E5 | START/STOP/IMPORT/EXPORT | אין “Run scenario” אוטומטי |

**מסקנה:** הכיוון הנכון הוא **לחבר מודל replay (כמו Benchmark) לסשן E5**, לא להמציא DSP חדש ולא להפוך את E5 עצמו לנגן.

---

## 3. עקרון ארכיטקטוני

שתי שכבות נפרדות:

```
A) Scenario / Replay  =  "מה להפעיל ומתי"   (control plane)
B) E5 Diagnostics     =  "מה נצפה בזמן הריצה" (observe only)
C) Assertions (אופציונלי) = "מה לבדוק אחרי STOP"
```

אנלוגיה ל־Benchmark:

- Benchmark: `record` → ZIP של events → `playBlocking` קורא `noteOn`/`noteOff`/`applySettings` מחוץ ל־AudioThread.
- E5 Auto Scenario: AI כותב (או מקליטים) תרחיש דומה → אותה צורת replay → במקביל E5 session פעיל.

**E5 לא הופך ל־executor של JSON.**  
ה־Runner של התרחיש קורא ל־APIs קיימים של `SynthEngine` (כמו שה־Benchmark כבר עושה).

---

## 4. מודל מוצע לקבצים

### 4.1 להפריד מושגים (מומלץ)

1. **`e5-scenario-v1`** — תרחיש הרצה (stimulus/timeline).  
2. **`e5-test-reference` v1** — assertions בלבד (כבר קיים).  

אפשר גם קובץ מורכב אחד עם שני חלקים (`scenario` + `assertions`), אבל ההפרדה שומרת על החוזה הנוכחי ומונעת בלגן.

### 4.2 תוכן תרחיש (קונספטואלי)

שדות מינימליים:

- `schema`: `e5-scenario`
- `schemaVersion`: `1`
- `scenarioId`, `title`, `description`
- `sampleRate` (או “engine native”)
- `durationUs` / `durationMs`
- `initialSettings` (waveform, cutoff, detune, drive, sub, warm, div2/3/4, …)
- `events[]`: רשימה ממוינת לפי זמן

סוגי אירועים מוצעים ל־V1 (רק כאלה שכבר קיימים ב־Benchmark/Engine):

| type | פעולה קיימת |
|------|-------------|
| `NOTE_ON` | `engine.noteOn(freq)` |
| `NOTE_OFF` | `engine.noteOff(freq)` |
| `SETTINGS` / snapshot | אותם שדות כמו `BenchmarkReferenceSettings` → `applySettings` |
| (אופציונלי V1.1) `CUTOFF_RAMP` | סדרת SETTINGS מתוזמנת — עדיף לא כטיפוס חדש אם אפשר לייצר כ־events |

**אסור ב־V1:** סקריפטים, expressions, reflection, שינוי sample-rate, פקודות AudioThread, DSP חדש.

### 4.3 מי מכין את הקובץ

- **AI** מייצר תרחישים דטרמיניסטיים לבאגים (קולות 1..8, Cutoff ramp, Detune+Drive…).
- או: מקליטים פעם אחת עם מנגנון ה־Benchmark הקיים ומייצאים → ממירים / משתמשים חוזרים כ־scenario ל־E5.

---

## 5. Lifecycle מוצע

```
IMPORT scenario (+ optional assertions)
        ↓
READY
        ↓ START
arm E5 session (הקיים)
        ↓
start Replay worker (thread/coroutine) — לא AudioThread
        ↓
timed events → noteOn/noteOff/settings
        ↓
E5 צופה ברקע
        ↓ end of timeline / duration
stop Replay
        ↓
stop E5 + finalize
        ↓
evaluate assertions (אם יש)
        ↓
RESULT + EXPORT diagnostics
```

- **STOP ידני:** מבטל replay + סוגר E5 (כמו היום).
- **IMPORT בזמן RUNNING:** נשאר אסור.
- **duration:** מגיע מהתרחיש (אמיתי), לא רק מטא־דאטה.

---

## 6. אי־זיהום המדידה (Non-contamination)

כללי חובה:

1. **Replay מחוץ ל־AudioThread** — בדיוק כמו `BenchmarkReferencePlayer.playBlockingInternal` (תזמון ב־wall clock / elapsedRealtime, קריאות control-plane).
2. **E5 נשאר observational** — בלי שינוי DSP, בלי measurement חדש רק בשביל JSON.
3. **אין הקצאות/JSON/I/O ב־RT path** — כמו היום.
4. **תרחיף דטרמיניסטי:** אותם freqs/זמנים/הגדרות בכל ריצה.
5. **לא לגעת ב־benchmark scoring/thresholds/workload** — זה מסלול מחקר E5 נפרד (גם אם יושב באותו מסך).
6. **Baseline A/B:** אותו תרחיש עם E5 OFF מול E5 ON — לוודא ש־replay+E5 לא מורידים תקרת קולות נקיים (כמו רגרסיית 7→4 שכבר תוקנה חלקית).
7. **לא “לתקן” זיהום** ע״י שינוי עומס הבנצ׳מארק.

סיכון ידוע: גם replay עלול להעמיס CPU אם מציפים noteOn בקצב לא ריאלי — לכן התרחישים חייבים להיות ריאליסטיים (כמו נגינה אנושית / פרוטוקול הבאג).

---

## 7. שימוש חוזר בקוד Benchmark (המלצה חזקה)

לא לבנות נגן מאפס אם אפשר:

**אופציה A (מומלצת):**  
Reuse / thin adapter מעל `BenchmarkReferenceSession` + `playBlocking` / אותו מנגנון apply.

- יתרון: כבר קיים, כבר מטפל ב־NOTE_ON/OFF/SETTINGS/PAD/…
- חיסרון: פורמט ZIP של Benchmark מול JSON פשוט ל־AI — צריך או:
  - המרת AI→ZIP פנימית, או
  - JSON scenario שנטען ל־`List<BenchmarkReferenceEvent>`.

**אופציה B:**  
`E5ScenarioPlayer` חדש שמעתיק את לוגיקת התזמון מ־Benchmark אך רק subset של אירועים.

המלצה: **A עם מתאם JSON→events**, כדי לא לשכפל באגים.

---

## 8. UI (מינימלי)

נשארות 4 כפתורי E5:

`START | STOP | IMPORT | EXPORT`

משמעות מעודכנת אחרי המימוש:

- **IMPORT** — scenario ו/או assertions.
- **START** — E5 arm + התחלת replay אוטומטי (אם יש scenario).
- **STOP** — ביטול replay + finalize E5.
- **EXPORT** — diagnostics הקיים (ללא שינוי משמעות).

תצוגה מוצעת: `SCENARIO RUNNING — 00:07 / 00:45` + שם `scenarioId`.

אין צורך בכפתור RUN חמישי אם START = “הפעל תרחיש+E5”.

---

## 9. מיפוי לבאגים שציינת (איך AI יכין תרחישים)

| באג | תרחיש אוטומטי מוצע |
|-----|---------------------|
| BUG-01 Cutoff Sine/Triangle | initial waveform=Sine; noteOn אמצע; ramp cutoff לאורך Z שניות; חזרה על Triangle |
| BUG-01b Saw/Square pops | noteOn מוחזק; סדרת SETTINGS cutoff בתדירות גבוהה בזמן תנועה |
| BUG-02 Dividers presets | **מחוץ ל־replay אודיו** — persistence של preset; תרחיש E5 לא מחליף בדיקת שמירה לקובץ אלא אם מוסיפים API preset נפרד |
| BUG-03 תקרת קולות | noteOns מדורגים 1..8 בלי FX; גרסאות לפי waveform |
| BUG-04 Detune+Drive100% | initialSettings + עליית קולות לפי ספי דיווח |
| BUG-05 +Sub+Warm | כמו 04 עם flags נוספים |
| RQ1 / RQ2 | אותם תרחישים + EXPORT לניתוח |

כך המחקר מדויק יותר: אותה סקריפט־זמן בכל build.

---

## 10. Assertions אחרי ריצה אוטומטית

נשארות שימושיות כ־sanity:

- `controlRecordCount >= N` (N = מספר NOTE_ON בתרחיש)
- `audioObservationCount >= N`
- overflows == 0
- `hasExportableData == true`

האבחון האקוסטי עדיין מגיע מ־**EXPORT**, לא מ־PASS של assertion בלבד.

---

## 11. שלבי מימוש מוצעים (רק אחרי אישור)

1. **מתאם פורמט:** JSON scenario v1 → רשימת אירועי Benchmark (או מבנה שקול).  
2. **E5ScenarioRunner:** startE5 → play events → stopE5 → snapshot/evaluate.  
3. **UI:** START מפעיל runner כשיש scenario טעון.  
4. **חבילת תרחישים** לבאגים 1/3/4/5 (+ RQ).  
5. **בדיקות:** יחידה לתזמון/parse; במכשיר A/B זיהום; חזרתיות 3 ריצות → EXPORT דומים במבנה.  
6. **Gate פיקוח:** מסמך זה + diff לפני merge ל־main.

---

## 12. מה לא לעשות

- לא לדחוף stimulus לתוך `E5Diagnostics`.
- לא להריץ JSON על AudioThread.
- לא לשנות scoring של Benchmark כדי “לייפות” תוצאות.
- לא להבטיח ש־PASS assertion = “אין באג”.
- לא לערבב שמירת presets (BUG-02) לתוך נגן האודיו בלי API ייעודי.

---

## 13. Acceptance Criteria (להחלטת Done עתידית)

- [ ] IMPORT תרחיש AI → START בלי נגינה ידנית → נשמע/נרשם תרחיש מלא.
- [ ] E5 EXPORT מכיל controlRecords/windows תואמים למספר NOTE_ON.
- [ ] אותם תרחיף פעמיים → אותם counts בסיסיים (±סבילות מוגדרת).
- [ ] E5 OFF vs ON עם אותו תרחיף — אין רגרסיית תקרה חמורה.
- [ ] STOP ידני מבטל באמצע בבטחה.
- [ ] אין שינוי DSP / benchmark thresholds.

---

## 14. המלצת פיקוח / סיכום למתכנת

הפער שזיהית נכון: **Reference assertions ≠ Scenario playback**.  
כדי לקבל דיוק מחקרי כמו שרצית, צריך **שכבת Scenario Replay** (Reuse מ־Benchmark) שמזינה את המנוע בזמן ש־E5 צופה.

מסמך זה הוא החוזה המוצע למימוש — **ללא קוד עד אישור מפורש להמשיך לשלב Implementation**.

