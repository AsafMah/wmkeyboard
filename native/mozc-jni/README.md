# mozc-jni: the Mozc engine for Japanese

[Mozc](https://github.com/google/mozc) is Google's open-source Japanese
input method engine, the one inside Google Japanese Input and Gboard's
Japanese keyboard. This directory builds its converter into `libwmmozc.so`,
one shared library with a small JNI bridge, which the keyboard uses as an
optional engine for Japanese. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.input.mozc.MozcNative` (in
`core/input/src/main`), driven by `MozcRuntime` and `MozcComposer`.

The built library is committed under `core/input/src/mozc/jniLibs/`, so the
app builds without an NDK or Bazel. It never ships inside an APK, though:
Play builds deliver it as the on-demand `:feature:mozc` split, and the GitHub
and F-Droid builds pin it in `assets/native/pins.tsv` under the `mozc` gate
and fetch it from the data repository's `natives` release when someone picks
Mozc. F-Droid deletes the committed copies and builds them with this script
(through `native/build-fdroid-libs.sh`), and so does the release workflow for
the reference APK. Rebuild after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/mozc-jni/build.sh
```

Mozc builds with Bazel and nothing else, so this does too. The script
takes Bazel at the version Mozc names from `MOZC_BAZEL` or a `bazel` on
PATH (a bazelisk install serves: the pinned version and its SHA-256 are
exported for it), and otherwise, on macOS, fetches bazelisk at a pinned
release, checks its SHA-256 and has it fetch that Bazel, checked as well.
On Linux it builds Bazel from source instead: it fetches Bazel's
distribution archive, checks its SHA-256 and bootstraps it with the
archive's own `compile.sh` (a JDK 21 from `MOZC_JDK`, `JAVA_HOME` or
Debian's `openjdk-21-jdk-headless`, plus `zip`, `unzip`, a C++ compiler
and Python 3). That is the F-Droid path, since F-Droid takes no prebuilt
Bazel ([#964](https://github.com/wasi-master/wmkeyboard/issues/964)), and
the release workflow's reference build takes it too. Nothing prebuilt is
downloaded on that path: the bootstrapped Bazel runs on the JDK found, and
Mozc's build is told to use that JDK and the machine's `python3`
(`--tool_java_runtime_version=local_jdk`,
`--extra_toolchains=@rules_python//python/runtime_env_toolchains:all`)
rather than the JDK and Python that Bazel and rules_python would fetch.
Either way the script then clones Mozc at the pinned tag and checks the
commit, copies this directory into the checkout as the package
`src/wmkeyboard/`, and builds it with Mozc's own Android configuration
(`--config oss_android --config release_build`), the one Mozc's CI builds
`libmozc.so` with. Two edits to the checkout: the NDK is
linked where Mozc's `MODULE.bazel` looks for it, and the API level is set to
24, the keyboard's minimum, where Mozc leaves rules_android_ndk's 31. Bazel
fetches Mozc's module dependencies (Abseil, Protocol Buffers and the rules)
from the Bazel Central Registry, each checked against the hash in the
registry. One ABI takes about 15 minutes on an M-series Mac with nothing
else running; `WMKB_ABIS` narrows the build. The library is linked with
16 KB pages and exports only the `Java_…_MozcNative_*` functions. Bazel runs
every compile with paths relative to its execution root, so no path of the
build machine reaches the library.

`build.sh --data DIR` also builds Mozc's system dictionary into
`DIR/mozc.data`, on the host, since its generators run there. That file is
what the data repository publishes as `cjk/mozc/mozc-<version>.data` and
what `MozcData` pins by SHA-256: the library opens it from the downloaded
CJK pack instead of carrying it, and it has to come from the same Mozc
release as the library, since its format is the library's.

## Provenance

| Component | Version | Licence |
|---|---|---|
| Mozc | `3.34.6239`, commit `76887c679e1e4f156102e4bc62ea9cf9174678a3` | BSD-3-Clause |
| Abseil | `20260107.1` (Mozc's `MODULE.bazel`) | Apache-2.0 |
| Protocol Buffers | `34.1` (Mozc's `MODULE.bazel`) | BSD-3-Clause |
| Bazel | `9.0.2`: on Linux bootstrapped from `bazel-9.0.2-dist.zip`, on macOS through bazelisk `1.29.0` | Apache-2.0, build tool only |
| Mozc dictionary | built from Mozc's `src/data` at the same commit: IPAdic, ICOT, the Okinawa dictionary, and the Japanese usage dictionary Mozc's `MODULE.bazel` pins | see the notices |

All unmodified. The licences of all of it are in the app as
`app/src/main/assets/licenses/mozc-third-party.txt`.

No Android port of Mozc was used. The bridge is new code over Mozc's own
headers. Mozc's own `android/jni` target was read for how it opens a data
file, and its `EngineConverter` (`src/engine/engine_converter.cc`) for the
order of converter calls the bridge makes.

## Why the converter, not the session

Mozc's session layer, which its own `libmozc.so` exposes through one
protobuf-in, protobuf-out JNI call, keeps a candidate window, a keymap and a
state machine of its own, and the keyboard already has all three. More to the
point, neither the session's output nor `EngineConverter`'s says how much of
the reading a partial suggestion covers. `ConverterInterface` and its
`Segments` do: `Candidate::consumed_key_size` for a partial suggestion, each
segment's `key_len()` for a conversion. That is what the keyboard's prefix
commit needs, so the bridge (`mozc_bridge.cc`) calls the converter directly,
in the order `EngineConverter` does, and keeps one `Segments` across calls
for its history: that is Mozc's context for what comes next, and what its
learning reads when a conversion is finished.

## Contract with Kotlin

Readings go in as hiragana. Romaji the keyboard has not turned into kana yet
go in last as romaji, through Mozc's own table, so its prediction expands
them (`h` to は, ひ, ふ…). Every length that comes back is in characters of
that reading; the Kotlin side maps them back to buffer characters through
the spans of the keyboard's own transducer. Strings cross as UTF-16.

- `nativeStart(dataFile, userDir)` / `nativeStop()`: open Mozc on a
  `mozc.data` with its history under `userDir`, and close it, writing the
  history out.
- `nativeSuggest(reading, preceding, limit, info)`: the typing list, Mozc's
  mobile prediction with partial suggestions.
- `nativeConvert(reading, preceding, ops, limit, info)`: the reading by
  segments, after replaying `ops` (resize the focused segment by one, move
  the focus by one) from Mozc's own split, with the focused segment's
  candidates.
- `nativePredict(preceding, limit, info)`: zero-query suggestions.
- `nativeCommit*`: re-run the same call, then commit candidate `id` the way
  `EngineConverter` would, and finish the conversion so Mozc learns it.
- `nativeSync()`: write the learnt history out. `nativeClearHistory()`
  forgets it.

The menu calls fill `info` with the counts and the focused segment, then
each candidate's id and consumed length, and return the candidate texts
followed by each segment's reading and best text.

## Testing on a desktop

`host-test.sh` builds `mozc_host_test`, which drives `mozc_bridge.cc`
against a real `mozc.data`, the same JNI library for a desktop JVM, and
`mozc.data` itself.

```sh
printf 'suggest きょうはいいてんき\nconvert きょうはいいてんき 1\n' \
    | mozc_host_test mozc.data /tmp/mozc-user
```

prints the typing list for きょうはいいてんき with how much of the reading
each candidate covers, then the reading split into segments with the first
one widened by a character.
