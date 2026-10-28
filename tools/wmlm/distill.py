"""Soft next-word targets from a large teacher LM, for train.py's `distill=`.

usage: distill.py <data dir> <out.npz> [teacher=Qwen/Qwen2.5-0.5B] [sentences=100000]
                  [topk=24] [batch=16] [device=auto] [seed=0]

The student (`model.WordLm`) learns from one-hot next words; a teacher a
thousand times its size knows the whole distribution over what could come
next, and that distribution is the better target: "I want to" is followed
by buy, go, see, know... with weights the one-hot target never shows. This
script runs the teacher over a sample of prep.py's training sentences and
writes, for every word position, the top-k of the teacher's next-word
distribution mapped onto the student's vocabulary.

Mapping: the teacher's subword vocabulary is cased and space-prefixed; a
student word maps to the teacher tokens that spell it whole as one token,
in lower case and capitalised (" the", " The"), and their probabilities
add. Words the teacher spells in several tokens get no teacher mass here
(the first token's mass is shared with every other word that starts the
same way, so it cannot be theirs); their one-hot target still stands in
train.py. Subword pieces (common.Pieces) are never targets.

Output (`out.npz`): `sentences` int32, the indices into train.npz of the
sentences covered; `starts` int64, each sentence's first row below;
`ids` int16 [rows, topk] student token ids; `probs` float16 [rows, topk],
renormalised over the top-k. Row i of a sentence is the teacher's view of
the word after its first i words (the student's target at position 3 + i).

Needs `transformers` beside torch: `uv venv --python 3.12 .venv-distill &&
uv pip install --python .venv-distill/bin/python torch transformers`.
"""
import os
import sys
import time

import numpy as np
import torch

from common import CONT, SPECIALS, START

PREFIX_LEN = 4  # reg field lang <s>


def opts(argv):
    o = dict(teacher="Qwen/Qwen2.5-0.5B", sentences=100000, topk=24, batch=16, device="auto", seed=0)
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v)
    return o


def student_to_teacher(vocab, tok):
    """Pairs (student id, teacher id) for every student word the teacher
    holds whole, in either case."""
    s_ids, t_ids = [], []
    for sid, w in enumerate(vocab):
        if sid < len(SPECIALS) or w.startswith(START) or w.startswith(CONT):
            continue
        for form in {w, w.capitalize()}:
            enc = tok.encode(" " + form, add_special_tokens=False)
            if len(enc) == 1:
                s_ids.append(sid)
                t_ids.append(enc[0])
    return torch.tensor(s_ids), torch.tensor(t_ids)


def main():
    data_dir, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    from transformers import AutoModelForCausalLM, AutoTokenizer

    dev = o["device"]
    if dev == "auto":
        dev = "cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu"
    dev = torch.device(dev)
    vocab = [line.rstrip("\n") for line in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8")]
    z = np.load(os.path.join(data_dir, "train.npz"))
    tokens, starts = z["tokens"], z["starts"]
    n = len(starts) - 1
    rng = np.random.default_rng(o["seed"])
    chosen = np.sort(rng.choice(n, size=min(o["sentences"], n), replace=False))

    tok = AutoTokenizer.from_pretrained(o["teacher"])
    model = AutoModelForCausalLM.from_pretrained(o["teacher"], dtype=torch.float16 if dev.type != "cpu" else torch.float32).to(dev).eval()
    s_ids, t_ids = student_to_teacher(vocab, tok)
    s_ids, t_ids = s_ids.to(dev), t_ids.to(dev)
    print(f"teacher {o['teacher']}: {len(set(s_ids.tolist()))} of {len(vocab)} student words map whole", file=sys.stderr, flush=True)

    out_sent, out_starts, out_ids, out_probs = [], [0], [], []
    t0 = time.time()
    for b in range(0, len(chosen), o["batch"]):
        batch = chosen[b : b + o["batch"]]
        texts, word_ends = [], []
        for s in batch:
            row = tokens[starts[s] : starts[s + 1]]
            words = [vocab[t] for t in row[PREFIX_LEN:-1]]  # drop the prefix and </s>
            # Pieces are joined back into their word for the teacher's eyes.
            text, ends = "", []
            for w in words:
                if w.startswith(CONT):
                    text += w[len(CONT):]
                    ends[-1] = len(text)
                else:
                    text += " " + (w[len(START):] if w.startswith(START) else w)
                    ends.append(len(text))
            texts.append(text)
            word_ends.append(ends)
        enc = tok(texts, return_tensors="pt", padding=True, return_offsets_mapping=True, add_special_tokens=False)
        offsets = enc.pop("offset_mapping")
        with torch.no_grad():
            logits = model(**{k: v.to(dev) for k, v in enc.items()}).logits.float()
        probs = torch.softmax(logits, dim=-1)
        for i, s in enumerate(batch):
            ends = word_ends[i]
            # The teacher token that ends each word: its logits predict the next word.
            tok_end = offsets[i, :, 1].tolist()
            positions = []
            for e in ends:
                j = max(idx for idx, te in enumerate(tok_end) if te == e) if e in tok_end else None
                positions.append(j)
            # Row k predicts word k+1 from the end of word k (k >= 1); the
            # first word has no teacher context here and gets a uniform-free
            # row of zeros (train.py skips zero rows).
            rows_ids = np.zeros((len(ends), o["topk"]), dtype=np.int16)
            rows_p = np.zeros((len(ends), o["topk"]), dtype=np.float16)
            for k in range(1, len(ends)):
                j = positions[k - 1]
                if j is None:
                    continue
                p = probs[i, j]
                mass = torch.zeros(len(vocab), device=dev).index_add_(0, s_ids, p[t_ids])
                top_p, top_i = torch.topk(mass, o["topk"])
                total = top_p.sum()
                if total <= 0:
                    continue
                rows_ids[k] = top_i.cpu().numpy()
                rows_p[k] = (top_p / total).cpu().numpy()
            out_sent.append(s)
            out_ids.append(rows_ids)
            out_probs.append(rows_p)
            out_starts.append(out_starts[-1] + len(ends))
        if (b // o["batch"]) % 50 == 0:
            done = b + len(batch)
            rate = done / max(1e-9, time.time() - t0)
            print(f"{done}/{len(chosen)} sentences, {rate:.1f}/s, {(len(chosen) - done) / max(rate, 1e-9) / 60:.0f} min left", file=sys.stderr, flush=True)
    np.savez(
        out,
        sentences=np.array(out_sent, dtype=np.int32),
        starts=np.array(out_starts, dtype=np.int64),
        ids=np.concatenate(out_ids) if out_ids else np.zeros((0, o["topk"]), np.int16),
        probs=np.concatenate(out_probs) if out_probs else np.zeros((0, o["topk"]), np.float16),
    )
    print(f"wrote {out}: {len(out_sent)} sentences, {out_starts[-1]} rows", file=sys.stderr)


if __name__ == "__main__":
    main()
