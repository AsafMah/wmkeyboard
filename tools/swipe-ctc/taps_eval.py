"""The tap encoder against the engine's fuzzy walk, on the same cases.

usage: taps_eval.py <checkpoint.pt> <cases.tsv> <en.txt> [beam=16] [limit=0]

`cases.tsv` is `typing_eval`'s CASES_OUT: per mistyped word, the scatter
profile, the word meant, what the nearest keys spelled, the taps, and the
word the engine's strip put first. The encoder reads the same taps on the
same QWERTY, its emissions go through a dictionary-constrained CTC prefix
beam over en.txt (frequency as a prior, like `CtcGlideBeam`), and the table
compares top-1 per profile. One number, one claim: whether learning the
noise beats pricing it by hand, on the noise both were given.
"""
import math
import sys
from collections import defaultdict

import numpy as np
import torch

import tapsim
from tapmodel import TapCtc

FREQ_WEIGHT = 0.3


class Trie:
    __slots__ = ("children", "word", "freq")

    def __init__(self):
        self.children = {}
        self.word = None
        self.freq = 0.0


def build_trie(path):
    root = Trie()
    words, probs = tapsim.load_words(path, min_len=1)
    for w, p in zip(words, probs):
        node = root
        for c in w:
            node = node.children.setdefault(c, Trie())
        node.word = w
        node.freq = math.log(p + 1e-12)
    return root


def decode(lp, trie, beam):
    """CTC prefix beam over the trie: returns (word, score) best first."""
    T, C = lp.shape
    blank = C - 1
    # Beam entries: prefix string -> (p_blank, p_nonblank) in log space, node.
    beams = {"": (0.0, -math.inf, trie)}

    def lse(a, b):
        if a == -math.inf:
            return b
        if b == -math.inf:
            return a
        m = max(a, b)
        return m + math.log(math.exp(a - m) + math.exp(b - m))

    for t in range(T):
        step = lp[t]
        nxt = {}

        def add(prefix, pb, pnb, node):
            if prefix in nxt:
                ob, onb, _ = nxt[prefix]
                nxt[prefix] = (lse(ob, pb), lse(onb, pnb), node)
            else:
                nxt[prefix] = (pb, pnb, node)

        for prefix, (pb, pnb, node) in beams.items():
            total = lse(pb, pnb)
            add(prefix, total + step[blank], -math.inf, node)
            last = prefix[-1] if prefix else None
            for c, child in node.children.items():
                k = tapsim.LETTERS.index(c)
                if k >= C - 1:
                    continue
                pc = step[k]
                if c == last:
                    # Repeating the same letter needs a blank between.
                    add(prefix + c, -math.inf, pb + pc, child)
                    add(prefix, -math.inf, pnb + pc, node)
                else:
                    add(prefix + c, -math.inf, total + pc, child)
        beams = dict(sorted(nxt.items(), key=lambda kv: -lse(kv[1][0], kv[1][1]))[:beam])
    out = []
    for prefix, (pb, pnb, node) in beams.items():
        if node.word is not None:
            out.append((node.word, lse(pb, pnb) + FREQ_WEIGHT * node.freq))
    out.sort(key=lambda x: -x[1])
    return out


def main():
    ckpt, cases_path, words_path = sys.argv[1:4]
    opts = dict(a.split("=") for a in sys.argv[4:])
    beam = int(opts.get("beam", 16))
    limit = int(opts.get("limit", 0))
    state = torch.load(ckpt, map_location="cpu")
    o = state["opts"]
    model = TapCtc(d=o["d"], layers=o["layers"])
    model.load_state_dict(state["model"])
    model.eval()
    trie = build_trie(words_path)
    keys = np.zeros((1, tapsim.K, 2), dtype=np.float32)
    keys[0, :26] = tapsim.QWERTY
    kmask = np.zeros((1, tapsim.K), dtype=bool)
    kmask[0, :26] = True
    hits = defaultdict(lambda: [0, 0, 0])  # encoder, engine, n
    n = 0
    with open(cases_path, encoding="utf-8") as f, torch.no_grad():
        for line in f:
            profile, word, typed, taps_s, engine_top = line.rstrip("\n").split("\t")
            pts = [p.split(",") for p in taps_s.split(";") if p]
            if not pts or len(pts) > tapsim.MAX_TAPS:
                continue
            taps = np.zeros((1, len(pts), 4), dtype=np.float32)
            for i, (x, y) in enumerate(pts):
                taps[0, i, 0] = float(x)
                taps[0, i, 1] = float(y)
                taps[0, i, 2] = math.log1p(0.25) if i > 0 else 0.0
                taps[0, i, 3] = 0.09
            mask = np.ones((1, len(pts)), dtype=bool)
            lp = model(torch.from_numpy(taps), torch.from_numpy(mask), torch.from_numpy(keys), torch.from_numpy(kmask))[0].numpy()
            # Slots 0..25 are a..z here, so the class index is the letter.
            decoded = decode(lp, trie, beam)
            top = decoded[0][0] if decoded else ""
            h = hits[profile]
            h[0] += top == word
            h[1] += engine_top == word
            h[2] += 1
            n += 1
            if limit and n >= limit:
                break
    for profile, (enc, eng, total) in sorted(hits.items()):
        print(f"{profile:>8}: encoder top1 {enc / total:.4f}  engine top1 {eng / total:.4f}  ({total} cases)")


if __name__ == "__main__":
    main()
