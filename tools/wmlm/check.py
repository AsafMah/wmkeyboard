"""The PyTorch side of `lm_probe`: the same context, the same answers, in
f32, so a disagreement with the int8 reader shows.

usage: check.py <checkpoint.pt> <vocab.txt> "i want to" buy by bye
"""
import sys

import torch
import torch.nn.functional as F

from common import BOS, FIELDS, LANGS, REGISTERS, UNK, tokenize
from model import WordLm


def main():
    ckpt, vocab_path, context = sys.argv[1:4]
    cands = sys.argv[4:]
    state = torch.load(ckpt, map_location="cpu")
    cfg = state["config"]
    model = WordLm(cfg["vocab"], cfg["d"], cfg["layers"], cfg["heads"], cfg["ff"], cfg["ctx"])
    model.load_state_dict(state["model"])
    model.eval()
    vocab = [l.rstrip("\n") for l in open(vocab_path, encoding="utf-8")]
    ids = {w: i for i, w in enumerate(vocab)}
    toks = [ids[REGISTERS[0]], ids[FIELDS[0]], ids[LANGS[0]], ids[BOS]] + [ids.get(w, ids[UNK]) for w in tokenize(context)]
    with torch.no_grad():
        logits = model(torch.tensor([toks]))[0, -1]
        logits[0] = float("-inf")
        lp = F.log_softmax(logits, -1)
    top = torch.topk(lp[16:], 10)
    print(f"context {context!r} -> {len(toks)} tokens")
    for v, i in zip(top.values, top.indices):
        print(f"  {vocab[i + 16]:>14} {v.item():.3f} ({v.exp().item():.4f})")
    for c in cands:
        i = ids.get(c)
        print(f"  P({c}) = {None if i is None else (round(lp[i].item(), 4), round(lp[i].exp().item(), 4))}")


if __name__ == "__main__":
    main()
