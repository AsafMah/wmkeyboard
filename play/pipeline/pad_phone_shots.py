#!/usr/bin/env python3
"""Pad the phone slides out to a Play-legal aspect ratio.

The slides are composed on a 1080x2400 canvas because that is the shape of the
device they were captured on. Play will not take that: a store screenshot's
"maximum dimension can't be more than twice the minimum dimension", and
2400 is 2.22x 1080, so the upload is refused before anyone looks at the art.
The same rule is why the downscaled 810x1800 copies under fastlane/ were no
better - the ratio travels with the scale.

So this widens the canvas instead of cropping the art: 1080x2400 becomes
1200x2400, exactly 2:1, by replicating the leftmost and rightmost column of
pixels 60px outwards. The backgrounds are vertical gradients, so an edge
column is the correct colour for every row and the seam is invisible; nothing
is scaled, moved or clipped. 1200 on the short side also clears the 1080px
floor that large-format Play recommendations ask for, which 810 did not.

Run from the repo root:  python3 play/pipeline/pad_phone_shots.py
Reads play/store-listing/??-*.png, writes both that file and the fastlane
upload copy in place. Idempotent: an image that is already 2:1 is skipped.
"""

from __future__ import annotations

import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "play" / "store-listing"
REGIONAL_DIR = SOURCE_DIR / "regional"
CSL_DIR = ROOT / "play" / "store-listing-csl"
UPLOAD_DIR = (
    ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "phoneScreenshots"
)

TARGET_W, TARGET_H = 1200, 2400

CSL_MAP = {
    "bangladesh": "02-bengali.png",
    "india": "02-hindi.png",
    "pakistan": "02-urdu.png",
    "arab": "02-arabic.png",
}


def pad(img: Image.Image) -> Image.Image:
    """[img] centred on a TARGET_W canvas, the gap filled by its edge columns."""
    out = Image.new("RGB", (TARGET_W, TARGET_H))
    left = (TARGET_W - img.width) // 2
    # Stretch one column, not a solid fill: the backgrounds are vertical
    # gradients, so the edge column already holds the right colour per row.
    out.paste(img.crop((0, 0, 1, img.height)).resize((left, img.height)), (0, 0))
    out.paste(
        img.crop((img.width - 1, 0, img.width, img.height)).resize(
            (TARGET_W - left - img.width, img.height),
        ),
        (left + img.width, 0),
    )
    out.paste(img, (left, 0))
    return out


def pad_file(path: pathlib.Path) -> Image.Image:
    with Image.open(path) as opened:
        img = opened.convert("RGB")
        if (img.width, img.height) == (TARGET_W, TARGET_H):
            return img
        if img.height != TARGET_H or img.width > TARGET_W:
            return img
        padded = pad(img)
        padded.save(path)
        return padded


def main() -> int:
    slides = sorted(p for p in SOURCE_DIR.glob("*.png") if p.name[:2].isdigit())
    if not slides:
        print(f"No numbered slides in {SOURCE_DIR}", file=sys.stderr)
        return 1

    print("=== Padding Global Slides (for Fastlane & Main Store Listing) ===")
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    global_padded_shots: dict[int, Image.Image] = {}
    for index, path in enumerate(slides, start=1):
        padded = pad_file(path)
        global_padded_shots[index] = padded
        padded.save(UPLOAD_DIR / f"{index}.png")
        print(f"  ✔ {path.name} -> {UPLOAD_DIR.name}/{index}.png ({TARGET_W}x{TARGET_H})")
    print(f"  → {len(slides)} screenshots in {UPLOAD_DIR}")

    # Build Custom Store Listing (CSL) sets for regional countries
    if REGIONAL_DIR.is_dir():
        print("\n=== Building Regional Custom Store Listing (CSL) Sets ===")
        for region, reg_filename in CSL_MAP.items():
            reg_path = REGIONAL_DIR / reg_filename
            if not reg_path.is_file():
                continue
            reg_padded = pad_file(reg_path)
            target_csl_dir = CSL_DIR / region
            target_csl_dir.mkdir(parents=True, exist_ok=True)

            # Copy 1.png - 8.png with slide 2 replaced by regional slide
            for idx, shot in global_padded_shots.items():
                dest_file = target_csl_dir / f"{idx}.png"
                if idx == 2:
                    reg_padded.save(dest_file)
                else:
                    shot.save(dest_file)
            print(f"  ✔ CSL [{region.upper()}]: 8 screenshots ready in {target_csl_dir} (Slide 2: {reg_filename})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
