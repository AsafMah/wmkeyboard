"""Train WordLm on prep.py's arrays.

usage: train.py <data dir> <out dir> [steps=20000 batch=256 lr=1e-3 d=192 layers=4 heads=4 ff=512
                                     seed=0 eval_every=1000 drop_prefix=0.3 ctx=32
                                     distill=soft.npz distill_alpha=0.5 distill_share=0.5
                                     resume=out/last.pt]

Sentences are packed one per row, padded to the longest in the batch; the
loss ignores padding. `drop_prefix` is the share of rows whose register and
field tokens are replaced by the neutral ones, so the model also serves a
caller that does not know them. Saves out/last.pt and out/best.pt (lowest
held-out loss) with the config inside.

`distill=` points at distill.py's soft targets: `distill_share` of the
batches are drawn from the sentences the teacher has read, and on those the
loss is `(1 - alpha) * cross-entropy + alpha * KL(teacher top-k || student)`
per word position; rows the teacher left empty keep the plain target.
`resume=` continues from a saved checkpoint's weights and step (the
optimiser's moments start afresh; the schedule picks up where it was).
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from common import FIELDS, REGISTERS, SPECIALS
from model import WordLm

PAD = SPECIALS.index("<pad>")
NEUTRAL_REG = SPECIALS.index(REGISTERS[0])
OTHER_FIELD = SPECIALS.index(FIELDS[0])


def opts(argv):
    o = dict(steps=20000, batch=256, lr=1e-3, d=192, layers=4, heads=4, ff=512, seed=0, eval_every=1000, drop_prefix=0.3, ctx=32,
             distill="", distill_alpha=0.5, distill_share=0.5, resume="")
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v)
    return o


class Soft:
    """distill.py's teacher targets, looked up by training-sentence index."""

    def __init__(self, path):
        z = np.load(path)
        self.sentences = z["sentences"]
        self.starts = z["starts"]
        self.ids = z["ids"].astype(np.int64)
        self.probs = z["probs"].astype(np.float32)
        self.at = {int(s): i for i, s in enumerate(self.sentences)}
        self.k = self.ids.shape[1]

    def batch(self, idx, T, dev):
        """Teacher ids/probs per logits position for the rows `idx`, zero
        where the teacher said nothing: `[B, T-1, k]` each."""
        ids = np.zeros((len(idx), T - 1, self.k), dtype=np.int64)
        probs = np.zeros((len(idx), T - 1, self.k), dtype=np.float32)
        for b, s in enumerate(idx):
            i = self.at.get(int(s))
            if i is None:
                continue
            a, e = self.starts[i], self.starts[i + 1]
            # Row r predicts word r, which sits at token 4 + r, so at logits
            # position 3 + r.
            n = min(e - a, T - 1 - 3)
            if n > 0:
                ids[b, 3 : 3 + n] = self.ids[a : a + n]
                probs[b, 3 : 3 + n] = self.probs[a : a + n]
        return torch.from_numpy(ids).to(dev), torch.from_numpy(probs).to(dev)


def distill_loss(logits, ids, probs):
    """-sum_k p_k log q(id_k), averaged over the positions the teacher filled.

    log q(id) = logit[id] - logsumexp(logits): the reduction keeps only a
    [B, T] tensor live, where a materialised log-softmax over the whole
    vocabulary ([B, T, V], a gigabyte at batch 256) sent MPS into swap."""
    filled = probs.sum(-1) > 0
    if not bool(filled.any()):
        return logits.new_zeros(())
    lse = torch.logsumexp(logits, dim=-1)
    picked = torch.gather(logits, -1, ids)
    per_pos = -(probs * (picked - lse.unsqueeze(-1))).sum(-1)
    return per_pos[filled].mean()


class Data:
    def __init__(self, path, dev):
        z = np.load(path)
        self.tokens = torch.from_numpy(z["tokens"].astype(np.int64))
        self.starts = z["starts"]
        self.n = len(self.starts) - 1
        self.dev = dev

    def batch(self, idx, ctx, gen=None, drop_prefix=0.0):
        rows = [self.tokens[self.starts[i]:self.starts[i + 1]] for i in idx]
        T = min(ctx, max(len(r) for r in rows))
        out = torch.full((len(rows), T), PAD, dtype=torch.long)
        for i, r in enumerate(rows):
            r = r[:T]
            out[i, : len(r)] = r
        if drop_prefix > 0:
            mask = torch.rand(len(rows), generator=gen) < drop_prefix
            out[mask, 0] = NEUTRAL_REG
            out[mask, 1] = OTHER_FIELD
        return out.to(self.dev)


def evaluate(model, data, ctx, dev):
    model.eval()
    total, count = 0.0, 0
    with torch.no_grad():
        for a in range(0, min(data.n, 8000), 256):
            idx = range(a, min(data.n, a + 256))
            x = data.batch(idx, ctx)
            logits = model(x[:, :-1])
            tgt = x[:, 1:]
            # The prefix tokens are given, not predicted: score from <s> on.
            tgt = tgt.clone()
            tgt[:, :3] = PAD
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD, reduction="sum")
            total += loss.item()
            count += (tgt != PAD).sum().item()
    model.train()
    return total / max(1, count)


def main():
    data_dir, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    dev = torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    print("device", dev, flush=True)
    torch.manual_seed(o["seed"])
    gen = torch.Generator().manual_seed(o["seed"])
    vocab = sum(1 for _ in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8"))
    train = Data(os.path.join(data_dir, "train.npz"), dev)
    held = Data(os.path.join(data_dir, "heldout.npz"), dev)
    soft = Soft(o["distill"]) if o["distill"] else None
    if soft:
        print("distilling from", o["distill"], "over", len(soft.sentences), "sentences", flush=True)
    model = WordLm(vocab, d=o["d"], layers=o["layers"], heads=o["heads"], ff=o["ff"], ctx=o["ctx"]).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "train sentences", train.n, "vocab", vocab, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.1, betas=(0.9, 0.95))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run, best, start = time.time(), 0.0, 1e9, 0
    if o["resume"]:
        saved = torch.load(o["resume"], map_location=dev)
        model.load_state_dict(saved["model"])
        start, best = saved["step"], saved.get("heldout", 1e9)
        for _ in range(start):
            sched.step()
        print("resumed from", o["resume"], "at step", start, flush=True)
    for step in range(start + 1, o["steps"] + 1):
        from_teacher = soft is not None and torch.rand((), generator=gen).item() < o["distill_share"]
        if from_teacher:
            idx = soft.sentences[torch.randint(0, len(soft.sentences), (o["batch"],), generator=gen).numpy()].tolist()
        else:
            idx = torch.randint(0, train.n, (o["batch"],), generator=gen).tolist()
        x = train.batch(idx, o["ctx"], gen, o["drop_prefix"])
        logits = model(x[:, :-1])
        tgt = x[:, 1:].clone()
        tgt[:, :3] = PAD
        loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD)
        if from_teacher:
            ids, probs = soft.batch(idx, x.shape[1], dev)
            loss = (1 - o["distill_alpha"]) * loss + o["distill_alpha"] * distill_loss(logits, ids, probs)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > start + 1 else loss.item()
        if step % 100 == 0:
            print(f"step {step} loss {run:.3f} ppl {math.exp(run):.1f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % o["eval_every"] == 0 or step == o["steps"]:
            h = evaluate(model, held, o["ctx"], dev)
            print(f"eval step {step} heldout loss {h:.4f} ppl {math.exp(h):.1f}", flush=True)
            state = {"model": model.state_dict(), "config": model.config(), "opts": o, "step": step, "heldout": h}
            torch.save(state, os.path.join(out, "last.pt"))
            if h < best:
                best = h
                torch.save(state, os.path.join(out, "best.pt"))


if __name__ == "__main__":
    main()
