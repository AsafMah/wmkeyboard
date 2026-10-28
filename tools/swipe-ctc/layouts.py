"""Letter layouts as (26, 2) canvas shares (cx of the width, cy of the
height) in a..z order, on swipe.futo.org's canvas: ten key widths across,
three letter rows.

NAMED are the layouts the eval transplants strokes onto. Training never sees
them: it draws random letter orders on random grids (`bank`), so every named
layout but QWERTY is unseen.
"""
import numpy as np

from geo import LETTERS

ROW_Y = (1 / 6, 1 / 2, 5 / 6)


def grid(rows, leads, step=0.1):
    """rows: strings with '.' for a non-letter key; leads: first centre share."""
    out = np.full((26, 2), np.nan)
    for r, (row, lead) in enumerate(zip(rows, leads)):
        for i, c in enumerate(row):
            if c != ".":
                out[LETTERS.index(c)] = (lead + step * i, ROW_Y[r])
    assert not np.isnan(out).any(), rows
    return out


NAMED = {
    "qwerty": grid(["qwertyuiop", "asdfghjkl", "zxcvbnm"], [0.05, 0.10, 0.20]),
    "qwertz": grid(["qwertzuiop", "asdfghjkl", "yxcvbnm"], [0.05, 0.10, 0.20]),
    "azerty": grid(["azertyuiop", "qsdfghjklm", "wxcvbn"], [0.05, 0.05, 0.20]),
    "colemak": grid(["qwfpgjluy", "arstdhneio", "zxcvbkm"], [0.05, 0.05, 0.20]),
    # Mobile Dvorak: ' , . on the top row's left, nine letters between the
    # shift and backspace keys of the bottom row.
    "dvorak": grid(["...pyfgcrl", "aoeuidhtns", "qjkxbmwvz"], [0.05, 0.05, 0.10]),
    "abc": grid(["abcdefghij", "klmnopqrs", "tuvwxyz"], [0.05, 0.10, 0.20]),
}

# (keys per row, centre of each row's first key, key pitch) for the grids
# training draws letters onto; rows with more keys than letters keep holes.
GRIDS = [
    ((10, 9, 7), (0.05, 0.10, 0.20), 0.1),
    ((10, 10, 6), (0.05, 0.05, 0.20), 0.1),
    ((10, 10, 9), (0.05, 0.05, 0.10), 0.1),
    ((10, 10, 7), (0.05, 0.05, 0.15), 0.1),
    ((11, 11, 9), (1 / 22, 1 / 22, 3 / 22), 1 / 11),
    ((11, 11, 7), (1 / 22, 1 / 22, 4 / 22), 1 / 11),
    ((12, 12, 10), (1 / 24, 1 / 24, 3 / 24), 1 / 12),
]


def bank(n, seed=0):
    """n random layouts: a grid, then the 26 letters shuffled onto 26 of its keys."""
    rng = np.random.default_rng(seed)
    out = np.empty((n, 26, 2), np.float32)
    for j in range(n):
        counts, leads, step = GRIDS[rng.integers(len(GRIDS))]
        if rng.random() < 0.3:
            # A freer grid: any row lengths that hold the letters, any stagger.
            counts = tuple(int(c) for c in rng.integers(7, 12, 3))
            while sum(counts) < 26:
                grow = rng.integers(3)
                counts = tuple(c + (i == grow) for i, c in enumerate(counts))
            step = 1.0 / max(counts)
            leads = tuple(step / 2 + rng.uniform(0, (max(counts) - c) * step) for c in counts)
        pos = [(leads[r] + step * i, ROW_Y[r]) for r in range(3) for i in range(counts[r])]
        pick = rng.choice(len(pos), 26, replace=False)
        out[j] = np.array(pos)[pick][rng.permutation(26)]
    return out
