"""The tap simulator the tap encoder trains on, and the eval's geometry.

No public corpus holds real phone taps with their coordinates (the Aalto
37k study logged timing only; the Gboard heatmap study was not released), so
the encoder learns from a finger drawn here: words from the keyboard's own
frequency list, each key hit with Gaussian scatter whose width is drawn per
word from careful to sloppy, a letter dropped, a stray neighbour pressed, or
two taps swapped at the rates the literature gives for phone typing, and
inter-tap gaps drawn log-normally around the Aalto medians. The grid is the
eval harness's QWERTY (key widths, rows a key apart, staggered a quarter and
three quarters), and training also draws random letter orders on it so the
weights never learn QWERTY's letters. Everything the model sees is a claim
about typing, so its numbers are an upper bound until taps from a real hand
are measured; the Rust harness's cases (typing_eval CASES_OUT) are the same
simulator in Rust, which is what taps_eval.py compares against.
"""
import math

import numpy as np

LETTERS = "abcdefghijklmnopqrstuvwxyz"
ROWS = [("qwertyuiop", 0.0), ("asdfghjkl", 0.25), ("zxcvbnm", 0.75)]
K = 32
MAX_TAPS = 24


def qwerty():
    out = np.zeros((26, 2), dtype=np.float32)
    for row, (letters, offset) in enumerate(ROWS):
        for col, ch in enumerate(letters):
            out[LETTERS.index(ch)] = (offset + col, row)
    return out


QWERTY = qwerty()
SCATTERS = [(0.25, 0.3), (0.35, 0.45), (0.45, 0.55)]


def load_words(path, min_len=3, max_len=MAX_TAPS - 2):
    words, counts = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            w = parts[0]
            if min_len <= len(w) <= max_len and all("a" <= c <= "z" for c in w):
                words.append(w)
                counts.append(int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1)
    p = np.array(counts, dtype=np.float64)
    return words, p / p.sum()


def random_layout(rng):
    """QWERTY's grid with its letters shuffled, nudged a little."""
    layout = QWERTY[rng.permutation(26)].copy()
    layout += rng.normal(0, 0.05, layout.shape)
    return layout.astype(np.float32)


def simulate(word, layout, rng, sx, sy, p_drop=0.04, p_insert=0.04, p_swap=0.04):
    """Taps (x, y, log gap, hold) for `word` on `layout`, with one slip at most."""
    letters = list(word)
    roll = rng.random()
    if roll < p_drop and len(letters) > 3:
        del letters[rng.integers(len(letters))]
    elif roll < p_drop + p_insert:
        i = rng.integers(len(letters))
        letters.insert(i, LETTERS[rng.integers(26)])
    elif roll < p_drop + p_insert + p_swap and len(letters) > 3:
        i = rng.integers(len(letters) - 1)
        letters[i], letters[i + 1] = letters[i + 1], letters[i]
    taps = np.zeros((len(letters), 4), dtype=np.float32)
    for i, ch in enumerate(letters):
        cx, cy = layout[LETTERS.index(ch)]
        taps[i, 0] = cx + rng.normal(0, sx)
        taps[i, 1] = cy + rng.normal(0, sy)
        gap = math.exp(rng.normal(math.log(0.25), 0.4)) if i > 0 else 0.0
        taps[i, 2] = math.log1p(gap)
        taps[i, 3] = math.exp(rng.normal(math.log(0.09), 0.3))
    return taps


def batch(words, probs, n, rng, real=0.4):
    """A training batch: taps, mask, keys (slots), kmask, targets, lengths."""
    taps = np.zeros((n, MAX_TAPS, 4), dtype=np.float32)
    mask = np.zeros((n, MAX_TAPS), dtype=bool)
    keys = np.zeros((n, K, 2), dtype=np.float32)
    kmask = np.zeros((n, K), dtype=bool)
    targets = np.zeros((n, MAX_TAPS), dtype=np.int64)
    lengths = np.zeros(n, dtype=np.int64)
    idx = rng.choice(len(words), size=n, p=probs)
    for b in range(n):
        word = words[idx[b]]
        layout = QWERTY if rng.random() < real else random_layout(rng)
        s = rng.integers(len(SCATTERS))
        sx, sy = SCATTERS[s]
        sx *= rng.uniform(0.7, 1.3)
        sy *= rng.uniform(0.7, 1.3)
        t = simulate(word, layout, rng, sx, sy)
        t = t[:MAX_TAPS]
        taps[b, : len(t)] = t
        mask[b, : len(t)] = True
        perm = rng.permutation(K)  # slot -> letter index (>= 26 is padding)
        slot_of = np.argsort(perm)
        for slot in range(K):
            if perm[slot] < 26:
                keys[b, slot] = layout[perm[slot]]
                kmask[b, slot] = True
        label = [slot_of[LETTERS.index(c)] for c in word]
        targets[b, : len(label)] = label
        lengths[b] = len(label)
    return taps, mask, keys, kmask, targets, lengths
