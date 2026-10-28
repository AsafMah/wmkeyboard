"""Train TapCtc on simulated taps (tapsim.py).

usage: train_taps.py <en.txt> <out dir> [steps=12000 batch=256 lr=1e-3 d=96 layers=4 seed=0]

Prints the greedy exact-word rate on fresh simulated batches (QWERTY and a
random layout) every thousand steps and saves out/last.pt and out/best.pt.
`taps_eval.py` is the real measurement, against the Rust engine's cases.
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

import tapsim
from tapmodel import TapCtc


def opts(argv):
    o = dict(steps=12000, batch=256, lr=1e-3, d=96, layers=4, seed=0, real=0.4)
    for a in argv:
        k, v = a.split("=")
        o[k] = type(o[k])(v)
    return o


def to_dev(arrays, dev):
    return [torch.from_numpy(a).to(dev) for a in arrays]


def greedy(lp, frames):
    """Greedy CTC words as slot sequences, over each row's own frames."""
    best = lp.argmax(-1).cpu().numpy()
    out = []
    for b in range(best.shape[0]):
        seq, prev = [], -1
        for c in best[b, : int(frames[b])]:
            if c != prev and c < tapsim.K:
                seq.append(int(c))
            prev = c
        out.append(seq)
    return out


def evaluate(model, words, probs, rng, dev, real):
    model.eval()
    hits, n = 0, 0
    with torch.no_grad():
        for _ in range(4):
            taps, mask, keys, kmask, targets, lengths = tapsim.batch(words, probs, 256, rng, real=real)
            lp = model(*to_dev([taps, mask, keys, kmask], dev))
            for seq, tgt, ln in zip(greedy(lp, mask.sum(1) * 2), targets, lengths):
                hits += seq == list(tgt[:ln])
                n += 1
    model.train()
    return hits / n


def main():
    words_path, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    dev = torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    torch.manual_seed(o["seed"])
    rng = np.random.default_rng(o["seed"])
    words, probs = tapsim.load_words(words_path)
    model = TapCtc(d=o["d"], layers=o["layers"]).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "words", len(words), flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.01)
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run, best = time.time(), 0.0, 0.0
    for step in range(1, o["steps"] + 1):
        taps, mask, keys, kmask, targets, lengths = tapsim.batch(words, probs, o["batch"], rng, real=o["real"])
        taps_t, mask_t, keys_t, kmask_t = to_dev([taps, mask, keys, kmask], dev)
        lp = model(taps_t, mask_t, keys_t, kmask_t)
        T = lp.shape[1]
        in_len = torch.from_numpy(mask.sum(1) * 2).to(dev)
        loss = F.ctc_loss(lp.transpose(0, 1).float(), torch.from_numpy(targets).to(dev), in_len, torch.from_numpy(lengths).to(dev), blank=tapsim.K, zero_infinity=True)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 100 == 0:
            print(f"step {step} loss {run:.3f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % 1000 == 0 or step == o["steps"]:
            q = evaluate(model, words, probs, rng, dev, real=1.0)
            r = evaluate(model, words, probs, rng, dev, real=0.0)
            print(f"eval step {step} greedy exact qwerty {q:.4f} random layout {r:.4f}", flush=True)
            state = {"model": model.state_dict(), "opts": o, "step": step}
            torch.save(state, f"{out}/last.pt")
            if q + r > best:
                best = q + r
                torch.save(state, f"{out}/best.pt")


if __name__ == "__main__":
    main()
