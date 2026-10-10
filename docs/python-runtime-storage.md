# Python runtime storage

Issue [#54](https://github.com/originalRecipe1/unfurlit/issues/54), measured on
2026-10-10 against #53 baseline `95e8befa65263489ae9337cde09d7898b207386d`.
**Decision: keep only the audited removals.** The stdlib stays unpacked and
Python writes bytecode caches normally. The owner rejected the approximately
one-second startup penalty of the zipped stdlib. Engine pins and app version
remain unchanged; PR #58 targets the next release.

## Runtime layout and updates

`PythonRuntimeArchive` removes the exact audited paths below. All retained
sources, package data, native libraries and site-packages remain in their original
locations, with unchanged payload bytes. Raw compressed data, permissions,
symlink targets and timestamp metadata are preserved across build timezones.
There is no stdlib ZIP, injected `sitecustomize.py`, or build-time `.pyc` generation.
gallery-dl keeps its existing `-S` and `PYTHONDONTWRITEBYTECODE=1` behavior.

Missing removal targets or required stdlib sources, an upstream stdlib ZIP,
unexpected startup customization, or a native file/symlink inside a stdlib
package fail the layout checks for review. The upstream installer replaces the
Python directory when the runtime archive size changes. The upgrade test plants
obsolete bytecode, removed libraries, and files from the rejected ZIP layout,
then verifies replacement with the unpacked stdlib. A row and thumbnail in the
real History schema survive. The opt-in storage test is retained.

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

**Verified — ARM64 release artifacts:** runtime entry payloads fall from
**34,880,897 to 33,393,483 bytes** (1,487,414 bytes saved). The runtime archive
falls from 12,478,102 to 11,892,960 bytes. Matched local unsigned release APKs
are **19,914,537 → 19,353,557 bytes**. Retained runtime payloads and both engines
compare byte for byte with baseline. Artifact hashes are in the measurement JSON.

**Verified — API 30/36 x86_64 debug:** three fresh installs per variant/API,
each followed by the same `youtube-video` fixture
(`https://www.youtube.com/watch?v=eRsGyueVLvQ`). All first-extraction readings
agree within each variant/API. A second extraction per install is retained as
supplemental evidence. All 24 extractions succeeded.

| Metric (bytes unless stated) | #53 baseline | Trims only |
| --- | ---: | ---: |
| API 30 user data excluding cache | 51,179,520 | 48,840,704 |
| API 30 app size | 48,701,440 | 47,538,176 |
| API 36 user data excluding cache | 51,195,904 | 48,857,088 |
| API 36 app size | 48,705,536 | 47,542,272 |
| Runtime regular-file payload after use (both APIs) | 40,024,175 | 38,561,827 |
| Bytecode files (both APIs) | 223 | 223 |
| Bytecode bytes (both APIs) | 4,849,801 | 4,849,801 |
| Cache (both APIs) | 32,768 | 32,768 |

User data falls by **2,338,816 bytes (2.34 MB)** on both APIs. Bytecode caching
is unchanged: **223 files / 4,849,801 bytes** after one extraction. Logical runtime
sizes exclude symlinks. Android `StorageStats` data includes cache, so user data
above is `dataBytes - cacheBytes`. File allocation was also captured with `du`
and `stat`. These emulator debug readings are separate from ARM64 release sizes.

One supplemental API 30 baseline second-extraction app-size reading was
91,693,056 bytes while package state changed from `run-from-apk / unknown` to
`verify / boot`. All first-extraction readings in the table were consistent.
The anomalous later reading remains in the raw JSON; no reset or adjustment was
applied. It does not affect the user-data measurement or offline timing result.

**Owner-reported release baseline:** 1.4.0 on a physical phone uses
**45.29 MB user data / 37.94 MB app size** after use. **Not verified:** release
storage after one extraction or updating that phone from 1.4.0 with History kept.
These are now explicit items in the [pre-release phone checklist](development.md#pre-release-phone-checklist).

## Offline startup: matches baseline within noise

**Verified:** API 36 x86_64, app-UID IPv4 and IPv6 OUTPUT traffic rejected, plus
a blocked socket probe inside `RuntimeStartupTimingTest`. The same engine bytes,
Python launcher, `ProcessBuilder` environment and two commands are used:

- `libpython.so <installed-yt-dlp-zip> --version`.
- `libpython.so -c` setting `sys.path[0]` to that engine ZIP, then
  `import yt_dlp, yt_dlp.YoutubeDL; yt_dlp.YoutubeDL({'quiet': True})`.

Timing covers process start through output collection and exit, using
`SystemClock.elapsedRealtimeNanos`; installation and hashing are outside the
interval. Three fresh installs per variant run in baseline/after, after/baseline,
baseline/after order. Discarding the first invocation of each command on each
install leaves **18 samples per command/variant**. Command order alternates.
No other build or emulator test runs alongside timing. All invocations succeed;
no retained sample or outlier is removed. Quartiles use inclusive interpolation.

| Variant | Command | Median (s) | 25th–75th percentile (s) | Min–max (s) |
| --- | --- | ---: | --- | --- |
| baseline | `--version` | 0.652 | 0.651–0.653 | 0.640–0.664 |
| baseline | Construct `YoutubeDL` | 0.894 | 0.891–0.901 | 0.881–0.908 |
| after | `--version` | 0.654 | 0.649–0.660 | 0.639–0.671 |
| after | Construct `YoutubeDL` | 0.895 | 0.884–0.903 | 0.879–0.940 |

The median changes are **+0.002 s / +0.001 s**, with overlapping spreads.
The trims-only build matches the warmed #53 baseline within this experiment's
noise. **Not verified:** equivalent timing on an ARM64 physical release build.

## Partial stdlib ZIP: estimate only, not implemented

**Verified input measurement:** a separate fresh API 36 x86_64 diagnostic install
ran the **full 64-case live fixture set through the app's routes and both engines**.
Only gallery-dl's bytecode suppression was removed for this local diagnostic,
so its otherwise invisible imports could create caches; its `-S`, entry point,
engine bytes and routes were unchanged. The production runner is unchanged.
No startup benchmark or offline import test preceded this pass.

All **64 fixtures matched their expected outcomes**. Both engines were exercised
successfully.

“Imported during use” is exactly the union of stdlib `.pyc` files created during
that full pass; site-packages and native extensions are excluded. Cached modules
keep their sources and caches on disk. The other sources and package data are
compressed only in host memory to estimate size (ZIP deflate level 6); no partial
ZIP is installed or added to the app.

| Projected layout | Modules | Source/data bytes | Cache bytes | Stored bytes |
| --- | ---: | ---: | ---: | ---: |
| Keep unpacked | 182 | 3,844,095 | 4,347,525 | 8,191,620 |
| Put in ZIP | 306 (+4 data files) | 5,048,004 | 0 | 1,312,795 |

**Not verified — projection:** versus keeping the same observed workload unpacked,
this would save **3,735,209 logical bytes**
(3.74 MB), or about
**5,750,784 allocated file bytes**
(5.75 MB) on this emulator,
excluding directory changes and Android accounting effects. Caches are included
on both sides, so the estimate does not count suppressing them as a saving.

This is an observed import set, not proof that every future link takes the same
paths. A regular package split between disk and ZIP also needs an import-path
design: its disk `__path__` does not automatically search the ZIP for remaining
submodules. Compatibility, phone storage and performance of such a design are
**not verified**. No partial-ZIP implementation or build-time precompilation is
part of #54. Precompiling would require an exact Python 3.12 host and a separate
F-Droid reproducibility design.

## Rejected: about 1 s per-link startup penalty

**Verified historical experiment:** the fully zipped stdlib plus global bytecode
suppression saved **18,825,216 bytes** of emulator user data and reduced ARM64
runtime payload to **26,836,910 bytes**, but offline API 36 medians rose from
**0.656 → 1.637 s** (`--version`) and **0.900 → 1.891 s** (initialization).
Dropping only the global switch gave **1.532 / 1.776 s**, still approximately
**0.88 s slower** than baseline. It wrote 54 site-packages caches totaling
791,415 bytes (1,142,784 allocated bytes). The owner rejected this tradeoff;
neither the stdlib ZIP nor the global switch is retained.
[Historical raw samples](measurements/python-runtime-startup-2026-10-10.json)
preserve the evidence so this approach is not repeated without addressing its cost.

## Validation and reproduction

**Verified:** 11 build-logic tests, 155 app unit tests, 96 Python script tests,
debug lint, debug/instrumentation builds and the R8 release build pass. All five
`PythonRuntimeTest` cases pass with app network access blocked on API 30 and 36,
including engine imports, native dependencies and History-preserving replacement.
A separate install with production gallery-dl settings also passes seven live
extraction routes: YouTube, X mixed media, Reddit gallery, Instagram carousel,
TikTok photos with audio, Tumblr video and Pixiv. These extraction checks and the
full-fixture diagnostic are separate from physical-phone playback/release acceptance. Current-head CI and F-Droid build/scanner/
byte-comparison results are recorded in the associated PR.

Build the source engines as described in [Development](development.md), using
`-Punfurlit.ci.x86_64=true` for emulator builds and the same engine hashes in both
variants. On fresh installs run `SocialLinksTest` with `-e liveLinks true -e linkId
youtube-video`, then `RuntimeStorageMeasurementTest` with `-e runtimeMeasurement
true`. The storage test is opt-in and does not initialize an engine. Repeat three
times; keep a subsequent-extraction sample separate from the first-extraction
comparison.

For startup, block the dedicated app UID in both IP families and run
`RuntimeStartupTimingTest` with `-e runtimeTiming true -e runtimeTimingRuns 6` on
three fresh installs of each variant, discarding the first run of each command
per install. Restore the firewall rules afterward. The `runtimeTiming` status
reports all nanosecond samples and engine/runtime hashes.

[Storage readings](measurements/python-runtime-trims-storage-2026-10-10.json),
[startup samples](measurements/python-runtime-trims-startup-2026-10-10.json), and
[partial-ZIP estimate and cache inventory](measurements/python-runtime-partial-estimate-2026-10-10.json)
contain no local emulator names, device serials or host-specific paths. The canonical
F-Droid recipe remains pinned to published 1.4.0; this work does not change that release.
