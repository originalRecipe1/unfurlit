# Reddit adapter: next-release phone checks

The adapter uses anonymous Reddit HTML sessions. It has no login, imported
browser cookies, WebView, or JSON extraction path. The `15ko0qu` fixture and its
mirror are unchanged pending the owner's browser check; their generic page
shell still falls through to the existing engines.

## Owner release-APK verification — NOT RUN

Record the final release APK revision, SHA-256, Android version, test date and
network here before marking this checklist complete. The automated extraction
and byte checks below use an isolated debug app; they do not establish release
playback, seeking, rendered gallery order or History behavior.

For **each row**, test Wi-Fi and mobile data with Wi-Fi off. Verify the correct
media is displayed; videos play and seek, with sound where the original has an
audio track. The `reddit-native-video` fixture itself has no separate audio
track. Compare gallery order with Reddit. Confirm History retains the shared
Reddit URL, post title and author rather than the discovered media/provider URL.

| Fixture | Expected | Wi-Fi owner check | Mobile owner check |
| --- | --- | --- | --- |
| reddit-video | 1 native video, audio | NOT RUN | NOT RUN |
| reddit-native-video | 1 native video | NOT RUN | NOT RUN |
| reddit-external-imgur | 1 Imgur video | NOT RUN | NOT RUN |
| reddit-external-streamable | 1 Streamable video | NOT RUN | NOT RUN |
| reddit-gallery | 3 images in order | NOT RUN | NOT RUN |
| reddit-gallery-comments | Same 3 images | NOT RUN | NOT RUN |
| reddit-gallery-share | 4 images in order | NOT RUN | NOT RUN |
| reddit-gallery-short | Same 3 images | NOT RUN | NOT RUN |
| reddit-image-earthporn | 1 image, EarthPorn post 7t0swm | NOT RUN | NOT RUN |
| reddit-image-pics | 1 image, pics post haucpf | NOT RUN | NOT RUN |
| reddit-image-post | 15ko0qu: compare with browser; record fallback/block | NOT RUN | NOT RUN |
| reddit-mirror-post | Same 15ko0qu post via normalized mirror link | NOT RUN | NOT RUN |

- Kill the app process, reopen it, and open a supported Reddit post. Confirm a
  fresh anonymous session succeeds without login; repeat on both networks.
- Open several supported Reddit links in one process. Confirm there is no login
  prompt or repeated-session delay on every link.
- On a genuinely blocked network/case, confirm the Reddit block message and
  **Open link** action still work. Do not label an unknown page as a challenge
  solely because its script mentions a challenge. The unit tests cover a
  persistent challenge and one rebuild; this UI check is NOT RUN.
- Reopen a successful entry from History and verify the post metadata and media.
- Confirm no regression in existing non-Reddit playback using the release's
  normal checklist. This PR leaves those routes and format settings unchanged.

## New single-image fixture provenance

Both are public, single-image posts found on their subreddits' all-time top
HTML listing, then checked on the Pixel through the production adapter:

- [EarthPorn / 7t0swm](https://www.reddit.com/r/EarthPorn/comments/7t0swm/so_glad_i_got_up_for_this_15f_sunrise_theres_only/).
- [pics / haucpf](https://www.reddit.com/r/pics/comments/haucpf/ive_found_a_few_funny_memories_during_lockdown/).

No response bodies, signed media URLs, cookies or credentials are committed. The JVM
parser resources are small synthetic, hand-trimmed SSR examples.

## Automated device checks — 2026-11-02

Device: Pixel 7, SDK 37. Debug APK built with the existing opt-in init script,
which installs `io.github.originalrecipe1.unfurlit.redditspike` beside the normal
app. `SocialLinksTest` invokes the production extraction route; it does not
start playback or write History. The two fixture byte tests assert the original
shared source URL, nonblank post title/author, one image, no playback cookie
header or cookie objects, a successful range GET and a valid image signature.
The derived-native test runs the parser's DASH fallback and GETs that manifest
through the safe client without cookies. HLS discovery was already verified in
the [transport spike](../reddit-transport-spike.md).

Final extraction run: **10 of 12 selected post cases succeeded on each network**.
The two unchanged `15ko0qu` cases reported `NetworkBlocked` through the existing
engine fallback, as before; their fixture expectations were not rewritten.
`SocialLinksTest` enumerates 75 fixtures, skips the other 63, and exits with two
assertion failures for those existing cases. Those failures are disclosed rather
than counted as passes. No supported-case failure remains in the final run.

| Fixture | Wi-Fi observation | Seconds | Mobile observation | Seconds |
| --- | --- | ---: | --- | ---: |
| reddit-video | success: 1 video (Hls, separate audio), from Reddit | 8.2 | success: 1 video (Hls, separate audio), from Reddit | 9.0 |
| reddit-native-video | success: 1 video (Hls), from Reddit | 7.1 | success: 1 video (Hls), from Reddit | 7.5 |
| reddit-image-earthporn | success: 1 image, from Reddit | 3.3 | success: 1 image, from Reddit | 2.9 |
| reddit-image-pics | success: 1 image, from Reddit | 4.1 | success: 1 image, from Reddit | 3.2 |
| reddit-image-post | NetworkBlocked | 9.9 | NetworkBlocked | 10.6 |
| reddit-gallery | success: 3 images, from Reddit | 3.7 | success: 3 images, from Reddit | 3.3 |
| reddit-gallery-comments | success: 3 images, from Reddit | 2.8 | success: 3 images, from Reddit | 3.0 |
| reddit-gallery-share | success: 4 images, from Reddit | 3.7 | success: 4 images, from Reddit | 3.7 |
| reddit-gallery-short | success: 3 images, from Reddit | 3.6 | success: 3 images, from Reddit | 3.5 |
| reddit-external-imgur | success: 1 video (Progressive), from Reddit | 7.8 | success: 1 video (Progressive), from Reddit | 6.9 |
| reddit-external-streamable | success: 1 video (Progressive), from Reddit | 6.1 | success: 1 video (Progressive), from Reddit | 6.0 |
| reddit-mirror-post | NetworkBlocked | 9.8 | NetworkBlocked | 10.6 |

The first Wi-Fi run exposed an external-preview player on the Imgur post. The
parser was corrected to hand off its original external `content-href`; a JVM
regression test covers that shape. The final Wi-Fi matrix above was rerun after
the fix, and the full cellular matrix used the corrected build.

Additional byte/manifest proof on both networks (two device tests, both passed):

| Check | Wi-Fi | Mobile |
| --- | --- | --- |
| EarthPorn single-image range GET | HTTP 206, valid image signature, 1 image | Same |
| pics single-image range GET | HTTP 206, valid image signature, 1 image | Same |
| Derived DASH manifest, no Reddit cookies | HTTP 200, DASH XML | Same |

The mobile script verified that the active default network was cellular before
starting either suite, with Wi-Fi disabled. Wi-Fi was restored after testing.
The test packages were removed afterward; the normal app was not replaced or
cleared. The media GETs above use no Reddit cookie header or cookie jar.

Local validation: **222 JVM tests passed**, including offline parser, session,
extractor and bounded-page-loader tests; **117 Python script tests passed**;
fixture validation passed with 75 cases; lint passed with 0 errors and the
existing 28 warnings; debug APKs and the minified release APK built successfully.
The live checks did not store or log raw page bodies, cookies or signed media
URLs. Full release playback and owner checklist items remain NOT RUN.


## Repeat the isolated debug checks

Build and install without replacing the normal app:

```bash
./gradlew -I scripts/reddit_transport_spike.init.gradle assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class io.github.originalrecipe1.unfurlit.data.extractor.reddit.RedditAdapterDeviceTest \
  -e redditAdapter true \
  io.github.originalrecipe1.unfurlit.redditspike.test/androidx.test.runner.AndroidJUnitRunner
```

The additional device tests are opt-in; normal test runs make no requests from
them. To run the post extraction matrix, use `SocialLinksTest` with
`liveLinks=true` and `linkId` selecting the twelve IDs above. The existing CI
fixture expectations and runner BLOCKED classification are unchanged; new image
fixtures use the same success expectation as other public links.


## PR review follow-up: targeted parsing and login walls — 2026-11-02

The parser now locates post-tag candidates with string searches, verifies the
requested opening tag/ID with the existing tag and attribute patterns, and
counts nested post boundaries to select just that element. Scripts, comments
and quoted attributes are skipped while locating boundaries. Only the selected
slice becomes Tag objects; challenge detection scans candidates, block phrases,
the requested ID and title without tokenizing the page. An offline regression
test surrounds each supported post shape with approximately 1 MiB of filler on
each side and checks unchanged results, quoted fake tags and nested boundaries.

A page-loader `AuthenticationRequired` now propagates immediately from the
Reddit extractor. A unit test verifies one page attempt, no session rebuild and
no engine fallback. This is separate from the ID-bearing `15ko0qu` shell, which
still deliberately falls through; no live login-wall fixture was added or run.
The README footnote now uses plain user wording and retains both documentation
links.

### Pixel 7 Wi-Fi: before/after extraction seconds

Before: the original adapter run at `17e7835`, recorded above. After: the same
12 selected cases, existing isolated debug package and Pixel 7 (SDK 37), with
Wi-Fi confirmed as the active default network. These are single full-extraction
samples, including preflight, network requests, session setup where needed and
engine time; they do not isolate parser CPU time or establish a statistical
benchmark. No mobile-data rerun was requested for this review follow-up.

| Fixture | Observation (unchanged) | Before seconds | After seconds |
| --- | --- | ---: | ---: |
| reddit-video | success: 1 video (Hls, separate audio), from Reddit | 8.2 | 7.8 |
| reddit-native-video | success: 1 video (Hls), from Reddit | 7.1 | 4.5 |
| reddit-image-earthporn | success: 1 image, from Reddit | 3.3 | 1.0 |
| reddit-image-pics | success: 1 image, from Reddit | 4.1 | 0.8 |
| reddit-image-post | NetworkBlocked | 9.9 | 9.6 |
| reddit-gallery | success: 3 images, from Reddit | 3.7 | 1.2 |
| reddit-gallery-comments | success: 3 images, from Reddit | 2.8 | 0.7 |
| reddit-gallery-share | success: 4 images, from Reddit | 3.7 | 1.0 |
| reddit-gallery-short | success: 3 images, from Reddit | 3.6 | 1.3 |
| reddit-external-imgur | success: 1 video (Progressive), from Reddit | 7.8 | 4.9 |
| reddit-external-streamable | success: 1 video (Progressive), from Reddit | 6.1 | 4.1 |
| reddit-mirror-post | NetworkBlocked | 9.8 | 9.6 |

All ten supported cases still pass; the two unchanged `15ko0qu` cases still
report NetworkBlocked through the existing fallback. The matrix again enumerated
75 cases, skipped 63, and reported those two existing assertion failures.
The six successful single-image/gallery cases changed from **2.8–4.1 s** to
**0.7–1.3 s** in these samples.

Validation: **224 JVM tests passed**, including all existing tests and the new
large-page and login-wall cases. Lint and both debug APK builds passed. The
normal installed app and History were left intact; temporary test packages were
removed after the run. The owner release-playback checklist above remains NOT RUN.
