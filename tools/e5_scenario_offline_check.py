#!/usr/bin/env python3
"""
Offline mirror of E5ScenarioJson parse rules + sample validation.
No Android SDK / Gradle required. Exit 0 iff all checks pass.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any, Optional, Tuple, List, Dict

SCHEMA_ID = "e5-scenario"
BUNDLE_SCHEMA_ID = "e5-bundle"
REF_SCHEMA_ID = "e5-test-reference"
SUPPORTED_SCHEMA_VERSIONS = {1}
MAX_JSON_BYTES = 262144
MAX_EVENTS = 4096
MAX_SCENARIO_ID_LENGTH = 128
MAX_TITLE_LENGTH = 256
MAX_DESCRIPTION_LEN = 1024
MIN_DURATION_US = 1
MAX_DURATION_US = 600_000_000
EVENT_TYPES = {"NOTE_ON", "NOTE_OFF", "SETTINGS"}

PASS = 0
FAIL = 0
ROOT = Path(__file__).resolve().parent
SCENARIO_DIR = ROOT / "e5_scenarios"


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"PASS: {name}" + (f" — {detail}" if detail else ""))
    else:
        FAIL += 1
        print(f"FAIL: {name}" + (f" — {detail}" if detail else ""))


def is_finite_number(v: Any) -> bool:
    if isinstance(v, bool):
        return False
    if isinstance(v, int):
        return True
    if isinstance(v, float):
        return math.isfinite(v)
    return False


def parse_duration_us(root: dict) -> Tuple[Optional[int], Optional[str]]:
    has_us = "durationUs" in root and root["durationUs"] is not None
    has_ms = "durationMs" in root and root["durationMs"] is not None
    if not has_us and not has_ms:
        return None, "durationUs or durationMs required"
    if has_us:
        v = root["durationUs"]
        if not is_finite_number(v) or isinstance(v, float) and v != int(v):
            # allow int-valued floats
            if isinstance(v, float) and math.isfinite(v) and v == int(v):
                us = int(v)
            else:
                return None, "durationUs must be finite integer"
        else:
            us = int(v)
    else:
        v = root["durationMs"]
        if not is_finite_number(v):
            return None, "durationMs must be finite number"
        ms = int(v)
        us = ms * 1000
    if us < MIN_DURATION_US or us > MAX_DURATION_US:
        return None, f"duration out of range: {us}"
    return us, None


def parse_scenario(raw: Any) -> Tuple[Optional[dict], Optional[str]]:
    if isinstance(raw, (bytes, bytearray)):
        if len(raw) > MAX_JSON_BYTES:
            return None, f"exceeds MAX_JSON_BYTES ({len(raw)})"
        try:
            text = raw.decode("utf-8")
        except Exception as e:
            return None, f"UTF-8 decode failed: {e}"
    else:
        text = raw
        if len(text.encode("utf-8")) > MAX_JSON_BYTES:
            return None, "exceeds MAX_JSON_BYTES"
    try:
        root = json.loads(text)
    except Exception as e:
        return None, f"Malformed JSON: {e}"
    if not isinstance(root, dict):
        return None, "Root must be object"
    if root.get("schema") != SCHEMA_ID:
        return None, f"schema must be {SCHEMA_ID}"
    if root.get("schemaVersion") not in SUPPORTED_SCHEMA_VERSIONS:
        return None, "unsupported schemaVersion"
    sid = root.get("scenarioId")
    if not isinstance(sid, str) or not sid or len(sid) > MAX_SCENARIO_ID_LENGTH:
        return None, "scenarioId invalid"
    title = root.get("title")
    if not isinstance(title, str) or not title or len(title) > MAX_TITLE_LENGTH:
        return None, "title invalid"
    desc = root.get("description")
    if desc is not None and (not isinstance(desc, str) or len(desc) > MAX_DESCRIPTION_LEN):
        return None, "description invalid"
    duration_us, err = parse_duration_us(root)
    if err:
        return None, err
    events = root.get("events")
    if not isinstance(events, list):
        return None, "events must be array"
    if len(events) > MAX_EVENTS:
        return None, f"events exceed MAX_EVENTS ({len(events)})"
    parsed_events: List[dict] = []
    for i, ev in enumerate(events):
        if not isinstance(ev, dict):
            return None, f"events[{i}] must be object"
        t = ev.get("timeUs")
        if not is_finite_number(t) or int(t) < 0:
            return None, f"events[{i}].timeUs invalid"
        typ = ev.get("type")
        if typ not in EVENT_TYPES:
            return None, f"events[{i}].type invalid: {typ}"
        if typ in ("NOTE_ON", "NOTE_OFF"):
            freq = ev.get("freq")
            if not is_finite_number(freq) or float(freq) <= 0:
                return None, f"events[{i}].freq must be > 0"
        if typ == "SETTINGS":
            settings = ev.get("settings")
            if not isinstance(settings, dict):
                return None, f"events[{i}].settings must be object"
        parsed_events.append(ev)
    # sort check / allow unsorted (kotlin sorts)
    times = [int(e["timeUs"]) for e in parsed_events]
    return {
        "scenarioId": sid,
        "title": title,
        "durationUs": duration_us,
        "events": parsed_events,
        "times": times,
        "note_on_count": sum(1 for e in parsed_events if e.get("type") == "NOTE_ON"),
    }, None


def to_benchmark_events(scenario: dict) -> List[dict]:
    """Mirror mapping: STATE@0, NOTE_ON/OFF, SETTINGS→STATE, trailing STATE@duration."""
    out: List[dict] = [{"timeUs": 0, "type": "STATE"}]
    for ev in sorted(scenario["events"], key=lambda e: int(e["timeUs"])):
        typ = ev["type"]
        if typ == "NOTE_ON":
            out.append({"timeUs": int(ev["timeUs"]), "type": "NOTE_ON", "freq": float(ev["freq"])})
        elif typ == "NOTE_OFF":
            out.append({"timeUs": int(ev["timeUs"]), "type": "NOTE_OFF", "freq": float(ev["freq"])})
        elif typ == "SETTINGS":
            out.append({"timeUs": int(ev["timeUs"]), "type": "STATE"})
    last = max(e["timeUs"] for e in out)
    dur = max(scenario["durationUs"], last)
    if dur > last:
        out.append({"timeUs": dur, "type": "STATE"})
    return out


def parse_bundle(raw: str) -> Tuple[Optional[dict], Optional[str]]:
    try:
        root = json.loads(raw)
    except Exception as e:
        return None, str(e)
    if root.get("schema") != BUNDLE_SCHEMA_ID:
        return None, "not bundle"
    scen = root.get("scenario")
    ref = root.get("reference")
    if not isinstance(scen, dict) or not isinstance(ref, dict):
        return None, "bundle parts missing"
    # inject schema if nested omitted
    if "schema" not in scen:
        scen = dict(scen)
        scen["schema"] = SCHEMA_ID
        scen.setdefault("schemaVersion", 1)
    if "schema" not in ref:
        ref = dict(ref)
        ref["schema"] = REF_SCHEMA_ID
        ref.setdefault("schemaVersion", 1)
    s, err = parse_scenario(json.dumps(scen))
    if err:
        return None, f"scenario: {err}"
    if ref.get("schema") != REF_SCHEMA_ID:
        return None, "reference schema"
    return {"scenario": s, "reference": ref}, None


def main() -> int:
    check("scenario dir exists", SCENARIO_DIR.is_dir(), str(SCENARIO_DIR))

    samples = sorted(SCENARIO_DIR.glob("*.json"))
    check("samples present", len(samples) >= 5, f"count={len(samples)}")

    for path in samples:
        text = path.read_text(encoding="utf-8")
        check(f"{path.name} size<=MAX", len(text.encode("utf-8")) <= MAX_JSON_BYTES)
        root = json.loads(text)
        schema = root.get("schema")
        if schema == SCHEMA_ID:
            s, err = parse_scenario(text)
            check(f"parse {path.name}", s is not None, err or s["scenarioId"])
            if s:
                mapped = to_benchmark_events(s)
                check(
                    f"map {path.name} has NOTE_ON",
                    any(e["type"] == "NOTE_ON" for e in mapped),
                    f"events={len(mapped)} noteOns={s['note_on_count']}",
                )
                check(
                    f"map {path.name} duration covered",
                    mapped[-1]["timeUs"] >= s["durationUs"] or mapped[-1]["timeUs"] == max(s["times"] + [0]),
                    f"last={mapped[-1]['timeUs']} dur={s['durationUs']}",
                )
        elif schema == BUNDLE_SCHEMA_ID:
            b, err = parse_bundle(text)
            check(f"parse bundle {path.name}", b is not None, err or "")
            if b:
                ref = b["reference"]
                asserts = ref.get("assertions") or []
                control = next(
                    (a for a in asserts if a.get("path") == "e5.controlRecordCount"),
                    None,
                )
                if control is not None:
                    note_ons = b["scenario"]["note_on_count"]
                    expected = control.get("expected")
                    check(
                        f"bundle {path.name} controlRecordCount>=noteOns",
                        is_finite_number(expected) and int(expected) <= note_ons,
                        f"expected={expected} noteOns={note_ons}",
                    )
        else:
            check(f"{path.name} known schema", False, f"schema={schema}")

    # synthetic negatives
    bad, err = parse_scenario('{"schema":"e5-scenario","schemaVersion":1,"scenarioId":"x","title":"x","durationUs":100,"events":[{"timeUs":0,"type":"NOTE_ON","freq":0}]}')
    check("reject freq<=0", bad is None, err or "")

    bad, err = parse_scenario('{"schema":"nope","schemaVersion":1,"scenarioId":"x","title":"x","durationUs":100,"events":[]}')
    check("reject bad schema", bad is None, err or "")

    # durationMs conversion
    s, err = parse_scenario(json.dumps({
        "schema": SCHEMA_ID,
        "schemaVersion": 1,
        "scenarioId": "ms",
        "title": "ms",
        "durationMs": 3,
        "events": [{"timeUs": 0, "type": "NOTE_ON", "freq": 100.0}],
    }))
    check("durationMs→us", s is not None and s["durationUs"] == 3000, err or str(s and s["durationUs"]))

    print(f"\nSummary: {PASS} passed, {FAIL} failed")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
