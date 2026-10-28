#!/bin/sh
# Emissions for every layout, then the trie beam over them.
# usage: eval.sh <checkpoint.pt> <futo dir with test.jsonl, qwerty.json> <layouts dir> <out dir> [FW ...]
# Needs PY (a python with torch) and wm-engine's release ctc_glide_eval built.
set -e
ckpt=$1; futo=$2; lay=$3; out=$4; shift 4
here=$(cd "$(dirname "$0")" && pwd)
engine="$here/../../native/wm-engine"
words="$here/../../app/dictionaries-src/en.txt"
mkdir -p "$out"
cd "$here"
"$PY" emit.py "$ckpt" "$futo/test.jsonl" "$futo/qwerty.json" "$out/qwerty.bin" > /dev/null
for n in qwertz azerty colemak dvorak abc; do
  "$PY" emit.py "$ckpt" "$lay/$n.jsonl" "$lay/$n.json" "$out/$n.bin" > /dev/null
done
[ $# -eq 0 ] && set -- 0.166
for fw in "$@"; do
  for n in qwerty qwertz azerty colemak dvorak abc; do
    printf '%s FW %s ' "$n" "$fw"
    FW=$fw "$engine/target/release/examples/ctc_glide_eval" "$out/$n.bin" "$words" | tail -1 | cut -d'|' -f1-2
  done
done
