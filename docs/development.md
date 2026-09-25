# Development

Build, test, and understand Unfurlit. For using the app, see the [user guide](usage.md).

## Build

Requirements:

- JDK 21 for the Gradle runtime (the app still targets Java 17 bytecode)
- Android SDK 36
- a 64-bit ARM device running Android 7.0+, or an emulator (see the CI build option below)
- Git, Python 3, Make, and Zip only when building the extractor from source

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

On this Fedora host, the API 36 emulator's SwiftShader renderer crashed before
Android finished booting. A cold boot using host graphics worked:

```bash
"$ANDROID_HOME/emulator/emulator" @unfurlit-review -no-window -no-audio -no-snapshot -gpu host -feature -Vulkan
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Punfurlit.ci.x86_64=true
```

Use the AVD name and emulator serial available on your machine. This is a local
workaround; CI retains its existing software renderer configuration.

Release builds use R8 code optimization and resource shrinking, including the
optimized resource shrinker for AGP 8.13. CI also builds the release APK so
shrinker failures are caught on pull requests. Validate changes to dependencies
or keep rules with playback in a signed release build; debug tests do not run
the optimized code. Keep `app/build/outputs/mapping/release/mapping.txt` with
each release to decode obfuscated crash traces. The release workflow attaches
this mapping file alongside the signed APK and checksum.

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

A local Pixel 7 sanity check (2026-09-18), using the same saved history,
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
builds, including x86_64 CI tests. Its exact removal list contains only the
static QuickJS build archive and seven CPython test extension modules. Retained
file contents, compressed payloads, Unix permissions, and symlink targets are
preserved. Gradle runs in UTC to keep rewritten ZIP headers reproducible. The
downloaded Maven artifact and its dependency metadata remain unchanged; a
runtime layout change fails the transform and requires review of the list.

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

For an offline/F-Droid-style source build, initialize the pinned submodule and
build the extractor first:

```bash
git submodule update --init
./scripts/build_yt_dlp_from_source.sh
source_file="$PWD/build/yt-dlp-source/yt-dlp"
source_sha="$(sha256sum "$source_file" | awk '{print $1}')"
./gradlew --offline --no-daemon assembleRelease \
  -Punfurlit.ytdlp.file="$source_file" \
  -Punfurlit.ytdlp.sha256="$source_sha"
```

This path performs no extractor download during Gradle execution. Gradle verifies
the supplied archive's checksum and embedded version before packaging it. The
source-built variant has also completed the YouTube streaming proof of concept
on the emulator.

The build also assembles a second engine for photos and galleries: gallery-dl
`1.32.13` and the pure-Python requests stack it needs (requests, urllib3, idna,
certifi, charset-normalizer). `preparePinnedGalleryDl` downloads the six pinned
wheels from PyPI, verifies each SHA-256, and combines them with
`app/gallery-dl/__main__.py` into a reproducible zip application (the
`PythonZipApp` build logic sorts entries, fixes timestamps and drops install-time
metadata). For offline builds, put the same wheel files in a directory and pass
`-Punfurlit.gallerydl.wheels=/path/to/wheels`; their checksums are still verified.

The engine runs as `libpython.so -S gallerydl.zip URL` on the Python runtime
youtubedl-android installs, with the same environment it uses for yt-dlp. Its
entry point runs gallery-dl's extractors without configuration files, cache, or
downloads and prints one JSON object with the media URLs, the request headers
their hosts expect (such as a Referer), and basic metadata. It is tried only when
yt-dlp reports no video or fails to extract, is limited to 60 seconds and 50
items, and its output is validated like yt-dlp's. For Reddit posts it first
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

The **Live social links** workflow runs weekly and on manual dispatch. It exercises
`YtDlpMediaExtractor` on Android, including URL preflight, the bundled yt-dlp and
gallery-dl engines and JSON normalization, with native page-data adapters for TikTok
and Instagram photo posts. The 55 cases in
`app/src/androidTest/assets/social-links.json` are grouped by media type:

- **Video:** YouTube (watch, youtu.be and Shorts links), Vimeo, Reddit, X (including
  an animated GIF), Instagram posts, Reels and video carousels, TikTok, Bluesky, an Imgur
  GIFV, PeerTube, Dailymotion, a Twitch clip and a direct MP4 file.
- **Photos and galleries:** Instagram and TikTok photo posts (with and without a
  soundtrack), Reddit image posts, galleries, direct `i.redd.it`, `preview.redd.it`,
  `reddit.com/media` and mirror links, X photos, Bluesky, Imgur images and albums, Flickr,
  Tumblr, Mastodon, Pixiv, Pinterest, Wikimedia Commons and a direct JPEG file.
- **Mixed media:** an X post with a photo and a video.
- **Audio:** SoundCloud, Bandcamp, Mixcloud and a direct Ogg file.
- **Error handling:** a non-media page, missing pages, an invalid scheme and a
  private address.

Public positive examples are seeded from the pinned yt-dlp and gallery-dl extractor
test fixtures and YouTube sample videos; they are expectations, not a claim that each
site currently permits anonymous access from CI.

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
case, grouped by media type, to the workflow's job summary, followed by the app's
redacted failure log line for each failed case. Reports and logcat are
uploaded even on failure. Review failures for site changes, deleted fixtures and CI
blocking before changing an expectation. No cookies or accounts are used. This checks
extraction; playback, seeking and image rendering still need the manual viewer checks
above.

To rerun selected cases, start the workflow manually with comma-separated case IDs in
**link_ids**. Against a connected x86_64 emulator:

```bash
./gradlew connectedDebugAndroidTest -Punfurlit.ci.x86_64=true \
  -Pandroid.testInstrumentationRunnerArguments.liveLinks=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest
python3 scripts/social_links_report.py
```

Without `liveLinks=true`, these cases are skipped. The normal CI suite covers
error classification and the failure screen's recovery actions without contacting
social sites, and validates the fixture with `python3 scripts/social_links_report.py
--check` (through `scripts/tests`). To extend live coverage, add a public URL, a unique
lowercase hyphenated ID, `expected`, and where known the media kinds and count.

See the [initial live baseline](social-link-baseline.md) for observed passes and
compatibility failures.

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
Both native adapters share a cancellable, bounded page loader with public-only
DNS and safe redirect handling. Instagram photo-post soundtracks are not supported;
the adapter makes no additional media-info request for audio. Instagram videos
retain their audio. TikTok photo soundtracks remain supported.

The Instagram photo case is `instagram-photo-carousel` and requires 11 photos.
`linkId` also accepts comma-separated IDs for focused regression runs.
