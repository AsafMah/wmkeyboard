"""Sentences out of the corpora the model trains on, each with its
register and field label.

Sources:
  tatoeba   <lang>_sentences.tsv.bz2 (id, lang, text): casual, chat
  leipzig   <name>.tar.gz holding <name>-sentences.txt (id\\ttext):
            *_news_* is formal/other, *web* is neutral/other,
            *wikipedia* is formal/other
  lines     a plain text file, one sentence per line, with the labels given

usage: corpus.py <spec> [<spec> ...] > sentences.tsv
  spec = tatoeba:<path>[:reg:field] | leipzig:<path>[:reg:field] | lines:<path>:reg:field
Writes `register\\tfield\\tlang\\ttext` lines, labels as indices into
common.REGISTERS / FIELDS, lang as the 0-3 slot given with `--lang`.
"""
import bz2
import sys
import tarfile

from common import FIELDS, REGISTERS


def tatoeba(path, reg, field, lang):
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[2].strip():
                yield reg, field, lang, parts[2].strip()


def leipzig(path, reg, field, lang):
    with tarfile.open(path, "r:gz") as tar:
        for member in tar.getmembers():
            if member.name.endswith("-sentences.txt"):
                f = tar.extractfile(member)
                for raw in f:
                    line = raw.decode("utf-8", errors="replace").rstrip("\n")
                    parts = line.split("\t", 1)
                    text = parts[1] if len(parts) == 2 else parts[0]
                    if text.strip():
                        yield reg, field, lang, text.strip()


def lines(path, reg, field, lang):
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                yield reg, field, lang, line.strip()


def default_labels(kind, path):
    if kind == "tatoeba":
        return "casual", "chat"
    name = path.lower()
    if "news" in name or "wikipedia" in name:
        return "formal", "other"
    return "neutral", "other"


def main():
    lang = 0
    out = sys.stdout
    for spec in sys.argv[1:]:
        if spec.startswith("--lang="):
            lang = int(spec.split("=")[1])
            continue
        parts = spec.split(":")
        kind, path = parts[0], parts[1]
        reg, field = default_labels(kind, path)
        if len(parts) >= 4:
            reg, field = parts[2], parts[3]
        r = REGISTERS.index(f"<reg:{reg}>")
        fi = FIELDS.index(f"<field:{field}>")
        gen = {"tatoeba": tatoeba, "leipzig": leipzig, "lines": lines}[kind]
        n = 0
        for reg_i, field_i, lang_i, text in gen(path, r, fi, lang):
            out.write(f"{reg_i}\t{field_i}\t{lang_i}\t{text}\n")
            n += 1
        print(f"{spec}: {n} sentences", file=sys.stderr)


if __name__ == "__main__":
    main()
