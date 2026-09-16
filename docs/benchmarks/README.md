# מעקב בייצ׳מארקים (DSP / Realtime)

תיעוד מסודר של ריצות בייצ׳מארק על מכשיר, כדי להשוות ביצועים בין קומיטים ולזהות רגרסיות אחרי עדכונים.

## מבנה

```
docs/benchmarks/
  README.md                 # המדריך הזה
  INDEX.md                  # טבלת סיכום בין קומיטים
  TEMPLATE_SESSION.json     # תבנית לריצה חדשה
  results/
    <commit_short>/
      <YYYY-MM-DD>_<scenario>.json
      <YYYY-MM-DD>_<schema>.md
```

## איך מוסיפים סשן חדש

1. מעתיקים את `TEMPLATE_SESSION.json` לנתיב:
   `results/<commit_short>/<YYYY-MM-DD>_<scenario>.json`
2. ממלאים את המספרים מהמסך (Benchmark Complete + DSP Diagnostic).
3. כותבים סיכום אנושי ב־`.md` ליד ה־JSON.
4. מעדכנים שורה ב־`INDEX.md`.
5. פותחים PR / קומיט ל־GitHub.

## שדות חובה בסשן

- `commit` / `commit_short`
- `device`, `android`, `app_version`
- `audio` (sample rate, buffer, deadline)
- `scenario` (סוג רפרנס, BPM, wave, warm-up, משך)
- `runs[]` עם score, processing_ms, miss_rate, underruns, cpu, dsp_ms
- `aggregate` (מינימום סיכום על הריצות)

## תרחיש נוכחי

רפרנס נגינה קבוע **C**, פרופיל **DSP**, ללא warm-up — בסיס להשוואת גרסאות.
