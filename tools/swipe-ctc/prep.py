"""swipe.futo.org jsonl to the training arrays.

usage: prep.py <jsonl> <qwerty.json> <out.npz> [max]

Per stroke: FRAMES time-uniform frames in key widths, each frame's anchor span
(lo, hi letters) and share s for transplanting the stroke onto another layout,
the word as letter indices, the canvas size and the duration.
"""
import json
import sys
from multiprocessing import Pool

import numpy as np

from geo import FRAMES, LETTERS, MAX_LABEL, centres, futo_qwerty, record, resample, segments, squeeze

QWERTY = None


def init(path):
    global QWERTY
    QWERTY = futo_qwerty(path)


def one(line):
    got = record(json.loads(line), QWERTY)
    if got is None:
        return None
    word, W, H, xy, ts = got
    keys = centres(QWERTY, W, H)
    letters = np.array([LETTERS.index(c) for c in squeeze(word)])
    lo, hi, s = segments(xy, letters, keys)
    frames, flo, fhi, fs = resample(xy, ts, lo, hi, s)
    return word, W, H, float(ts[-1]), frames.astype(np.float32), flo.astype(np.uint8), fhi.astype(np.uint8), fs.astype(np.float16)


def lines(path, limit):
    with open(path) as f:
        for i, ln in enumerate(f):
            if i >= limit:
                return
            yield ln


def main():
    src, layout, out = sys.argv[1:4]
    limit = int(sys.argv[4]) if len(sys.argv) > 4 else 10**12
    rows = []
    with Pool(7, initializer=init, initargs=(layout,)) as pool:
        for got in pool.imap(one, lines(src, limit), chunksize=256):
            if got is not None:
                rows.append(got)
                if len(rows) % 100000 == 0:
                    print(len(rows), flush=True)
    n = len(rows)
    label = np.full((n, MAX_LABEL), 255, np.uint8)
    for i, r in enumerate(rows):
        label[i, : len(r[0])] = [LETTERS.index(c) for c in r[0]]
    np.savez(
        out,
        words=np.array([r[0] for r in rows]),
        size=np.array([[r[1], r[2]] for r in rows], np.float32),
        dur=np.array([r[3] for r in rows], np.float32),
        frames=np.stack([r[4] for r in rows]),
        lo=np.stack([r[5] for r in rows]),
        hi=np.stack([r[6] for r in rows]),
        s=np.stack([r[7] for r in rows]),
        label=label,
        label_len=np.array([len(r[0]) for r in rows], np.uint8),
    )
    print("strokes", n, "frames", FRAMES)


if __name__ == "__main__":
    main()
