"""Stroke geometry shared by prep, training and the emissions writer.

Units: key widths of the swipe.futo.org canvas, whose keys are a tenth of its
width (x in [0, 10]) and whose three letter rows split its height.
"""
import json
import re

import numpy as np

LETTERS = "abcdefghijklmnopqrstuvwxyz"
FRAMES = 64
MAX_LABEL = 24
WORD = re.compile(r"[A-Za-z]{2,}")


def squeeze(word):
    out = []
    for c in word:
        if not out or out[-1] != c:
            out.append(c)
    return "".join(out)


def futo_qwerty(path):
    """Letter centres of the dataset's QWERTY as (cx, row share) arrays in a..z order."""
    keys = {k["letter"]: k for k in json.load(open(path))["keys"]}
    return np.array([[keys[c]["cx"], keys[c]["cy"]] for c in LETTERS], np.float64)


def centres(layout, width, height):
    """(26, 2) canvas shares to key widths for a canvas of width x height px."""
    kw = width * 0.1
    return np.stack([layout[:, 0] * width / kw, layout[:, 1] * height / kw], 1)


def anchors(pts, letters, keys):
    """Index of the point that stands for each letter of the squeezed word: the
    monotone path through the points that is nearest, summed, to the letters'
    keys (two letters may share a point on a short stroke)."""
    cost = np.linalg.norm(pts[None, :, :] - keys[letters][:, None, :], axis=2)
    m, n = cost.shape
    acc = np.empty_like(cost)
    acc[0] = cost[0]
    for i in range(1, m):
        acc[i] = cost[i] + np.minimum.accumulate(acc[i - 1])
    out = np.empty(m, np.int64)
    out[-1] = int(np.argmin(acc[-1]))
    for i in range(m - 2, -1, -1):
        out[i] = int(np.argmin(acc[i, : out[i + 1] + 1]))
    return out


def segments(pts, letters, keys):
    """Per point: the letters (lo, hi) of the anchor span it lies in and its
    share s of that span's arc length, so that a layout that moves letter c by
    d[c] moves the point by (1 - s) d[lo] + s d[hi]."""
    n = len(pts)
    at = anchors(pts, letters, keys)
    lo = np.full(n, letters[0], np.int64)
    hi = np.full(n, letters[0], np.int64)
    s = np.zeros(n)
    arc = np.concatenate([[0.0], np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))])
    for i in range(len(letters) - 1):
        a, b = at[i], at[i + 1]
        if b <= a:
            continue
        span = arc[b] - arc[a]
        idx = np.arange(a, b)
        lo[idx], hi[idx] = letters[i], letters[i + 1]
        s[idx] = (arc[idx] - arc[a]) / span if span > 1e-9 else (idx - a) / (b - a)
    tail = at[-1]
    lo[tail:], hi[tail:], s[tail:] = letters[-1], letters[-1], 0.0
    return lo, hi, s


def transplant(pts, lo, hi, s, delta):
    """Points moved by the per-letter displacement delta (26, 2)."""
    return pts + (1 - s)[:, None] * delta[lo] + s[:, None] * delta[hi]


def frame_times(ts):
    dur = float(ts[-1] - ts[0])
    return np.linspace(0.0, dur, FRAMES) if dur > 0 else None


def resample(pts, ts, lo=None, hi=None, s=None):
    """FRAMES time-uniform frames (the FUTO encoder's 60 Hz then 64-step
    resample, without its double interpolation). With the span arrays, also
    each frame's span and share."""
    ts = np.maximum.accumulate(np.asarray(ts, np.float64) - ts[0])
    n = len(pts)
    want = frame_times(ts)
    if want is None:
        pos = np.linspace(0, n - 1, FRAMES)
    else:
        pos = np.interp(want, ts, np.arange(n, dtype=np.float64))
    k = np.minimum(np.floor(pos).astype(np.int64), n - 1)
    k1 = np.minimum(k + 1, n - 1)
    f = pos - k
    frames = pts[k] + f[:, None] * (pts[k1] - pts[k])
    if lo is None:
        return frames
    same = (lo[k] == lo[k1]) & (hi[k] == hi[k1])
    s_next = np.where(same, s[k1], 1.0)
    fs = s[k] + f * (s_next - s[k])
    return frames, lo[k], hi[k], fs


def record(r, qwerty):
    """A dataset line to (word, W, H, points in key widths, times ms) or None."""
    word = r["word"]
    if not WORD.fullmatch(word):
        return None
    word = word.lower()
    if len(word) > MAX_LABEL:
        return None
    W, H = float(r["canvas_width"]), float(r["canvas_height"])
    pts = r["data"]
    if len(pts) < 3 or W <= 0 or H <= 0:
        return None
    kw = W * 0.1
    xy = np.array([[p["x"] * W / kw, p["y"] * H / kw] for p in pts], np.float64)
    ts = np.array([p["t"] for p in pts], np.float64)
    return word, W, H, xy, ts - ts[0]
