"""Writes the real-swipe fixture GlideRealSwipeFloorTest reads.

Takes every `stride`-th stroke of a swipe.futo.org jsonl split whose word is
two or more letters a-z and in the word list, up to `count` of them, and
writes them gzipped, one stroke a line:

    word width height x,y,dt x,y,dt ...

x and y in thousandths of the canvas, dt in milliseconds since the previous
point. The first line carries the dataset's key centres, the same way:
`#keys q:x,y w:x,y ...`.

    python -I make_fixture.py <test.jsonl> <qwerty.json> <word list> <out.txt.gz> [count] [stride]
"""

import gzip
import json
import sys


def main():
    records, layout, words, out = sys.argv[1:5]
    count = int(sys.argv[5]) if len(sys.argv) > 5 else 2000
    stride = int(sys.argv[6]) if len(sys.argv) > 6 else 13
    known = set()
    with open(words, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                known.add(line.split()[0].split(",")[0].split("=")[-1].lower())
    keys = json.load(open(layout))["keys"]
    lines = ["#keys " + " ".join(
        f"{k['letter']}:{round(k['cx'] * 1000)},{round(k['cy'] * 1000)}"
        for k in keys if len(k.get("letter", "")) == 1 and k["letter"].isalpha()
    )]
    taken = 0
    eligible = 0
    with open(records, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            word = r["word"].lower()
            if len(word) < 2 or not word.isascii() or not word.isalpha() or word not in known:
                continue
            eligible += 1
            if eligible % stride:
                continue
            last = r["data"][0]["t"]
            points = []
            for p in r["data"]:
                points.append(f"{round(p['x'] * 1000)},{round(p['y'] * 1000)},{p['t'] - last}")
                last = p["t"]
            lines.append(f"{word} {r['canvas_width']:.6g} {r['canvas_height']:.6g} " + " ".join(points))
            taken += 1
            if taken >= count:
                break
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as g:
        g.write("\n".join(lines) + "\n")
    print(f"{taken} strokes")


if __name__ == "__main__":
    main()
