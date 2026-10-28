#!/usr/bin/env bash
# Cuts the interface translations out of the all-languages build into one
# small resource-only APK per language, the packs the standard (English) build
# downloads and loads with ResourcesLoader on Android 11 and later
# (LanguagePacks in :app).
#
#   language-packs.sh <intl-universal.apk> <out-dir> <versionName> <versionCode>
#
# The all-languages build is compiled with the English build's resource IDs
# (wmkb.stableResourceIds), so a resource in a pack has the ID the English
# APK looks it up by. `aapt2 optimize --split` is how bundletool makes Play's
# language splits: each pack holds one locale's values and nothing else.
# language-pack-finish.sh then gives each a base APK's manifest, since the app
# cannot check a split's signer, and signs it.
#
# Each pack is signed with the release key, which is what the app checks
# before it loads one: release.keystore and the RELEASE_* variables must be
# set, as in the job that builds the APKs.
set -euo pipefail

apk=$1
out=$2
version=$3
code=$4

tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
aapt2=$tools/aapt2

# The app's own translations, by resource qualifier: values-fr -> fr,
# values-zh-rCN -> zh-rCN. The same folders :app's APP_LOCALES is read from.
locales=$(ls app/src/main/res | sed -n -E 's/^values-([a-z]{2,3}(-r[A-Z]{2})?)$/\1/p' | sort)
[ -n "$locales" ] || { echo "no translations under app/src/main/res" >&2; exit 1; }

work=$(mktemp -d)
args=()
for locale in $locales; do
    args+=(--split "$work/$locale.apk:$locale")
done
"$aapt2" optimize -o "$work/base.apk" "${args[@]}" "$apk"

# The universal APK's own versionCode (release * 10 + its ABI digit), which
# the app reads the release back out of.
manifest_code=$("$aapt2" dump badging "$apk" | sed -n -E "s/^package: .*versionCode='([0-9]+)'.*/\1/p")
[ -n "$manifest_code" ] || { echo "no versionCode in $apk" >&2; exit 1; }

mkdir -p "$out"
for locale in $locales; do
    split=$work/$locale.apk
    [ -f "$split" ] || { echo "aapt2 wrote no pack for $locale" >&2; exit 1; }
    target=$out/wmkeyboard-${version}-vc${code}-lang-${locale}.apk
    "$(dirname "$0")/language-pack-finish.sh" "$split" "$target" "$manifest_code"
    echo "$locale: $(wc -c < "$target") bytes"
done
rm -rf "$work"
