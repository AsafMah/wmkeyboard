"""Fit the learned autocorrect gate on the typing harness's rows.

usage: train_confidence.py <rows.csv> [<rows.csv> ...] [--mode=lm] [--hidden=16] [--l2=3e-3]
                           [--steps=3000] [--rust=path/confidence.rs] [--kotlin=path/Confidence.kt]
                           [--test=path/ConfidenceTest.kt]

Rows come from `cargo run --release --example typing_eval -- ... DUMP=rows.csv`
(mode, scatter, kind, label, f0..f26). The model is a one-hidden-layer
network (tanh) on standardised features, trained by Adam in numpy; the new
words typed right (kind `new`) are weighted up, since a false rewrite of
one is the failure the gate exists to stop. Rows are split by sentence
order into fit and check halves; the check half is what the table reports.
With `--rust` / `--kotlin` the fitted constants are written into the two
source files, between their `// FIT BEGIN` and `// FIT END` markers.
"""
import csv
import math
import re
import sys

import numpy as np

F = 27


def load(paths, mode):
    X, y, kinds, scatters = [], [], [], []
    for p in paths:
        with open(p, newline="") as f:
            for row in csv.DictReader(f):
                if mode and row["mode"] != mode:
                    continue
                X.append([float(row[f"f{i}"]) for i in range(F)])
                y.append(int(row["label"]))
                kinds.append(row["kind"])
                scatters.append(row["scatter"])
    return np.array(X, dtype=np.float64), np.array(y, dtype=np.float64), np.array(kinds), np.array(scatters)


def fit(X, y, wt, hidden, l2, steps, seed=0):
    rng = np.random.default_rng(seed)
    mean = X.mean(axis=0)
    scale = X.std(axis=0)
    scale[scale == 0] = 1.0
    Z = (X - mean) / scale
    n, d = Z.shape
    W1 = rng.normal(0, 1 / np.sqrt(d), (d, hidden))
    b1 = np.zeros(hidden)
    W2 = rng.normal(0, 1 / np.sqrt(hidden), hidden)
    b2 = 0.0
    params = [W1, b1, W2, np.array([b2])]
    m = [np.zeros_like(p) for p in params]
    v = [np.zeros_like(p) for p in params]
    lr, beta1, beta2, eps = 0.01, 0.9, 0.999, 1e-8
    for t in range(1, steps + 1):
        H = np.tanh(Z @ params[0] + params[1])
        logit = H @ params[2] + params[3][0]
        p = 1 / (1 + np.exp(-logit))
        g = (p - y) * wt / n
        gW2 = H.T @ g + l2 * params[2]
        gb2 = np.array([g.sum()])
        gH = np.outer(g, params[2]) * (1 - H * H)
        gW1 = Z.T @ gH + l2 * params[0]
        gb1 = gH.sum(axis=0)
        for i, grad in enumerate([gW1, gb1, gW2, gb2]):
            m[i] = beta1 * m[i] + (1 - beta1) * grad
            v[i] = beta2 * v[i] + (1 - beta2) * grad * grad
            params[i] -= lr * (m[i] / (1 - beta1**t)) / (np.sqrt(v[i] / (1 - beta2**t)) + eps)
    return mean, scale, params[0], params[1], params[2], params[3][0]


def predict(X, mean, scale, W1, b1, W2, b2):
    Z = (X - mean) / scale
    return 1 / (1 + np.exp(-(np.tanh(Z @ W1 + b1) @ W2 + b2)))


def table(p, y, kinds, scatters, out):
    for sc in sorted(set(scatters)):
        m = scatters == sc
        typo = m & (kinds == "typo")
        new = m & (kinds == "new")
        print(f"scatter {sc}: {typo.sum()} typo rows (best right {y[typo].mean():.3f}), {new.sum()} new-word rows", file=out)
        for thr in (0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95):
            fire = p >= thr
            prec = y[typo & fire].sum() / max(1, (typo & fire).sum())
            rec = y[typo & fire].sum() / max(1, typo.sum())
            new_fire = (new & fire).sum() / max(1, new.sum())
            print(f"  thr {thr:.2f}: precision {prec:.4f} recall {rec:.4f} new words rewritten {new_fire:.4f}", file=out)


def rust_consts(mean, scale, W1, b1, W2, b2, hidden, note):
    def arr(v):
        return "[" + ", ".join(f"{x:.6}" for x in v) + "]"
    rows = ",\n    ".join(arr(r) for r in W1.T)  # HIDDEN rows of FEATURES
    return (
        f"// {note}\n"
        f"pub const HIDDEN: usize = {hidden};\n"
        f"pub const MEAN: [f32; FEATURES] = {arr(mean)};\n"
        f"pub const SCALE: [f32; FEATURES] = {arr(scale)};\n"
        f"pub const W1: [[f32; FEATURES]; HIDDEN] = [\n    {rows},\n];\n"
        f"pub const B1: [f32; HIDDEN] = {arr(b1)};\n"
        f"pub const W2: [f32; HIDDEN] = {arr(W2)};\n"
        f"pub const B2: f32 = {b2:.6};\n"
    )


def kotlin_consts(mean, scale, W1, b1, W2, b2, hidden, note):
    def arr(v):
        return "floatArrayOf(" + ", ".join(f"{x:.6}f" for x in v) + ")"
    rows = ",\n        ".join(arr(r) for r in W1.T)
    return (
        f"    // {note}\n"
        f"    const val HIDDEN = {hidden}\n"
        f"    val MEAN = {arr(mean)}\n"
        f"    val SCALE = {arr(scale)}\n"
        f"    val W1 = arrayOf(\n        {rows},\n    )\n"
        f"    val B1 = {arr(b1)}\n"
        f"    val W2 = {arr(W2)}\n"
        f"    const val B2 = {b2:.6}f\n"
    )


def splice(path, body):
    s = open(path, encoding="utf-8").read()
    out = re.sub(r"(// FIT BEGIN\n).*?(\s*// FIT END)", lambda m: m.group(1) + body + m.group(2), s, flags=re.S)
    assert out != s or body in s, f"no FIT markers in {path}"
    open(path, "w", encoding="utf-8").write(out)


def fixed_probability(mean, scale, W1, b1, W2, b2):
    """The case confidence.rs and ConfidenceTest.kt both score."""
    f = np.array([[2.3, 7.9, 0.105, 1, 1, 0, 0, 1, 0, 0, 0, 6, 1, 1.6, 4, -2.0, 0.0, 1, 0.0, 0,
                   0.0, 0, 0.0, 4.2, 1.5, -28.0, 1]], dtype=np.float64)
    return float(predict(f, mean, scale, W1, b1, W2, b2)[0])


def splice_number(path, pattern, value):
    s = open(path, encoding="utf-8").read()
    out, n = re.subn(pattern, lambda m: m.group(1) + f"{value:.6f}" + m.group(3), s)
    assert n == 1, f"expected one match of {pattern!r} in {path}"
    open(path, "w", encoding="utf-8").write(out)


def main():
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    opt = {a.split("=")[0][2:]: a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--") and "=" in a}
    mode = opt.get("mode", "")
    hidden = int(opt.get("hidden", "16"))
    l2 = float(opt.get("l2", "3e-3"))
    steps = int(opt.get("steps", "3000"))
    X, y, kinds, scatters = load(paths, mode)
    n = len(y)
    print(f"{n} rows, {int(y.sum())} positive, {int((kinds == 'new').sum())} new-word rows", file=sys.stderr)
    # New words typed right weigh three typo rows each: a false rewrite of one
    # is the failure the gate is for, and they are the rarer kind in the rows.
    wt = np.where(kinds == "new", 3.0, 1.0)
    # Fit on the first four fifths of each file's rows, check on the rest.
    idx = np.arange(n)
    check = (idx % 5) == 4
    fitted = fit(X[~check], y[~check], wt[~check], hidden, l2, steps)
    p = predict(X[check], *fitted)
    print("--- check rows ---", file=sys.stderr)
    table(p, y[check], kinds[check], scatters[check], sys.stderr)
    # The shipped weights use every row.
    fitted = fit(X, y, wt, hidden, l2, steps)
    note = f"Fitted by tools/wmlm/train_confidence.py on {', '.join(paths)} ({n} rows, hidden {hidden}, l2 {l2})."
    rust = rust_consts(*fitted, hidden, note)
    kotlin = kotlin_consts(*fitted, hidden, note)
    fixed = fixed_probability(*fitted)
    print(f"fixed case probability {fixed:.6f}", file=sys.stderr)
    if "rust" in opt:
        splice(opt["rust"], rust)
        splice_number(opt["rust"], r"(assert!\(\(p - )([0-9.]+)(\)\.abs\(\) < 1e-4)", fixed)
        print(f"wrote {opt['rust']}", file=sys.stderr)
    else:
        print(rust)
    if "kotlin" in opt:
        splice(opt["kotlin"], kotlin)
        test = opt.get("test", "")
        if test:
            splice_number(test, r"(assertEquals\()([0-9.]+)(, Confidence\.probability\(fixed\), 1e-4\))", fixed)
            print(f"wrote {test}", file=sys.stderr)
        print(f"wrote {opt['kotlin']}", file=sys.stderr)
    elif "rust" not in opt:
        print(kotlin)


if __name__ == "__main__":
    main()
