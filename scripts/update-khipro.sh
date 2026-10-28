#!/usr/bin/env bash
# Pulls the Khipro team's latest published layout definitions and conformance
# tables into the tree (issue #489).
#
#   scripts/update-khipro.sh        # fetch, then run KhiproTest against them
#
# Khipro is updated on a rolling basis, so the copies under native/wm-engine/data/khipro/
# go stale between releases. Fetching them at build time instead would make
# every build depend on GitHub being reachable, and F-Droid builds offline from
# a fixed source tree, so they are refreshed here and committed like any other
# change. The interpreter in native/wm-engine/src/khipro.rs runs the files verbatim; a test failure
# after an update is a spec feature the interpreter does not handle yet, not a
# table to edit.
#
# When anything changed, REVISION beside the specs is stamped with the time of
# the run: phones take a spec set from the data repository (#909) only when it
# is newer than the one they were built with, so a set published before this
# run stops being offered once a build carrying these copies ships.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/native/wm-engine/data/khipro"
TEST="$ROOT/native/wm-engine/data/khipro/tests"
RAW="https://raw.githubusercontent.com"
CHANGED=0

fetch() {
    local url="$1" dest="$2" tmp
    tmp="$(mktemp)"
    curl -fsSL "$url" -o "$tmp"
    if cmp -s "$tmp" "$dest"; then
        echo "unchanged  ${dest#"$ROOT"/}"
        rm -f "$tmp"
    else
        mv "$tmp" "$dest"
        echo "updated    ${dest#"$ROOT"/}"
        CHANGED=1
    fi
}

# Each spec repository carries its own conformance table beside the spec.
# Classic's files were renamed bn-khipro-classic.mim when Dark (issue #631)
# arrived; the local names stay as they were.
fetch "$RAW/rank-coder/khipro-m17n/HEAD/bn-khipro-classic.mim" "$MAIN/bn-khipro.mim"
fetch "$RAW/rank-coder/khipro-m17n/HEAD/khipro-testcases.tsv" "$TEST/khipro-testcases.tsv"
fetch "$RAW/KhiproTeam/khipro-mim-touchscreen/HEAD/bn-khipro-classic.mim" "$MAIN/bn-khipro-touchscreen.mim"
fetch "$RAW/KhiproTeam/khipro-mim-touchscreen/HEAD/khipro-testcases.tsv" "$TEST/khipro-testcases-touch.tsv"
fetch "$RAW/KhiproTeam/khipro-dark-m17n/HEAD/bn-khipro-dark.mim" "$MAIN/bn-khipro-dark.mim"
fetch "$RAW/KhiproTeam/khipro-dark-m17n/HEAD/khipro-testcases.tsv" "$TEST/khipro-dark-testcases.tsv"
fetch "$RAW/KhiproTeam/khipro-dark-mim-touchscreen/HEAD/bn-khipro-dark.mim" "$MAIN/bn-khipro-dark-touchscreen.mim"
fetch "$RAW/KhiproTeam/khipro-dark-mim-touchscreen/HEAD/khipro-testcases.tsv" "$TEST/khipro-dark-testcases-touch.tsv"

if [ "$CHANGED" = 1 ]; then
    date -u +%Y-%m-%dT%H:%M:%SZ > "$MAIN/REVISION"
    echo "stamped    ${MAIN#"$ROOT"/}/REVISION $(cat "$MAIN/REVISION")"
fi

cd "$ROOT/native/wm-engine"
cargo test khipro
