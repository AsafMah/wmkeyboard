#!/usr/bin/env bash
# One-time font fetch for running the pipeline locally (play/pipeline/fonts/).
set -e
cd "$(dirname "$0")"
mkdir -p fonts && cd fonts
base="https://raw.githubusercontent.com/google/fonts/main/ofl"
curl -sL "$base/manrope/Manrope%5Bwght%5D.ttf"                -o Manrope.ttf
curl -sL "$base/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"      -o SpaceGrotesk.ttf
curl -sL "$base/notosansbengali/NotoSansBengali%5Bwdth,wght%5D.ttf" -o NotoSansBengali.ttf
curl -sL "$base/notosansdevanagari/NotoSansDevanagari%5Bwdth,wght%5D.ttf" -o NotoSansDevanagari.ttf
curl -sL "$base/notosansarabic/NotoSansArabic%5Bwdth,wght%5D.ttf"     -o NotoSansArabic.ttf
curl -sL "$base/inter/Inter%5Bopsz,wght%5D.ttf"               -o Inter.ttf
ls -la
