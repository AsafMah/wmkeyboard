"""Fits the built-in glide decoder's tuning (GlideBeam) on real swipes.

Coordinate descent over `Tuning`'s fields against `glide_beam_eval`, which
keeps the strokes parsed and answers one tuning per line. Each round tries
every field a step up and a step down from the best so far, keeps any step
that raises top-1, and halves the steps of the fields that did not move.

    [START="name=value ..."] python -I fit_glide.py <glide_beam_eval binary> <train.jsonl> <qwerty.json> <word list> [rounds]

Fit on a training split; check the result on a split the fit never saw.
"""

import os
import subprocess
import sys

# field: (start, step as a factor, lower bound, upper bound)
FIELDS = {
    "sigma": (0.42, 1.25, 0.2, 1.0),
    "max_point_cost": (12.0, 1.5, 3.0, 80.0),
    "gap_weight": (2.0, 1.5, 0.2, 8.0),
    "gap_window": (14.0, 1.5, 4.0, 80.0),
    "shape_weight": (2.5, 1.5, 0.05, 10.0),
    "shape_channel": (45.0, 1.5, 0.0, 200.0),
    "repeat_cost": (0.05, 2.0, 0.0, 1.0),
    "unlooped_repeat": (0.1, 2.0, 0.0, 1.0),
    "dwell_full": (3.0, 1.5, 0.5, 12.0),
    "unclaimed_dwell": (2.0, 1.5, 0.0, 8.0),
    "loop_exclusion": (1.5, 1.5, 0.0, 6.0),
    "unclaimed_loop": (0.5, 1.5, 0.0, 4.0),
    "start_radius": (1.6, 1.25, 0.8, 3.0),
    "end_radius": (1.6, 1.25, 0.8, 3.0),
    "near_radius": (1.5, 1.25, 0.8, 3.0),
}


def main():
    binary, records, layout, words = sys.argv[1:5]
    rounds = int(sys.argv[5]) if len(sys.argv) > 5 else 6
    proc = subprocess.Popen(
        [binary, records, layout, words],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    seen = {}

    def score(tuning):
        key = tuple(sorted(tuning.items()))
        if key not in seen:
            line = " ".join(f"{k}={v:.6g}" for k, v in sorted(tuning.items()))
            proc.stdin.write(line + "\n")
            proc.stdin.flush()
            out = proc.stdout.readline().strip()
            seen[key] = (float(out.split()[1]), out)
        return seen[key]

    best = {k: v[0] for k, v in FIELDS.items()}
    # START="name=value ..." resumes from an earlier fit's best.
    for pair in os.environ.get("START", "").split():
        name, value = pair.split("=")
        best[name] = float(value)
    steps = {k: v[1] for k, v in FIELDS.items()}
    top, line = score(best)
    print(f"start {line}", flush=True)
    for r in range(rounds):
        moved = False
        for field, (_, _, lo, hi) in FIELDS.items():
            improved = True
            while improved:
                improved = False
                for direction in (steps[field], 1 / steps[field]):
                    value = best[field] * direction if best[field] > 0 else (lo + hi) / 20 * (direction > 1)
                    value = min(hi, max(lo, value))
                    if value == best[field]:
                        continue
                    trial = dict(best, **{field: value})
                    s, l = score(trial)
                    if s > top + 1e-9:
                        top, line, best = s, l, trial
                        improved = moved = True
                        print(f"round {r} {field}={value:.4g} -> {l}", flush=True)
                        break
        if not moved:
            for field in steps:
                steps[field] = steps[field] ** 0.5
            if all(s < 1.02 for s in steps.values()):
                break
    print("best " + " ".join(f"{k}={v:.4g}" for k, v in best.items()), flush=True)
    print(f"final {line}", flush=True)
    proc.stdin.close()
    proc.wait()


if __name__ == "__main__":
    main()
