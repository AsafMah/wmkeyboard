"""Redraw swipe.futo.org QWERTY strokes on other layouts, for the eval.

usage: transplant.py <test.jsonl> <qwerty.json> <out dir> [layout ...]

Each stroke is cut at the points nearest its letters' QWERTY keys (a
monotone fit, `geo.anchors`), and every point is moved by the blend of its
span's two letters' displacements to the new layout. The stroke keeps the
writer's timing, wobble, overshoot and miss of each key; only where the keys
are changes. Writes <layout>.jsonl (dataset records) and <layout>.json (the
dataset's layout format) for both `emit.py` and wm-engine's glide_beam_eval.
"""
import json
import os
import sys

import numpy as np

from geo import LETTERS, centres, futo_qwerty, record, segments, squeeze, transplant
from layouts import NAMED


def main():
    src, qwerty_path, out = sys.argv[1:4]
    names = sys.argv[4:] or [n for n in NAMED if n != "qwerty"]
    os.makedirs(out, exist_ok=True)
    qwerty = futo_qwerty(qwerty_path)
    files = {n: open(f"{out}/{n}.jsonl", "w") for n in names}
    for n in names:
        keys = [{"letter": c, "cx": float(x), "cy": float(y), "rx": 0.05, "ry": 1 / 6} for c, (x, y) in zip(LETTERS, NAMED[n])]
        json.dump({"name": n, "letters": LETTERS, "keys": keys}, open(f"{out}/{n}.json", "w"), indent=1)
    count = 0
    for ln in open(src):
        r = json.loads(ln)
        got = record(r, qwerty)
        if got is None:
            continue
        word, W, H, xy, ts = got
        Q = centres(qwerty, W, H)
        letters = np.array([LETTERS.index(c) for c in squeeze(word)])
        lo, hi, s = segments(xy, letters, Q)
        kw = W * 0.1
        for n in names:
            moved = transplant(xy, lo, hi, s, centres(NAMED[n], W, H) - Q)
            data = [{"t": p["t"], "x": float(x * kw / W), "y": float(y * kw / H)} for p, (x, y) in zip(r["data"], moved)]
            files[n].write(json.dumps({**r, "data": data}) + "\n")
        count += 1
    print("strokes", count, "layouts", names)


if __name__ == "__main__":
    main()
