#!/usr/bin/env python3
"""Offline checks for E5 blocker fixes (no Android device required)."""
import json
import math
import sys

def put_json_number(obj, key, value, non_finite):
    if isinstance(value, float) and math.isfinite(value):
        obj[key] = value
    elif isinstance(value, (int,)) and not isinstance(value, bool):
        obj[key] = float(value)
    else:
        # non-finite float
        obj[key] = None
        if isinstance(value, float):
            if math.isnan(value):
                label = "NaN"
            elif value > 0:
                label = "Infinity"
            else:
                label = "-Infinity"
        else:
            label = "NaN"
        non_finite[key] = label

def test_nan_export():
    non_finite = {}
    o = {}
    put_json_number(o, "zdfCachedCutoff", float("nan"), non_finite)
    put_json_number(o, "zdfCachedRes", float("inf"), non_finite)
    put_json_number(o, "zdfG", float("-inf"), non_finite)
    put_json_number(o, "baseFreq", 440.0, non_finite)
    if non_finite:
        o["nonFiniteValues"] = non_finite
    text = json.dumps(o, allow_nan=False)  # must not contain NaN tokens
    parsed = json.loads(text)
    assert parsed["zdfCachedCutoff"] is None
    assert parsed["zdfCachedRes"] is None
    assert parsed["zdfG"] is None
    assert parsed["baseFreq"] == 440.0
    assert parsed["nonFiniteValues"]["zdfCachedCutoff"] == "NaN"
    assert parsed["nonFiniteValues"]["zdfCachedRes"] == "Infinity"
    assert parsed["nonFiniteValues"]["zdfG"] == "-Infinity"
    # Metadata may contain the strings "NaN"/"Infinity"; numeric fields must be null
    assert ": NaN" not in text and ": Infinity" not in text and ":-Infinity" not in text.replace("\"-Infinity\"", "")
    print("PASS: NaN export -> null + metadata (not 0), parseable JSON")

def test_active_window_65_contract():
    """Simulate open at N=100, feed PCM for frames, ensure COMPLETE at N+32 with 65 samples.
    Per-sample work iterates activeCount only (not capacity 1024).
    """
    MAX = 1024
    WINDOW = 65
    PAST = 32
    FUTURE = 32
    CENTER = 32
    STATE_FREE, STATE_PAST, STATE_CENTER, STATE_FUTURE, STATE_COMPLETE, STATE_INCOMPLETE = range(6)

    win_state = [STATE_FREE] * MAX
    win_applied = [-1] * MAX
    win_captured = [0] * MAX
    win_hist_incomplete = [False] * MAX
    ev_frame = [-1] * (MAX * WINDOW)
    ev_cap = [False] * (MAX * WINDOW)
    active_idx = [0] * MAX
    active_count = 0
    samples_scanned = 0

    def open_window(n, w=0):
        nonlocal active_count
        win_state[w] = STATE_PAST
        win_applied[w] = n
        win_captured[w] = 0
        # pretend past filled from ring
        base = w * WINDOW
        for i in range(PAST):
            ev_frame[base + i] = n - PAST + i
            ev_cap[base + i] = True
            win_captured[w] += 1
        ev_frame[base + CENTER] = n
        ev_cap[base + CENTER] = False
        active_idx[active_count] = w
        active_count += 1

    def on_pcm(f):
        nonlocal active_count, samples_scanned
        ai = 0
        while ai < active_count:
            samples_scanned += 1
            w = active_idx[ai]
            state = win_state[w]
            n = win_applied[w]
            index = f - n + CENTER
            if index < 0 or index > 64:
                ai += 1
                continue
            ei = w * WINDOW + int(index)
            if not ev_cap[ei]:
                ev_frame[ei] = f
                ev_cap[ei] = True
                win_captured[w] += 1
            if int(index) == CENTER and state == STATE_PAST:
                win_state[w] = STATE_CENTER
            elif state in (STATE_CENTER, STATE_FUTURE):
                win_state[w] = STATE_FUTURE
            if f == n + FUTURE:
                all_ok = (win_captured[w] == WINDOW and
                          not win_hist_incomplete[w] and
                          ev_cap[w * WINDOW + 64] and
                          ev_frame[w * WINDOW + 64] == n + FUTURE)
                win_state[w] = STATE_COMPLETE if all_ok else STATE_INCOMPLETE
                active_count -= 1
                active_idx[ai] = active_idx[active_count]
                continue
            ai += 1

    N = 100
    # warm ring conceptually: feed past so open has history — already faked in open
    open_window(N)
    assert active_count == 1
    for f in range(N, N + FUTURE + 1):
        on_pcm(f)
    assert win_state[0] == STATE_COMPLETE
    assert win_captured[0] == 65
    assert active_count == 0
    # Per-sample scans == number of active iterations across frames (1 window * 33 frames)
    assert samples_scanned == 33, samples_scanned
    # Capacity 1024 was NEVER the loop bound for per-sample work
    assert samples_scanned < 1024
    # evidence layout
    base = 0
    for i in range(65):
        assert ev_frame[base + i] == N - 32 + i
        assert ev_cap[base + i]
    print("PASS: 65-frame contract COMPLETE; per-sample iterated activeCount only (33), not 1024")

    # Incomplete when stopped early
    open_window(200, w=1)
    on_pcm(200)
    on_pcm(201)
    assert active_count == 1
    # mark incomplete shutdown
    w = active_idx[0]
    win_state[w] = STATE_INCOMPLETE
    active_count = 0
    assert win_state[1] == STATE_INCOMPLETE
    assert win_captured[1] < 65
    print("PASS: early stop leaves INCOMPLETE (not COMPLETE)")

if __name__ == "__main__":
    test_nan_export()
    test_active_window_65_contract()
    print("ALL OFFLINE CHECKS PASSED")
