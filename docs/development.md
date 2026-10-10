# Development

Build, test, and understand Unfurlit. For using the app, see the [user guide](usage.md).

## Build

Requirements:

- JDK 21 for the Gradle runtime (the app still targets Java 17 bytecode)
- Android SDK 36
- a 64-bit ARM device running Android 7.0+, or an emulator (see the CI build option below)
- Git, Python 3, Make, Zip, Node.js 22 and npm only when building the extractor from source

The repository pins the Gradle daemon to Java 21 in
`gradle/gradle-daemon-jvm.properties`. Gradle 8.14.5 cannot run on Java 25.
In Android Studio, leave **Gradle JDK** set to **GRADLE_LOCAL_JAVA_HOME** and
make sure the resolved JDK is version 21. For command-line builds, install a
discoverable JDK 21 or set `JAVA_HOME` to one before invoking the wrapper.

Build and run tests:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

With an emulator or device selected through `ANDROID_SERIAL`, run the deterministic
Compose tests with `./gradlew connectedDebugAndroidTest`. CI runs the same tests
on an AOSP API 30 Gradle-managed device. Normal debug builds contain only
`arm64-v8a` native libraries. CI opts into an x86_64 debug APK for its emulator:

```bash
./gradlew pixel2Api30DebugAndroidTest -Punfurlit.ci.x86_64=true
```

The same option works with `assembleDebug` or `connectedDebugAndroidTest` for
local x86_64 emulator testing. Release APKs always contain only `arm64-v8a`,
even when this option is set. Live extraction tests run in a separate weekly/manual workflow so
platform rate limits and datacenter blocking cannot make pull requests flaky.

If the API 36 emulator's software renderer crashes during boot, cold-boot it
with `-gpu host -feature -Vulkan -no-snapshot`:

```bash
"$ANDROID_HOME/emulator/emulator" -avd "<avd-name>" -no-window -no-audio -gpu host -feature -Vulkan -no-snapshot
ANDROID_SERIAL="<emulator-serial>" ./gradlew connectedDebugAndroidTest -Punfurlit.ci.x86_64=true
```

Replace the placeholders with the selected AVD name and the serial reported by
`adb devices`. CI uses its configured software renderer.

Release builds use R8 code optimization and resource shrinking, including the
optimized resource shrinker for AGP 8.13. CI also builds the release APK so
shrinker failures are caught on pull requests. Validate changes to dependencies
or keep rules with playback in a signed release build; debug tests do not run
the optimized code. Keep `app/build/outputs/mapping/release/mapping.txt` with
each release to decode obfuscated crash traces. The release workflow attaches
this mapping file alongside the signed APK and checksum.

In historical ARM64 measurements on 2026-09-14, R8 and optimized resource
shrinking reduced the unsigned release APK from 29,972,943 to 20,079,981 bytes
and uncompressed DEX code from 30,492,864 to 3,995,044 bytes.

For daily testing on a development phone and for judging scrolling performance,
use `localRelease`. It has release optimizations and no debugger/tooling overhead,
but uses the local debug signing key so it can update a debug installation without
clearing history:

```bash
./gradlew :app:assembleLocalRelease
adb install -r app/build/outputs/apk/localRelease/app-localRelease.apk
```

Use `debug` for debugging and instrumentation tests. Do not distribute
`localRelease`; public releases use the release signing process. Compare frame
timings on the same phone, with the same history and scrolling sequence, after
force-stopping and relaunching the app. Debug timings are not representative of
release performance.

A sanity check on a physical ARM64 phone (2026-09-18), using the same saved history,
process restart, History navigation, and eight alternating 450 ms vertical swipes,
reported the following through `adb shell dumpsys gfxinfo
io.github.originalrecipe1.unfurlit framestats`:

| Build | Janky frames | 95th percentile frame time |
| --- | --- | --- |
| Debug | 26 / 344 (7.56%) | 34 ms |
| Local release, first run | 2 / 412 (0.49%) | 13 ms |
| Local release, repeat | 3 / 413 (0.73%) | 13 ms |

These are on-device diagnostic samples, not controlled benchmark results. Both
release runs finished with History visible; the database and thumbnails were
preserved across the build update. No forced ahead-of-time compilation was used.

The cached AAR transform in `buildSrc` trims the bundled Python runtime for all
builds, including x86_64 CI tests. The standard library and site-packages stay
unpacked, and Python may write its normal bytecode caches. gallery-dl retains
its existing `-S` and `PYTHONDONTWRITEBYTECODE=1` behavior.

The exact removal list covers the static QuickJS build archive, seven CPython
test extensions, and audited unused terminal/development packages. Retained
entries preserve file contents, compressed payloads, Unix permissions, symlink
targets, and timestamp metadata across build timezones. The downloaded Maven
artifact and dependency metadata remain unchanged; an unexpected runtime layout
fails the transform for review. See the
[runtime storage audit and measurements](python-runtime-storage.md) for the
removal rationale, storage savings, and rejected zipped-stdlib experiment.

In historical ARM64 measurements on 2026-09-15, trimming reduced expanded
runtime files from 41,680,927 to 34,880,897 bytes and the release APK from
20,079,981 to 18,282,669 bytes.

Run `./gradlew :buildSrc:test` for the archive preservation tests. Instrumented
tests also start the trimmed Python/yt-dlp engine with `--version`, without
accessing a media site, and verify that replacing an older runtime removes
obsolete files while preserving a database entry. The upstream runtime installer replaces its Python
directory when the bundled archive size changes, so existing installations
reclaim the space on their next extraction without clearing viewing history.

For Android Studio, select the shared **Unfurlit** run configuration, choose one or
more connected devices from the target-device selector, and press **Run**. The
configuration launches the default activity and does not clear app data.

The normal build downloads the official yt-dlp `2026.08.19` zipimport executable
and verifies its pinned SHA-256 before packaging it as an app resource. The app
then uses that bundled copy through youtubedl-android; it does not fetch or update
executable code at runtime.

For a source build, initialize the pinned submodules and
build the extractor first:

```bash
git submodule update --init --recursive
./scripts/build_yt_dlp_from_source.sh
source_file="$PWD/build/yt-dlp-source/yt-dlp"
source_sha="$(sha256sum "$source_file" | awk '{print $1}')"
./gradlew --offline --no-daemon assembleRelease \
  -Punfurlit.ytdlp.file="$source_file" \
  -Punfurlit.ytdlp.sha256="$source_sha"
```

This path performs no extractor download during Gradle execution. Gradle verifies
the supplied archive's checksum and embedded version before packaging it.
Targeted YouTube extraction checks passed on the API 30 emulator with the
source-built engine; physical-phone playback still needs confirmation.

The source build includes yt-dlp's required JavaScript challenge solver from the
pinned `third_party/yt-dlp-ejs` source, using its npm lockfile. `npm ci` needs
network access or a populated npm cache (`npm_config_offline=true`); the resulting
solver is bundled in the extractor, with no runtime code download. Its version in
`libs.versions.toml` must match the pinned yt-dlp Makefile. The build checks both
solver scripts and their Python package, and fails if the Makefile falls back to
downloading the upstream EJS wheel. PR CI builds a debug APK with the official
asset, then verifies APK builds using the source-built extractor;
the update workflow also compares its solver scripts byte for byte with the
checksum-verified official release. Run `python3 scripts/update_yt_dlp_notices.py`
after changing these pins; CI checks that the notices match. The 1.3.1 F-Droid recipe builds EJS from source; see
[the validation and remaining review work](automation-and-fdroid.md#f-droid-auto-update-configuration).

YouTube requests also include the public embedded player client alongside yt-dlp's defaults, because
some public Shorts ask the default clients to sign in while remaining embeddable.
This accepts an extra player API request for each YouTube extraction, including
links that already work with the default clients. It avoids restarting extraction
after a sign-in failure; playback remains native through Media3.

The build also assembles a second engine for photos and galleries from six
release-tag submodules: gallery-dl, requests, urllib3, idna, certifi and
charset-normalizer. Initialize submodules before any APK build. The versions,
source directories, licenses, tags and commits are recorded in
[`app/gallery-dl/sources.json`](../app/gallery-dl/sources.json).
`preparePinnedGalleryDl` stages their package files with Python 3, generates
urllib3's missing `_version.py`, and uses `PythonZipApp` to combine them with
`app/gallery-dl/__main__.py`. Entries are sorted with fixed DOS timestamps,
independent of the build timezone. Submodule files are never modified, and no
Python packaging backend or wheel download runs during the APK build.

The gallery-dl pin uses its [Codeberg upstream](https://codeberg.org/mikf/gallery-dl).
The package files at `v1.32.13` (`61070f0`) match the PyPI 1.32.13 wheel;
[GitHub's tag](https://github.com/mikf/gallery-dl/tree/v1.32.13) (`19a6403`)
points to different code. The updater's wheel comparison enforces this match.

Package data includes `certifi/cacert.pem`, typing markers and urllib3's
JavaScript helper. LICENSE/NOTICE files are stored under `licenses/<package>/`.
Compiled Python caches and charset-normalizer's optional `.pyx`/`.pxd` sources
are excluded, as is all wheel-only metadata (`METADATA`, `WHEEL`, entry points,
top-level lists and installation records). The old
`unfurlit.gallerydl.wheels` build property is no longer used.

CI runs `python3 scripts/check_gallery_dl.py` after building the engine. Only
this verification downloads the manifest's hash-pinned pure-Python wheels.
It compares every package file and license byte for byte, including generated
version data and certificates, and rejects missing, changed or additional files.
To verify with a pre-filled wheel directory, use:

```bash
./gradlew :app:preparePinnedGalleryDl
python3 scripts/check_gallery_dl.py --offline --wheels /path/to/verification-wheels
```

There is no scheduled gallery-dl updater. `update-yt-dlp.yml` and
`scripts/update_yt_dlp.py` cover yt-dlp, not gallery-dl. To change gallery-dl and
its dependencies, start from a clean checkout and supply all six stable release
tags to `scripts/update_gallery_dl.py`, including unchanged dependencies. For
example, these tags reproduce the initial source pins:

```bash
python3 scripts/update_gallery_dl.py \
  --gallery-dl v1.32.13 --requests v2.34.2 --urllib3 2.8.0 \
  --idna v3.20 --certifi 2026.07.22 --charset-normalizer 3.5.1
```

The script fetches those tags, resolves their commits and pure-Python wheel
hashes, checks out the submodules, updates the manifest, catalog and notice
versions, builds the engine and runs the byte comparison. It refuses a changed
immutable pin and rolls back pins/metadata and the previous engine if the build
or comparison fails. It does not patch submodule contents, stage or commit
changes, or advance the app version. Review the source/license changes and the
full diff, run the offline runtime and live-link checks, then commit the pins
with the manifest and notices. A changed package layout, generated-file format
or dependency set needs an explicit build review; do not bypass the comparison.

The engine runs as `libpython.so -S gallerydl.zip URL` on the Python runtime
youtubedl-android installs, with the same environment it uses for yt-dlp. Its
entry point runs gallery-dl's extractors without configuration files, cache, or
downloads and prints one JSON object with the media URLs, the request headers
their hosts expect (such as a Referer), and basic metadata. It is tried when
yt-dlp reports no video or fails to extract, is limited to 60 seconds and 50
items, and its output is validated like yt-dlp's. X/Twitter post URLs start
gallery-dl concurrently with yt-dlp to collect photos that yt-dlp omits. After
yt-dlp returns a video, the app waits at most five more seconds for photos, then
cancels the gallery lookup and keeps the video. Photos are appended without
duplicating videos or replacing their selected formats. A failed photo lookup
also keeps the video; an eligible yt-dlp failure reuses the same gallery lookup
for the normal fallback, with its original 60-second limit. Other sites retain
the sequential fallback. Both processes share a locked runtime installation,
use separate process environments, and disable disk caches; closing the request
cancels both lookups. For Reddit posts it first
loads `old.reddit.com` for the anonymous session cookie that Reddit's JSON pages
expect (as yt-dlp does); if Reddit still answers with its network-security block
page, it retries once through Reddit's OAuth API with gallery-dl's own client ID. Its pure Python logic is tested
by `scripts/tests/test_gallery_dl_entry.py`; `PythonRuntimeTest` also runs it on
the device runtime without network access.

Before the first extraction in each app process, Unfurlit verifies the app-private
extractor copy against the bundled checksum and atomically refreshes it when it
differs. This makes APK upgrades activate their newly pinned yt-dlp version
without clearing app data or viewing history.

The first extraction can take noticeably longer while the bundled Python runtime initializes. Network behavior is limited to the submitted source platform/CDN; there is no Unfurlit backend.

## History pagination and storage

History renders rows lazily, reads image blobs only for requested thumbnails, and
caches decoded artwork. Once the return transition fully hides History, it prepares
the list at the top for the next visit. A rapid reopen also requests the top before
layout instead of waiting for a scrolling coroutine after the first frame.
Data updates within the same visit and cancelled Back gestures keep its position.

History uses Paging 3 with 100 metadata rows initially, 50-row pages, a 15-row
prefetch distance, and a 250-row target window. Paging may temporarily exceed that
window while keeping pages needed by the viewport. Older pages are discarded and
queried again when scrolling back. Each database read uses LIMIT and indexed
(timestamp, ID) boundaries rather than OFFSET or a full-table materialization;
separate seeks for matching and older/newer timestamps support Android 7's SQLite
and timestamp ties. Thumbnail blobs remain outside page queries.

Date headers are inserted incrementally across page boundaries. Writes invalidate
the active source and refresh around the visible visit. Reopening reuses the cached
newest pages; only when those pages have been evicted does it request a fresh batch,
starting while offscreen when possible.
The same list presenter retains existing rows during that load, avoiding a blank
loading-screen flash. Errors expose Retry without discarding already shown rows. Tests traverse 10,000 synthetic visits in both directions, including
timestamp ties, and exercise page eviction/reloading, deletion, clear, insertion,
and reopening History. This bounds metadata work and retention; it is not a claim
of constant disk use or a measured maximum history capacity.

Each saved thumbnail is at most 48 KiB. Ten thousand thumbnails could therefore
occupy about 469 MiB before SQLite overhead; actual artwork is often smaller.
There is no automatic retention limit. SQLite reuses deleted pages, so clearing
history does not necessarily shrink the database file immediately. Any future
retention policy should be user-controlled rather than silently deleting visits.

## Tests and manual checks

The build command above runs Android unit tests and lint. Release-version tests
run separately with `python3 -m unittest discover -s scripts/tests`.

1. Install the debug APK and launch Unfurlit.
2. Paste a public URL, or share/open one from another app.
3. Wait for extraction to complete and verify that the native media viewer appears.
4. Confirm playback/seeking for video and audio, zoom/pan for images, swiping and the item indicator for galleries, and the new history event.
5. Repeat with public test cases for YouTube, Reddit, X, Instagram, and TikTok.
6. Capture whether each result is progressive, HLS/DASH, muxed, or split audio/video.
7. Record extraction time, playback errors, and the produced APK size when investigating compatibility changes.

Do not use private links, cookies, or credentials in committed test fixtures. Unfurlit's own success log records only the extractor name and media count. Failures emit a length-limited diagnostic with URLs and common secret fields redacted; direct media URLs, headers, cookies, and raw yt-dlp output are never deliberately logged.

### Pre-release phone checklist

Run the final pass on a physical ARM64 phone using the actual release APK, with
R8 code shrinking and optimization enabled. The
[link-check APK](#checking-links-by-hand) may be used for content checks beforehand,
but its `linkCheck` build type inherits from `debug` and is not minified or
optimized. It cannot verify behavior after release optimization. Copy this
reusable checklist into a release-specific test record under
[`docs/releases/`](releases/) and record the APK revision, test date and outcomes there.

- On a fresh release-build install, perform one extraction and record user data
  excluding cache and app size. Compare with the owner-reported 1.4.0 release
  baseline of **45.29 MB user data / 37.94 MB app size** after use; record the
  workload difference as well as the phone, Android version and APK hash.
- Update from the **1.4.0 release** without clearing data, extract a link to trigger
  runtime replacement, and confirm that saved History entries and thumbnails remain.
- Open `x-mixed-media` and confirm that both the video and the photo appear.
- Open `x-video` and confirm that it does not feel slower compared with the
  previous release.
- Open several X links in a row and watch for the app being killed or freezing.
  X posts briefly run two Python processes at once, so check on a lower-end ARM64
  phone if one is available.
- Open the [direct rotating-Earth GIF](https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif)
  used in the #45 live check and confirm that it animates in the viewer.
- Open the [Tumblr GIF-and-photo gallery](https://www.tumblr.com/k-eke/768588119781130240).
  Confirm that animation stops when swiping to the still image and restarts when
  swiping back. Its two assets were checked on 2026-10-08: one GIF with 760 frames
  and one still image. Recheck availability before the phone test.
- Pinch-zoom the GIF and confirm that it keeps animating.
- Return to History and confirm that the GIF's thumbnail is still.
- Open `tumblr-native-video` and confirm that its video plays with sound. Open
  `tumblr-mixed-media` and confirm that the video plays and the following photo loads.

## Architecture

The project intentionally has one Gradle app module. Package boundaries keep the replaceable pieces explicit:

```text
ui -> domain repository -> MediaExtractor -> yt-dlp adapter
 |
 +-> media viewer -> Media3 + OkHttp / Coil
```

`PlaybackSource` carries a URL, request headers, stream type, MIME type, format ID, and temporary scoped playback cookies. `ExtractedMedia.Video` can hold independent video and audio sources, while posts always expose a list of video, image, or audio entries. A separate history repository persists only a safe metadata projection after media is successfully displayed or starts playing. Runtime yt-dlp updating is not called; youtubedl-android `0.18.1` provides the Android/Python integration and yt-dlp `2026.08.19` is pinned separately as the extraction engine.

The app exports yt-dlp's scoped cookies separately from its HTTP headers and
uses an in-memory cookie jar per video/audio source. Cookie domain, path, secure
flags, and expiry are checked for each request, including redirects. History
stores display metadata and small thumbnail copies, never playback credentials.
Preserving these scoped cookies and decoding Python-quoted values fixed TikTok
media URLs returning HTTP 403 without requiring browser impersonation.

The image loader includes Coil's GIF decoders: `AnimatedImageDecoder` on API 28+
and `GifDecoder` on API 24–27. The viewer stops animation on inactive gallery
pages and when its lifecycle stops. History explicitly decodes a still first
frame before saving its JPEG thumbnail. `AnimatedImageTest` checks rendered
frame changes with both decoders, paging, zoom, lifecycle, and thumbnails using
authored fixtures without network access.

## Network safety

Unfurlit treats submitted URLs and extractor output as untrusted. Before extraction,
it upgrades HTTP inputs to HTTPS, follows a bounded redirect chain without
reading response bodies, rejects cleartext redirects and extracted media URLs, and
rejects any hop that targets localhost, a literal private address, or a hostname
whose DNS answer contains a non-public address. The same public-only DNS and
redirect policy is shared by Media3 and Coil, including manifest and image
requests. Sensitive and hop-by-hop headers are removed when a request crosses
origins, and extractor-provided connection, forwarding, host, length, and range
headers are ignored.

Extraction is cancellable and limited to 120 seconds. yt-dlp prints only the
metadata and selected-format fields Unfurlit consumes; short metadata is capped at
512 characters, descriptions at 16 KiB, the normalized output at 2 MiB, and
posts at 50 media entries. These controls reduce the attack surface, but they do
not turn arbitrary extraction into a sandbox: yt-dlp, gallery-dl and the bundled
Python runtime remain security-sensitive code that must be kept current. Like
yt-dlp, gallery-dl makes its own requests to the submitted site; the media URLs
it returns are loaded through the app's public-only HTTP stack.

## Release and licensing

Extractor updates are shipped through reviewed app releases. See
[release automation and F-Droid](automation-and-fdroid.md) for version numbering,
signing, source builds, and publishing.

The application is licensed under [GPL-3.0-only](../LICENSE). Keep the
[third-party notices](../THIRD_PARTY_NOTICES.md) current when changing dependencies.

[All documentation](README.md)

## Social-link regression pipeline

The **Live social links** workflow runs weekly and on manual dispatch, using the
same source-built extractor as release APKs. It exercises
`YtDlpMediaExtractor` on Android, including URL preflight, the bundled yt-dlp and
gallery-dl engines and JSON normalization, with native page-data adapters for TikTok,
Instagram and Pixiv photo posts, and Tumblr photos and native videos. The cases in
`app/src/socialLinks/assets/social-links.json` are grouped by media type:

- **Video:** YouTube (watch, youtu.be and Shorts links), Vimeo, Tumblr, Reddit (native video
  and external Imgur/Streamable link posts), X (including
  an animated GIF), Instagram posts, Reels and video carousels, TikTok, Bluesky, an Imgur
  GIFV, PeerTube, Dailymotion, a Twitch clip and a direct WebM file.
- **Photos and galleries:** Instagram and TikTok photo posts (with and without a
  soundtrack), Reddit image posts, galleries (explicit, comments, mobile share and
  `redd.it` links), direct `i.redd.it`, `preview.redd.it`,
  `reddit.com/media` and mirror links, X photos, Bluesky, Imgur images and albums, Flickr,
  Tumblr, Mastodon, Pixiv, Pinterest, Wikimedia Commons and a direct JPEG file.
- **Mixed media:** X and Tumblr posts with a photo and a video.
- **Audio:** SoundCloud, Bandcamp, Mixcloud and a direct Ogg file.
- **Error handling:** a non-media page, missing pages, an invalid scheme and a
  private address.

Public positive examples come from the pinned yt-dlp and gallery-dl extractor
test fixtures, YouTube sample videos and reported regression cases; they are
expectations, not a claim that each site currently permits anonymous access from CI.

Each link gets its own named JUnit result. `expected` is `success` or the exact
`ExtractionError` category a negative case must report. A successful case can also
require:

| Field | Meaning |
| --- | --- |
| `media` | `video`, `image` or `audio`, or a list of them. Every extracted item must be one of these kinds, and each listed kind must appear. |
| `count` / `minCount` | The exact or minimum number of extracted items. |
| `soundtrack` | `true` if a photo post must have a separate soundtrack, `false` if it must not. |

Login challenges, network failures and unexpected extraction errors fail positive cases
and do not count as successful negative tests. A failure names what was expected and
what was observed, for example `expected [success: 4 images] but observed
[success: 1 video (Progressive), from Twitter] (unexpected video; missing image; 1 item
instead of 4)`. The observation lists item kinds, stream formats, whether video audio
is a separate stream, soundtrack presence and the reporting extractor; it never contains
media URLs, headers or cookies. Each case also logs this observation and its extraction
time under the `SocialLinksTest` logcat tag.

After the tests, `scripts/social_links_report.py` writes a Markdown table of every
selected case, grouped by media type, to the workflow's job summary. It reports
**PASS**, **BLOCKED**, **LOCAL-ONLY**, **KNOWN**, and **FAIL** counts separately:

- **BLOCKED** requires `AuthenticationRequired` and the exact upstream message
  "Sign in to confirm you're not a bot" (including the curly-apostrophe spelling),
  "blocked by network security", "403 Blocked", or
  `[Reddit] <id>: Account authentication is required`. The last pattern requires
  the Reddit extractor prefix: the pinned yt-dlp raises it when an anonymous
  `.json` response is not JSON. The phrase alone, or a different extractor prefix,
  does not qualify. "Please sign in" is not a runner-block pattern; it is the
  YouTube regression signature from 1.3.0.
- **LOCAL-ONLY** applies to `youtube-shorts-sign-in-fallback` only when the report
  runs with `--runner`, as it does in the workflow. Its actual outcome is always
  reported and never fails the runner job, whether it passes or fails. Without
  `--runner`, this case is judged normally: "Please sign in" still fails the report.
  This regression case, and YouTube coverage in general, depend on the local
  pre-release run: runner results cannot establish that YouTube works.
- **KNOWN** requires an exact recorded failure signature in the report script;
  fixture expectations and JUnit assertions stay strict. The mapping is currently
  empty: X mixed media (#37), Tumblr photos (#38) and public Pixiv artwork (#39)
  are fixed and judged normally. A future exception must name its issue and exact
  signature; a passing case from that mapping is flagged for issue review.
- **FAIL** covers unexpected failures and selected cases without a completed
  result, except runner-only LOCAL-ONLY cases. BLOCKED, LOCAL-ONLY, and KNOWN
  do not count as passes.

The report determines the job result: unexpected failures, incomplete runs,
and test-run errors fail the job. The source build is also required to succeed.
After the full test pass, the workflow selects every completed `NetworkFailure`
or `Timeout` case for one extra pass. No other
outcome is retried, and the extra pass never schedules another retry. Raw reports
from the first pass are preserved before retrying.

A successful retry is shown as **PASS (retried)** and counts as PASS for gating.
The summary separately counts retried cases and retried passes, and retains the
original error. If the retry fails, its outcome is classified normally and both
errors are shown: repeated network failures or timeouts remain FAIL unless the
retry matches a KNOWN signature, or the case is LOCAL-ONLY
on the runner.
A different retry outcome can be BLOCKED, KNOWN, or FAIL; it never becomes a pass
without a successful test result. JSON includes both attempts for each retried case.
`Timeout` itself is never BLOCKED; a retry must return an approved block message
to receive that classification.

Reports, classified JSON, and logcat are uploaded even on failure, including
the app's redacted extraction messages. Review failures for site changes and
deleted fixtures before changing an expectation. No cookies or accounts are used.
This checks extraction; playback, seeking and image rendering still need the
manual viewer checks above.

To rerun selected cases, start the workflow manually with comma-separated case IDs in
**link_ids**. Against a connected x86_64 emulator:

```bash
./gradlew connectedDebugAndroidTest -Punfurlit.ci.x86_64=true \
  -Pandroid.testInstrumentationRunnerArguments.liveLinks=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest
python3 scripts/social_links_report.py
```

When selecting cases with the instrumentation `linkId` argument, pass the same
comma-separated IDs to the report's `--link-ids` option so unselected cases are
not mistaken for missing results.

Without `liveLinks=true`, these cases are skipped. The normal CI suite covers
error classification and the failure screen's recovery actions without contacting
social sites, and validates the fixture with `python3 scripts/social_links_report.py
--check` (through `scripts/tests`). To extend live coverage, add a public URL, a unique
lowercase hyphenated ID, `expected`, and where known the media kinds and count.

See the [live baseline](social-link-baseline.md) for observed passes and
compatibility failures, including the media-type results from CI.

### Checking links by hand

The `linkCheck` build type is a debug build installed as a separate app, **Unfurlit
Link Check** (`io.github.originalrecipe1.unfurlit.linkcheck`), with its own History.
When it starts, History lists every case from `social-links.json` in fixture order. Each
entry is titled with the case ID; the line above names its media group, and the line below
says what to expect, such as “Expect 16 images, with soundtrack” or, for an error case,
the failure screen's title.

Tap an entry to open its link, then check playback and seeking, zoom and swiping, the
item indicator and soundtrack, or the failure screen. A link that opens is recorded like
any visit and moves to the top with its real title and thumbnail, so the labelled entries
left below are the links still to check or that failed. Delete an entry to set it aside.
Clear all and restart the app for a fresh list; when the fixture changes, the next start
adds every case that isn't in History.

Each Android CI run uploads this app as the **unfurlit-link-check-apk** artifact, built
for ARM64 phones. CI signs it with a new debug key each time, so uninstall an earlier
link-check app before installing a newer CI build. To install it from a checkout:

```bash
./gradlew :app:installLinkCheck                            # ARM64 phone
./gradlew :app:installLinkCheck -Punfurlit.ci.x86_64=true  # x86_64 emulator
```

Unlike the live workflow, this checks what opens on your own device and connection,
including playback, which CI does not verify. The fixture and its parser live in
`app/src/socialLinks/`, shared by `SocialLinksTest` and this build.

TikTok `/photo/` links use the public post's page data to retain ordered images
and an optional shared soundtrack. The adapter requests TikTok's `/video/` page
for the same post ID, which exposes the photo data, through the existing public-only
HTTP client. Responses are limited to 4 MiB and 50 photos; parsed media URLs must
use public HTTPS targets. A soundtrack stays outside the gallery pager, loops,
and pauses when the app leaves the foreground. History counts photos, not the
soundtrack, and does not persist its playback URL.

To run just the photo-post live case, add
`-Pandroid.testInstrumentationRunnerArguments.linkId=tiktok-photos-with-audio`
to the live-test command above. This case requires three photos and a soundtrack,
so an audio-only extraction cannot pass.

Instagram `/p/` links first check the public post data for photos. The adapter
matches the requested shortcode, preserves all carousel items in order, and uses
safe image candidates rather than page thumbnails. Pure video posts fall through
to yt-dlp. Mixed carousels can retain direct video formats alongside photos.
The native photo adapters share a cancellable, bounded page loader with public-only
DNS and safe redirect handling. Instagram photo-post soundtracks are not supported;
the adapter makes no additional media-info request for audio. Instagram videos
retain their audio. TikTok photo soundtracks remain supported.

The Instagram photo case is `instagram-photo-carousel` and requires 11 photos.
`linkId` also accepts comma-separated IDs for focused regression runs.

Tumblr photos and native videos use the anonymous `www.tumblr.com/<blog>/<id>` permalink page.
Legacy blog `/post/` and `/image/` links and dashboard `/blog/view/` links are
read through that permalink; History keeps the original URL. The page GET uses
the same public DNS and redirect checks as URL preflight. The adapter reads only
the matching post from `___INITIAL_STATE___`, preserves reblog and row-layout
order, and prefers the largest uncropped images over avatars or still GIF posters.
Native `provider: tumblr` MP4 blocks play with any muxed audio; posters are
thumbnails, not gallery items. Mixed posts keep both photos and videos. Responses
are limited to 4 MiB, 50 reblog entries and 50 media items.
External video providers, audio and unrecognized layouts retain the existing
yt-dlp/gallery-dl route. Page-load errors and size-limit rejection also allow that
route, starting with preflight of the original request URL. Cancellation and
unsafe DNS, redirects or media targets remain terminal. Unsupported posts still
incur the optional page lookup (bounded by the HTTP client's 20-second call timeout).
No cookies, account credentials or Tumblr API key are needed for the native route.
Row and reblog order follow [Tumblr's NPF specification](https://github.com/tumblr/docs/blob/master/npf-spec.md).
The live fixtures require all four images in `tumblr-photo-post`, one playable
video in `tumblr-native-video`, and both kinds in `tumblr-mixed-media`.
None is exempted as a KNOWN failure in the report.

Pixiv illustrations and manga use the anonymous `/ajax/illust/<id>` web endpoint.
Multi-page artwork also requests `/ajax/illust/<id>/pages` and uses each original
image URL in API order, with Pixiv's Referer header. This avoids gallery-dl's
mobile API route, which always requires a refresh token. Artwork, `/en/artworks/`,
`/i/`, and legacy `member_illust.php?illust_id=…` links retain the original URL in
History. Profiles and other Pixiv paths keep the existing engine route.

Requests use the same public-only DNS, safe redirects, cancellable 20-second
network timeout and 4 MiB decompressed-response limit as the other native adapters.
The adapter checks the returned artwork ID and rejects missing pages, galleries
over 50 images, restricted artwork and non-original image URLs. Ugoira's timed
frame archives remain unsupported; their still preview is never reported as the
artwork. No cookies or credentials are sent. The `pixiv-artwork` fixture retains
its one-image expectation; a return to the refresh-token error now fails the live
report instead of being classified KNOWN (#39).
