"""GeoCtc emissions for swipe.futo.org strokes, in wm-engine's FSW1 records.

usage: emit.py <checkpoint.pt> <jsonl> <layout.json> <out.bin> [max]

The keys the model is given are the layout file's 26 letters in a..z order,
so its classes come out as `glide::ctc` reads them: a..z, then blank. The
format is native/bench/futo-swipe/emit.py's, so `ctc_glide_eval` reads both.
"""
import json
import struct
import sys

import numpy as np
import torch

from geo import LETTERS, centres, record, resample
from train import build


def main():
    ckpt, src, layout, out = sys.argv[1:5]
    limit = int(sys.argv[5]) if len(sys.argv) > 5 else 10**9
    state = torch.load(ckpt, map_location="cpu")
    model = build(state["opts"])
    model.load_state_dict(state["model"])
    model.eval()
    keys = {k["letter"]: k for k in json.load(open(layout))["keys"]}
    lay = np.array([[keys[c]["cx"], keys[c]["cy"]] for c in LETTERS], np.float64)
    recs, pending, sessions = [], [], {}

    def flush(fo):
        if not pending:
            return
        path = torch.tensor(np.stack([p[4] for p in pending]), dtype=torch.float32)
        dur = torch.tensor([p[5] for p in pending], dtype=torch.float32)
        k = torch.tensor(np.stack([p[6] for p in pending]), dtype=torch.float32)
        mask = torch.ones(len(pending), 26, dtype=torch.bool)
        with torch.no_grad():
            lp = model(path, dur, k, mask).numpy().astype("<f4")
        for (word, sid, W, H, _, _, _, raw), row in zip(pending, lp):
            wb = word.encode()
            fo.write(struct.pack("<H", len(wb)))
            fo.write(wb)
            fo.write(struct.pack("<Iff", sid, W, H))
            fo.write(struct.pack("<I", len(raw)))
            for x, y, t in raw:
                fo.write(struct.pack("<ffi", x, y, int(t)))
            fo.write(struct.pack("<H", row.shape[0]))
            fo.write(row.tobytes())
        recs.append(len(pending))
        pending.clear()

    with open(src) as fin, open(out, "wb") as fo:
        fo.write(b"FSW1")
        fo.write(struct.pack("<I", 0))
        for ln in fin:
            if sum(recs) + len(pending) >= limit:
                break
            r = json.loads(ln)
            got = record(r, None)
            if got is None:
                continue
            word, W, H, xy, ts = got
            kw = W * 0.1
            sid = sessions.setdefault(r.get("session", ""), len(sessions))
            raw = [(x * kw, y * kw, t) for (x, y), t in zip(xy, ts)]
            pending.append((r["word"], sid, W, H, resample(xy, ts), float(ts[-1]), centres(lay, W, H), raw))
            if len(pending) == 1024:
                flush(fo)
        flush(fo)
        fo.seek(4)
        fo.write(struct.pack("<I", sum(recs)))
    print("records", sum(recs))


if __name__ == "__main__":
    main()
