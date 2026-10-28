"""Train GeoCtc on swipe.futo.org strokes with layout augmentation.

usage: train.py <train.npz> <test.npz> <qwerty.json> <out dir> [key=value ...]
  steps=60000 batch=384 lr=2e-3 real=0.4 d=96 ff=256 keyl=1 low=1 high=4 seed=0 rowshift=0

Each stroke is drawn on its recorded QWERTY with probability `real`, else
transplanted onto a random layout from `layouts.bank` (random letter order on
a random grid). Both get key jitter, a random aspect and rotation, an offset
between stroke and keys, and a fresh random order of key slots, so the model
can learn neither QWERTY's letters nor a slot's index. `rowshift` slides each
letter row sideways by up to that many key widths (the stroke follows), so
QWERTY's stagger is not the only one the real strokes show.
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from geo import LETTERS, futo_qwerty
from layouts import NAMED, bank
from model import GeoCtc

K = 32  # key slots; 26 letters, the rest padding


def opts(argv):
    o = dict(steps=60000, batch=384, lr=2e-3, real=0.4, d=96, ff=256, keyl=1, low=1, high=4, seed=0, bank=50000, eval_every=2000, rowshift=0.0)
    for a in argv:
        k, v = a.split("=")
        o[k] = type(o[k])(v) if k in o else v
    return o


class Data:
    def __init__(self, path, dev):
        z = np.load(path)
        self.n = len(z["dur"])
        self.frames = torch.from_numpy(z["frames"]).to(dev)
        self.lo = torch.from_numpy(z["lo"]).to(dev)
        self.hi = torch.from_numpy(z["hi"]).to(dev)
        self.s = torch.from_numpy(z["s"].astype(np.float32)).to(dev)
        self.size = torch.from_numpy(z["size"]).to(dev)
        self.dur = torch.from_numpy(z["dur"]).to(dev)
        self.label = torch.from_numpy(z["label"].astype(np.int64)).to(dev)
        self.label_len = torch.from_numpy(z["label_len"].astype(np.int64)).to(dev)
        self.words = z["words"]


def to_kw(shares, size):
    """[B, 26, 2] canvas shares to key widths for canvases [B, 2] (W, H)."""
    sy = size[:, 1] / (0.1 * size[:, 0])
    return torch.stack([shares[..., 0] * 10, shares[..., 1] * sy[:, None]], -1)


def batch(data, idx, qwerty, layouts, o, gen, train=True):
    """Stroke frames, durations, keys [B, K, 2], mask, targets (slots), lengths, slot->letter."""
    dev = data.frames.device
    B = len(idx)
    size = data.size[idx]
    Q = to_kw(qwerty.expand(B, 26, 2), size)
    if layouts is None:
        L = Q.clone()
    else:
        L = to_kw(layouts, size)
    if train and o.get("rowshift", 0) > 0:
        shares = qwerty.expand(B, 26, 2) if layouts is None else layouts
        row = torch.round(shares[..., 1] * 3 - 0.5).long().clamp(0, 2)
        shift = (torch.rand(B, 3, device=dev, generator=gen) * 2 - 1) * o["rowshift"]
        L = L + torch.stack([torch.gather(shift, 1, row), torch.zeros_like(L[..., 1])], -1)
    if train:
        L = L + torch.randn(B, 26, 2, device=dev, generator=gen) * 0.06
    delta = L - Q
    lo, hi, s = data.lo[idx].long(), data.hi[idx].long(), data.s[idx]
    dlo = torch.gather(delta, 1, lo[..., None].expand(-1, -1, 2))
    dhi = torch.gather(delta, 1, hi[..., None].expand(-1, -1, 2))
    path = data.frames[idx] + (1 - s)[..., None] * dlo + s[..., None] * dhi
    keys = L
    if train:
        keys = keys + torch.randn(B, 26, 2, device=dev, generator=gen) * 0.03
        path = path + torch.randn(B, 1, 2, device=dev, generator=gen) * 0.08
        # Aspect and a slight rotation, for stroke and keys together.
        sx = 1 + (torch.rand(B, device=dev, generator=gen) - 0.5) * 0.2
        sy = 0.8 + torch.rand(B, device=dev, generator=gen) * 0.45
        th = torch.randn(B, device=dev, generator=gen) * math.radians(1.5)
        c, sn = torch.cos(th), torch.sin(th)
        m = torch.stack([torch.stack([c * sx, -sn * sy], -1), torch.stack([sn * sx, c * sy], -1)], -2)  # [B, 2, 2]
        path = path @ m.transpose(1, 2)
        keys = keys @ m.transpose(1, 2)
    # Letters to random slots among K, the rest padding.
    if train:
        perm = torch.argsort(torch.rand(B, K, device=dev, generator=gen), 1)
    else:
        perm = torch.arange(K, device=dev).expand(B, K)
    letter_of = perm  # slot -> letter (>= 26 is padding)
    slot_of = torch.argsort(perm, 1)  # letter -> slot
    mask = letter_of < 26
    full = torch.cat([keys, torch.zeros(B, K - 26, 2, device=dev)], 1)
    keys_slots = torch.gather(full, 1, letter_of[..., None].expand(-1, -1, 2))
    lab = data.label[idx]
    tgt = torch.gather(slot_of, 1, lab.clamp_max(25))
    return path, data.dur[idx], keys_slots, mask, tgt, data.label_len[idx], letter_of


def build(o):
    return GeoCtc(d=o["d"], ff=o.get("ff", 256), key_layers=o.get("keyl", 2), low_layers=o.get("low", 2), high_layers=o["high"])


def greedy(lp, letter_of):
    best = lp.argmax(-1).cpu().numpy()
    letter_of = letter_of.cpu().numpy()
    out = []
    for b in range(best.shape[0]):
        w, prev = [], -1
        for c in best[b]:
            if c != prev and c < K:
                w.append(LETTERS[letter_of[b, c]] if letter_of[b, c] < 26 else "?")
            prev = c
        out.append("".join(w))
    return out


def evaluate(model, data, qwerty, layout, o):
    model.eval()
    hits, n = 0, min(3000, data.n)
    with torch.no_grad():
        for a in range(0, n, 500):
            idx = torch.arange(a, min(n, a + 500), device=data.frames.device)
            lay = None if layout is None else layout.expand(len(idx), 26, 2)
            path, dur, keys, mask, _, _, letter_of = batch(data, idx, qwerty, lay, o, None, train=False)
            words = greedy(model(path, dur, keys, mask), letter_of)
            hits += sum(w == data.words[i] for w, i in zip(words, idx.tolist()))
    model.train()
    return hits / n


def main():
    train_path, test_path, qwerty_path, out = sys.argv[1:5]
    o = opts(sys.argv[5:])
    os.makedirs(out, exist_ok=True)
    dev = torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    torch.manual_seed(o["seed"])
    gen = torch.Generator(device=dev).manual_seed(o["seed"])
    train, test = Data(train_path, dev), Data(test_path, dev)
    qwerty = torch.from_numpy(futo_qwerty(qwerty_path).astype(np.float32)).to(dev)[None]
    lay_bank = torch.from_numpy(bank(o["bank"], o["seed"])).to(dev)
    dvorak = torch.from_numpy(NAMED["dvorak"].astype(np.float32)).to(dev)[None]
    model = build(o).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "train", train.n, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.01)
    warm = 1000
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"])))
    )
    t0, run = time.time(), 0.0
    best = 0.0
    for step in range(1, o["steps"] + 1):
        idx = torch.randint(0, train.n, (o["batch"],), device=dev, generator=gen)
        real = torch.rand(o["batch"], device=dev, generator=gen) < o["real"]
        pick = torch.randint(0, lay_bank.shape[0], (o["batch"],), device=dev, generator=gen)
        lay = torch.where(real[:, None, None], qwerty.expand(o["batch"], 26, 2), lay_bank[pick])
        path, dur, keys, mask, tgt, tlen, _ = batch(train, idx, qwerty, lay, o, gen)
        lp = model(path, dur, keys, mask)
        T = lp.shape[1]
        loss = F.ctc_loss(
            lp.transpose(0, 1), tgt, torch.full((o["batch"],), T, dtype=torch.long), tlen, blank=K, zero_infinity=True
        )
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 200 == 0:
            print(f"step {step} loss {run:.3f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % o["eval_every"] == 0 or step == o["steps"]:
            q = evaluate(model, test, qwerty, None, o)
            d = evaluate(model, test, qwerty, dvorak, o)
            print(f"eval step {step} greedy qwerty {q:.4f} dvorak {d:.4f}", flush=True)
            torch.save({"model": model.state_dict(), "opts": o, "step": step}, f"{out}/last.pt")
            if q + d > best:
                best = q + d
                torch.save({"model": model.state_dict(), "opts": o, "step": step}, f"{out}/best.pt")


if __name__ == "__main__":
    main()
