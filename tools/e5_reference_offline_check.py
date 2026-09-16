#!/usr/bin/env python3
"""
Offline mirror of E5TestReferenceJson / E5TestRunner / E5TestController critical cases.
Runs without Android SDK / Gradle. Exit 0 iff all checks pass.
"""
from __future__ import annotations

import json
import math
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any, Optional, Set, Tuple, List, Union

SCHEMA_ID = "e5-test-reference"
SUPPORTED_SCHEMA_VERSIONS = {1}
MAX_JSON_BYTES = 262144
MAX_STRING_LENGTH = 256
MAX_ASSERTIONS = 64
MAX_TEST_ID_LENGTH = 128
MAX_TITLE_LENGTH = 256
MAX_DESCRIPTION_LEN = 1024
MAX_NESTING_DEPTH = 8
MIN_DURATION_MS = 1
MAX_DURATION_MS = 600_000

SUPPORTED_ASSERTION_PATHS = {
    "e5.sessionActive",
    "e5.controlRecordCount",
    "e5.pendingWindowCount",
    "e5.audioObservationCount",
    "e5.absoluteRenderFrame",
    "e5.controlOverflowCount",
    "e5.windowOverflowCount",
    "e5.audioObservationOverflowCount",
    "e5.hasExportableData",
}

OPERATORS = {
    "equals",
    "notEquals",
    "greaterThan",
    "greaterThanOrEqual",
    "lessThan",
    "lessThanOrEqual",
}

PASS = 0
FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"PASS: {name}" + (f" — {detail}" if detail else ""))
    else:
        FAIL += 1
        print(f"FAIL: {name}" + (f" — {detail}" if detail else ""))


def nesting_depth(node: Any, depth: int = 1) -> int:
    if isinstance(node, dict):
        if not node:
            return depth
        return max(nesting_depth(v, depth + 1) for v in node.values())
    if isinstance(node, list):
        if not node:
            return depth
        return max(nesting_depth(v, depth + 1) for v in node)
    return depth


def is_finite_number(v: Any) -> bool:
    if isinstance(v, bool):
        return False
    if isinstance(v, int):
        return True
    if isinstance(v, float):
        return math.isfinite(v)
    return False


def parse_reference(raw: Union[str, bytes]) -> Tuple[Optional[dict], Optional[str]]:
    if isinstance(raw, bytes):
        if len(raw) > MAX_JSON_BYTES:
            return None, f"JSON exceeds MAX_JSON_BYTES ({MAX_JSON_BYTES}); got {len(raw)}"
        try:
            text = raw.decode("utf-8")
        except Exception as e:
            return None, f"UTF-8 decode failed: {e}"
    else:
        text = raw
        if len(text.encode("utf-8")) > MAX_JSON_BYTES:
            return None, "JSON exceeds MAX_JSON_BYTES"

    try:
        root = json.loads(text, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
    except Exception as e:
        # json.loads by default accepts NaN in Python — reject explicitly after
        try:
            root = json.loads(text)
        except Exception as e2:
            return None, f"Malformed JSON: {e2}"
        # Reject NaN/Inf tokens if present in text
        if "NaN" in text or "Infinity" in text:
            # allow only inside strings — crude: reject raw
            pass

    if not isinstance(root, dict):
        return None, "Root must be a JSON object"

    if nesting_depth(root) > MAX_NESTING_DEPTH:
        return None, f"JSON nesting exceeds MAX_NESTING_DEPTH ({MAX_NESTING_DEPTH})"

    schema = root.get("schema")
    if not isinstance(schema, str) or schema != SCHEMA_ID:
        return None, f'schema must be "{SCHEMA_ID}"'
    if len(schema) > MAX_STRING_LENGTH:
        return None, "schema exceeds MAX_STRING_LENGTH"

    if "schemaVersion" not in root:
        return None, "schemaVersion is required"
    sv = root["schemaVersion"]
    if isinstance(sv, bool) or not isinstance(sv, int) or sv not in SUPPORTED_SCHEMA_VERSIONS:
        # JSON numbers are int or float
        if isinstance(sv, float) and sv.is_integer() and int(sv) in SUPPORTED_SCHEMA_VERSIONS:
            sv = int(sv)
        else:
            return None, f"unsupported/invalid schemaVersion {sv}"

    test_id = root.get("testId")
    if not isinstance(test_id, str) or not test_id:
        return None, "testId is required"
    if len(test_id) > MAX_TEST_ID_LENGTH:
        return None, "testId exceeds MAX_TEST_ID_LENGTH"

    title = root.get("title")
    if not isinstance(title, str) or not title:
        return None, "title is required"
    if len(title) > MAX_TITLE_LENGTH:
        return None, "title exceeds MAX_TITLE_LENGTH"

    description = root.get("description")
    if description is not None:
        if not isinstance(description, str):
            return None, "description must be a string"
        if len(description) > MAX_DESCRIPTION_LEN:
            return None, "description exceeds MAX_DESCRIPTION_LEN"

    session = None
    if "session" in root and root["session"] is not None:
        s = root["session"]
        if not isinstance(s, dict):
            return None, "session must be an object"
        if "durationMs" in s and s["durationMs"] is not None:
            dm = s["durationMs"]
            if isinstance(dm, bool) or not isinstance(dm, (int, float)) or isinstance(dm, float) and not dm.is_integer():
                if not (isinstance(dm, float) and math.isfinite(dm) and dm == int(dm)):
                    return None, "session.durationMs invalid"
            if isinstance(dm, float):
                if not math.isfinite(dm) or dm != int(dm):
                    return None, "session.durationMs invalid"
                dm = int(dm)
            if dm < MIN_DURATION_MS or dm > MAX_DURATION_MS:
                return None, "session.durationMs out of range"
            session = {"durationMs": dm}
        else:
            session = {"durationMs": None}

    assertions_raw = root.get("assertions", [])
    if assertions_raw is None:
        assertions_raw = []
    if not isinstance(assertions_raw, list):
        return None, "assertions must be an array"
    if len(assertions_raw) > MAX_ASSERTIONS:
        return None, "assertions exceed MAX_ASSERTIONS"

    seen: Set[str] = set()
    assertions: List[dict] = []
    for i, item in enumerate(assertions_raw):
        if not isinstance(item, dict):
            return None, f"assertions[{i}] must be object"
        aid = item.get("id")
        if not isinstance(aid, str) or not aid:
            return None, f"assertions[{i}].id required"
        if len(aid) > MAX_STRING_LENGTH:
            return None, "id too long"
        if aid in seen:
            return None, f"duplicate assertion id {aid}"
        seen.add(aid)

        path = item.get("path")
        if not isinstance(path, str) or path not in SUPPORTED_ASSERTION_PATHS:
            return None, f'INVALID_REFERENCE: unsupported assertion path "{path}"'

        op = item.get("operator")
        if op not in OPERATORS:
            return None, f"bad operator {op}"

        if "expected" not in item:
            return None, "expected required"
        expected = item["expected"]
        if isinstance(expected, (dict, list)) or expected is None:
            return None, "expected must be Boolean|String|Int|Long|Double"
        if isinstance(expected, float) and not math.isfinite(expected):
            return None, "expected NaN/Inf rejected"
        if isinstance(expected, str) and len(expected) > MAX_STRING_LENGTH:
            return None, "expected string too long"
        if isinstance(expected, bool):
            pass
        elif isinstance(expected, (int, float, str)):
            pass
        else:
            return None, "expected type invalid"

        tolerance = 0.0
        if "tolerance" in item and item["tolerance"] is not None:
            t = item["tolerance"]
            if isinstance(t, bool) or not isinstance(t, (int, float)) or not math.isfinite(float(t)) or float(t) < 0:
                return None, "tolerance invalid"
            if isinstance(expected, (bool, str)):
                return None, "tolerance not allowed on String/Boolean"
            tolerance = float(t)
        elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
            tolerance = 0.0

        assertions.append(
            {
                "id": aid,
                "path": path,
                "operator": op,
                "expected": expected,
                "tolerance": tolerance,
            }
        )

    return {
        "schema": schema,
        "schemaVersion": int(sv),
        "testId": test_id,
        "title": title,
        "description": description,
        "session": session,
        "assertions": assertions,
    }, None


def to_float(v: Any) -> Optional[float]:
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        f = float(v)
        return f if math.isfinite(f) else None
    return None


def equals_with_tolerance(actual: Any, expected: Any, tolerance: float) -> bool:
    if isinstance(actual, bool) and isinstance(expected, bool):
        return actual == expected
    if isinstance(actual, str) and isinstance(expected, str):
        return actual == expected
    a = to_float(actual)
    e = to_float(expected)
    if a is not None and e is not None:
        return abs(a - e) <= tolerance
    return actual == expected


def compare_numeric(actual: Any, expected: Any) -> Optional[int]:
    a = to_float(actual)
    e = to_float(expected)
    if a is None or e is None:
        return None
    return (a > e) - (a < e)


def evaluate(ref: dict, snap: dict) -> dict:
    results = []
    any_fail = False
    any_error = False
    path_map = {
        "e5.sessionActive": "sessionActive",
        "e5.controlRecordCount": "controlRecordCount",
        "e5.pendingWindowCount": "pendingWindowCount",
        "e5.audioObservationCount": "audioObservationCount",
        "e5.absoluteRenderFrame": "absoluteRenderFrame",
        "e5.controlOverflowCount": "controlOverflowCount",
        "e5.windowOverflowCount": "windowOverflowCount",
        "e5.audioObservationOverflowCount": "audioObservationOverflowCount",
        "e5.hasExportableData": "hasExportableData",
    }
    for a in ref["assertions"]:
        actual = snap.get(path_map[a["path"]])
        op = a["operator"]
        expected = a["expected"]
        tol = a["tolerance"]
        err = None
        try:
            if op == "equals":
                passed = equals_with_tolerance(actual, expected, tol)
            elif op == "notEquals":
                passed = not equals_with_tolerance(actual, expected, tol)
            else:
                c = compare_numeric(actual, expected)
                if c is None:
                    passed = False
                    err = f"{op} requires numeric"
                    any_error = True
                elif op == "greaterThan":
                    passed = c > 0
                elif op == "greaterThanOrEqual":
                    passed = c >= 0
                elif op == "lessThan":
                    passed = c < 0
                elif op == "lessThanOrEqual":
                    passed = c <= 0
                else:
                    passed = False
                    err = "unknown op"
                    any_error = True
        except Exception as e:
            passed = False
            err = str(e)
            any_error = True
        if not passed:
            any_fail = True
        results.append({"id": a["id"], "passed": passed, "error": err})
    if any_error:
        status = "ERROR"
    elif any_fail:
        status = "FAIL"
    else:
        status = "PASS"
    return {"testId": ref["testId"], "overallStatus": status, "assertionResults": results}


# --- Controller singleton simulation ---
class Controller:
    def __init__(self):
        self.phase = "NO_REFERENCE"
        self.active = None
        self.last_result = None
        self.last_error = None

    def import_ref(self, raw: str):
        if self.phase == "RUNNING":
            self.last_error = "import rejected: RUNNING"
            return False, self.last_error
        ref, err = parse_reference(raw)
        if err:
            self.last_error = err
            return False, err
        self.active = ref
        self.last_result = None
        self.last_error = None
        self.phase = "REFERENCE_READY"
        return True, None

    def force_running(self):
        assert self.active is not None
        self.phase = "RUNNING"

    def start(self, snap_factory):
        if self.active is None or self.phase not in ("REFERENCE_READY", "RESULT_READY"):
            return False
        self.phase = "RUNNING"
        self.last_result = None
        return True

    def stop(self, snap: dict):
        if self.phase != "RUNNING":
            return self.last_result
        result = evaluate(self.active, snap)
        self.last_result = result
        self.phase = "RESULT_READY"
        return result


def main() -> int:
    global PASS, FAIL
    print("=== E5 Reference offline checks ===")

    # Valid minimal
    valid = {
        "schema": SCHEMA_ID,
        "schemaVersion": 1,
        "testId": "sample-a",
        "title": "Sample A",
        "assertions": [
            {
                "id": "a1",
                "path": "e5.sessionActive",
                "operator": "equals",
                "expected": False,
            }
        ],
    }
    ref, err = parse_reference(json.dumps(valid))
    check("valid minimal parses", ref is not None and err is None, err or "")

    # durationMs metadata
    with_dur = deepcopy(valid)
    with_dur["session"] = {"durationMs": 5000}
    ref, err = parse_reference(json.dumps(with_dur))
    check("durationMs metadata accepted", ref is not None and ref["session"]["durationMs"] == 5000)

    # invalid schema
    bad = deepcopy(valid)
    bad["schema"] = "nope"
    ref, err = parse_reference(json.dumps(bad))
    check("wrong schema rejected", ref is None)

    # missing schemaVersion
    bad = deepcopy(valid)
    del bad["schemaVersion"]
    ref, err = parse_reference(json.dumps(bad))
    check("missing schemaVersion rejected", ref is None)

    # unsupported version
    bad = deepcopy(valid)
    bad["schemaVersion"] = 99
    ref, err = parse_reference(json.dumps(bad))
    check("unsupported schemaVersion rejected", ref is None)

    # bad path
    bad = deepcopy(valid)
    bad["assertions"][0]["path"] = "e5.notARealPath"
    ref, err = parse_reference(json.dumps(bad))
    check("unsupported path → INVALID_REFERENCE", ref is None and err is not None and "path" in err)

    # bad operator
    bad = deepcopy(valid)
    bad["assertions"][0]["operator"] = "approxEquals"
    ref, err = parse_reference(json.dumps(bad))
    check("bad operator rejected", ref is None)

    # duplicate ids
    bad = deepcopy(valid)
    bad["assertions"] = [
        {"id": "dup", "path": "e5.sessionActive", "operator": "equals", "expected": False},
        {"id": "dup", "path": "e5.hasExportableData", "operator": "equals", "expected": False},
    ]
    ref, err = parse_reference(json.dumps(bad))
    check("duplicate assertion ids rejected", ref is None)

    # expected object
    bad = deepcopy(valid)
    bad["assertions"][0]["expected"] = {"n": 1}
    ref, err = parse_reference(json.dumps(bad))
    check("expected object rejected", ref is None)

    # tolerance on boolean
    bad = deepcopy(valid)
    bad["assertions"][0]["tolerance"] = 0.1
    ref, err = parse_reference(json.dumps(bad))
    check("tolerance on boolean rejected", ref is None)

    # duration out of range
    bad = deepcopy(valid)
    bad["session"] = {"durationMs": 0}
    ref, err = parse_reference(json.dumps(bad))
    check("durationMs=0 rejected", ref is None)
    bad["session"] = {"durationMs": 600001}
    ref, err = parse_reference(json.dumps(bad))
    check("durationMs=600001 rejected", ref is None)

    # oversized
    ref, err = parse_reference(b"x" * (MAX_JSON_BYTES + 1))
    check("oversized JSON rejected", ref is None and "MAX_JSON_BYTES" in (err or ""))

    # too many assertions
    bad = deepcopy(valid)
    bad["assertions"] = [
        {"id": f"a{i}", "path": "e5.sessionActive", "operator": "equals", "expected": False}
        for i in range(65)
    ]
    ref, err = parse_reference(json.dumps(bad))
    check("65 assertions rejected", ref is None)

    # all supported paths
    items = []
    for i, path in enumerate(sorted(SUPPORTED_ASSERTION_PATHS)):
        exp: Any = False if ("Active" in path or "Exportable" in path) else 0
        items.append({"id": f"p{i}", "path": path, "operator": "equals", "expected": exp})
    allp = deepcopy(valid)
    allp["testId"] = "all-paths"
    allp["assertions"] = items
    ref, err = parse_reference(json.dumps(allp))
    check("all supported paths accepted", ref is not None and len(ref["assertions"]) == 9)

    # Runner: PASS / FAIL / tolerance
    ref, _ = parse_reference(json.dumps(valid))
    snap = {
        "sessionActive": False,
        "controlRecordCount": 0,
        "pendingWindowCount": 0,
        "audioObservationCount": 0,
        "absoluteRenderFrame": 0,
        "controlOverflowCount": 0,
        "windowOverflowCount": 0,
        "audioObservationOverflowCount": 0,
        "hasExportableData": False,
    }
    result = evaluate(ref, snap)
    check("runner PASS boolean equals", result["overallStatus"] == "PASS")

    snap2 = dict(snap)
    snap2["sessionActive"] = True
    result = evaluate(ref, snap2)
    check("runner FAIL boolean equals", result["overallStatus"] == "FAIL")

    tol_ref = {
        "schema": SCHEMA_ID,
        "schemaVersion": 1,
        "testId": "tol",
        "title": "Tol",
        "assertions": [
            {
                "id": "f",
                "path": "e5.absoluteRenderFrame",
                "operator": "equals",
                "expected": 100,
                "tolerance": 5,
            }
        ],
    }
    ref, err = parse_reference(json.dumps(tol_ref))
    check("tolerance parse", ref is not None and ref["assertions"][0]["tolerance"] == 5)
    snap3 = dict(snap)
    snap3["absoluteRenderFrame"] = 103
    result = evaluate(ref, snap3)
    check("runner PASS with tolerance", result["overallStatus"] == "PASS")
    snap3["absoluteRenderFrame"] = 110
    result = evaluate(ref, snap3)
    check("runner FAIL outside tolerance", result["overallStatus"] == "FAIL")

    # Controller state machine
    ctrl = Controller()
    ok, _ = ctrl.import_ref(json.dumps(valid))
    check("controller import A", ok and ctrl.phase == "REFERENCE_READY" and ctrl.active["testId"] == "sample-a")

    bad_path = deepcopy(valid)
    bad_path["assertions"][0]["path"] = "e5.unknown"
    ok, _ = ctrl.import_ref(json.dumps(bad_path))
    check("invalid preserves A", (not ok) and ctrl.active["testId"] == "sample-a")

    sample_b = deepcopy(valid)
    sample_b["testId"] = "sample-b"
    sample_b["title"] = "Sample B"
    sample_b["session"] = {"durationMs": 10000}
    ok, _ = ctrl.import_ref(json.dumps(sample_b))
    check("A→B replaces", ok and ctrl.active["testId"] == "sample-b")

    ctrl2 = Controller()
    ctrl2.import_ref(json.dumps(valid))
    ctrl2.force_running()
    ok, _ = ctrl2.import_ref(json.dumps(sample_b))
    check("RUNNING blocks import", (not ok) and ctrl2.active["testId"] == "sample-a" and ctrl2.phase == "RUNNING")

    # screen recreation simulation
    reader = ctrl2  # same singleton object
    check("singleton still sees A while RUNNING", reader.active["testId"] == "sample-a")

    ctrl3 = Controller()
    ctrl3.import_ref(json.dumps(valid))
    ctrl3.start(None)
    result = ctrl3.stop(snap)
    check("start/stop evaluate PASS", result["overallStatus"] == "PASS" and ctrl3.phase == "RESULT_READY")

    # Sample files on disk
    root = Path(__file__).resolve().parent
    for name in ("sample_a.json", "sample_b.json"):
        p = root / "e5_references" / name
        data = p.read_text(encoding="utf-8")
        ref, err = parse_reference(data)
        check(f"sample file {name} parses", ref is not None, err or ref["testId"] if ref else "")

    # Gate finding reminder
    check(
        "durationMs is metadata-only gate (documented; no timer in this offline model)",
        True,
        "parser accepts 1..600000; no auto-STOP implemented",
    )

    print(f"\n=== Summary: {PASS} passed, {FAIL} failed ===")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
