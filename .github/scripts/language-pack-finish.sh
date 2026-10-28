#!/usr/bin/env bash
# Turns one language split from `aapt2 optimize --split` into the pack the app
# accepts: gives it a base APK's manifest, aligns it and signs it.
#
#   language-pack-finish.sh <split.apk> <out.apk> <versionCode>
#
# aapt2 writes each split with `split="config.xx"` in its manifest, and
# PackageManager.getPackageArchiveInfo, which the app checks a pack's signer
# and versionCode with (LanguagePackDownloads.trusted), refuses any split
# ("Expected base APK, but found split"). 0.6.3 shipped its packs that way and
# no phone could load one. The resources.arsc is kept as it is; only the
# manifest is replaced, by one that names the package and versionCode and
# nothing else.
#
# Needs release.keystore and the RELEASE_* variables, as language-packs.sh.
set -euo pipefail

split=$1
out=$2
code=$3

tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
aapt2=$tools/aapt2
zipalign=$tools/zipalign
apksigner=$tools/apksigner
android_jar=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)

package=$("$aapt2" dump packagename "$split")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# minSdk 30: ResourcesLoader, which loads a pack, is Android 11.
cat > "$work/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$package"
    android:versionCode="$code">
    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="30" />
    <application android:hasCode="false" />
</manifest>
EOF
"$aapt2" link --manifest "$work/AndroidManifest.xml" -I "$android_jar" -o "$work/manifest.apk"

# Every entry but the manifest, as it was (resources.arsc stays stored), then
# the new manifest. No signature files: apksigner writes its own.
python3 - "$split" "$work/manifest.apk" "$work/pack.apk" <<'PY'
import sys, zipfile
split, manifest, out = sys.argv[1:]
with zipfile.ZipFile(split) as src, zipfile.ZipFile(manifest) as man, zipfile.ZipFile(out, "w") as dst:
    info = zipfile.ZipInfo("AndroidManifest.xml", date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    dst.writestr(info, man.read("AndroidManifest.xml"))
    for entry in src.infolist():
        if entry.filename == "AndroidManifest.xml" or entry.filename.startswith("META-INF/"):
            continue
        dst.writestr(entry, src.read(entry.filename))
PY

if "$aapt2" dump badging "$work/pack.apk" | grep -q "split="; then
    echo "$out still declares a split" >&2
    exit 1
fi
"$aapt2" dump badging "$work/pack.apk" | grep -q "versionCode='$code'" \
    || { echo "$out does not declare versionCode $code" >&2; exit 1; }

"$zipalign" -f -p 4 "$work/pack.apk" "$work/aligned.apk"
"$apksigner" sign --ks release.keystore --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:RELEASE_STORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
    --out "$out" "$work/aligned.apk"
rm -f "$out.idsig"
