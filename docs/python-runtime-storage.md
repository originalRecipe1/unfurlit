# Python runtime storage

Issue [#54](https://github.com/originalRecipe1/unfurlit/issues/54), measured on
2026-10-10 against the source-built gallery-dl baseline
`95e8befa65263489ae9337cde09d7898b207386d` from #53. The engine pins and application
version are unchanged. These changes are intended for the next release.

## Runtime layout and update behavior

`PythonRuntimeArchive` moves the Python 3.12 standard library, including package
data, into `usr/lib/python312.zip`. Python's existing search path finds this ZIP;
the upstream youtubedl-android installer needs no changes. `lib-dynload`,
site-packages, shared libraries, and the CA bundle remain at their original paths.
Source bytes are preserved. The inner ZIP has sorted names, fixed timestamps,
and regular-file permissions, and is stored without a second compression layer
inside the runtime archive. Unmoved entries retain their raw compressed bytes,
permissions, symlinks, and timestamp metadata.

The ZIP supplies `sitecustomize.py`, which sets `sys.dont_write_bytecode = True`
before yt-dlp imports its dependencies. gallery-dl runs with `-S` and already
sets `PYTHONDONTWRITEBYTECODE=1`. Both paths were exercised with no `.pyc`, `.pyo`,
or `__pycache__` left in the installed runtime. An unexpected upstream stdlib
ZIP, startup customization, native file in a stdlib package, or missing removal
target fails the transform and requires review.

The upstream installer replaces the entire Python directory when the bundled
archive size changes. The upgrade test plants old unpacked sources, bytecode,
terminal libraries, and the obsolete QuickJS archive, then verifies their
removal and the new ZIP. A row and thumbnail in the real History database schema
survive the replacement. History is outside the replaced runtime directory.

## Dependency audit

The exact paths are maintained in `PythonRuntimeArchive.REMOVED_PATHS`. This
extends the previous removal of `libquickjs.a` and seven CPython test extensions.

| Component | Decision and evidence |
| --- | --- |
| `curses`, `lib2to3`, `venv`, `xmlrpc`, `readline` | Remove. An AST scan of 1,472 Python files in the pinned yt-dlp, gallery-dl and request packages found no imports. Text searches found only unrelated file-object `readline()` calls. Retained Cryptodome and mutagen sources have no imports of these modules. |
| `_curses`, `_curses_panel`, readline extension and terminal libraries | Remove the exact native modules and ncurses/readline library files and aliases. `readelf -d` on 256 retained ARM64/x86_64 ELF entries, including the Python launchers, found no `DT_NEEDED` reference to the removed libraries. |
| SQLite | Keep. gallery-dl imports it unconditionally in `cookies.py`; cache and archive support also use it. The offline test executes a SQLite query. |
| Cryptodome | Keep. yt-dlp's AES helpers and extractors including Bilibili, IVI, TarangPlus and WrestleUniverse import it. The offline test checks an AES known-answer vector. |
| mutagen | Keep. yt-dlp imports it through `dependencies` and uses it in postprocessors. The offline test imports it on the installed runtime. |
| SSL, ctypes, bz2, lzma, XML, encodings, QuickJS executable | Keep. Offline imports, CA loading, gallery-dl startup and live YouTube challenge solving exercise these paths. Only QuickJS's unused static build archive is removed. |

The removal list is tied to these engine/runtime pins. Repeat the audit when
updating them; seven live routes do not establish compatibility with every
extractor supported upstream.

## Storage measurements

**Verified — ARM64 release build artifacts:** the sum of uncompressed runtime
entry payloads falls from **34,880,897 to 26,836,910 bytes**. The stdlib ZIP is
2,335,510 bytes and contains 493 files including the startup policy. The runtime
archive falls from 12,478,102 to 11,860,384 bytes; a matched local unsigned release
APK falls from 19,914,537 to 19,347,929 bytes. Retained payloads and relocated
stdlib contents compare byte for byte with the baseline.

**Verified — emulator measurements:** fresh x86_64 debug installs, one successful
YouTube extraction, then an immediate subsequent extraction. Three independent
fresh installs were measured per build/API; storage readings were identical
across all six samples for each build/API. Both builds used the same source-built
engines and the same opt-in measurement instrumentation.

| API / metric (bytes) | Baseline | Repacked |
| --- | ---: | ---: |
| API 30 user data, excluding cache | 51,179,520 | 32,354,304 |
| API 36 user data, excluding cache | 51,195,904 | 32,370,688 |
| API 30 app size | 48,701,440 | 47,501,312 |
| API 36 app size | 48,705,536 | 47,505,408 |
| Cache, either API | 32,768 | 32,768 |
| Runtime regular-file payloads after use, either API | 40,024,175 | 27,155,427 |
| Bytecode files after use, either API | 223 | 0 |
| Bytecode payload bytes, either API | 4,849,801 | 0 |

The data reduction is **18,825,216 bytes (36.8%)** on either emulator. Logical
file lengths exclude symlinks; filesystem block allocation was also captured
with `run-as ... du -ak .`. App/data/cache numbers come from Android
[`StorageStats`](https://developer.android.com/reference/android/app/usage/StorageStats).
`getDataBytes()` includes caches, so the user-data column subtracts
`getCacheBytes()`. These debug x86_64 results are separate from phone release
measurements.

**Owner-reported release baseline:** the physical phone running 1.4.0 has
**45.29 MB user data / 37.94 MB app size** after use. The earlier 46.69 MB / 48 MB
readings belong to the debug Link Check variant. **Not verified:** physical-phone
release storage and update behavior after this runtime change.

## Extraction timing

**Verified:** all 24 extractions succeeded using the `youtube-video` fixture
(`https://www.youtube.com/watch?v=eRsGyueVLvQ`). Each cold sample starts from a
fresh install. Each subsequent sample starts another instrumentation process
with the extracted runtime and app data retained. Times include network access
and YouTube challenge solving; these are small samples, not an isolated CPU
benchmark.

| API / build | Three cold samples (s) | Cold median | Three subsequent samples (s) | Subsequent median |
| --- | --- | ---: | --- | ---: |
| 30 baseline | 19.1, 17.3, 17.4 | 17.4 | 15.9, 15.6, 16.1 | 15.9 |
| 30 repacked | 17.7, 17.6, 17.4 | 17.6 | 17.3, 18.9, 16.7 | 17.3 |
| 36 baseline | 18.3, 17.6, 19.3 | 18.3 | 16.0, 17.5, 16.3 | 16.3 |
| 36 repacked | 17.6, 17.6, 17.9 | 17.6 | 17.4, 18.2, 17.5 | 17.5 |

Later extractions were 1.2–1.4 seconds slower at the median in these samples.
Avoiding persistent bytecode trades repeated parsing/decompression for storage.
Network variability prevents attributing the full difference to that change.

## Validation and reproduction

**Verified:** 11 build-logic tests, 155 app unit tests, 96 Python script tests,
debug lint, debug/test APK builds, and the R8 release build pass. All five
`PythonRuntimeTest` cases pass on API 30 and API 36 with both IPv4 and IPv6
blocked for the app UID. This includes engine startup, bundled EJS presence,
stdlib ZIP imports, native dependencies, gallery-dl, bytecode policy, and cleanup
that preserves History.

Build with `-Punfurlit.ci.x86_64=true` for the emulators. Use
`scripts/build_yt_dlp_from_source.sh` and pass the resulting
`unfurlit.ytdlp.file`/`unfurlit.ytdlp.sha256` properties as documented in
[Development](development.md). To measure a controlled fresh installation,
install the app and test APKs on a dedicated emulator, run `SocialLinksTest` with
`-e liveLinks true -e linkId youtube-video`, then run:

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.RuntimeStorageMeasurementTest \
  -e runtimeMeasurement true \
  io.github.originalrecipe1.unfurlit.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$serial" shell run-as io.github.originalrecipe1.unfurlit du -ak .
```

The storage test is opt-in and does not initialize an engine. Its
`runtimeStorage` instrumentation status contains the raw byte counts. Run a
second extraction without clearing data for the subsequent sample; repeat the
fresh-install pair three times for each build. Keep the emulator ABI, engine
artifacts and fixture constant.

Live playback and current-head CI/F-Droid results are recorded in the associated
PR. F-Droid validation of this unreleased change compares its unsigned APK with
CI at the same source commit. The canonical `docs/fdroid/` recipe remains pinned
to the published 1.4.0 release; this work does not publish or modify that release.
