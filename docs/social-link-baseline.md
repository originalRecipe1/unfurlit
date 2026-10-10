# Social-link baseline

## 1.4.0 — 2026-10-09

**Verified: 63/64 cases passed; 1 failed.** This release-candidate run includes
58/59 media cases and 5/5 expected-error checks. All 64 fixtures ran once;
the separate successful retry below does not replace the first failure.
No fixture or expectation was changed for the release run.

- Source: `release/1.4.0`, tested commit
  [`3bb9201a1775824bbc5fe670f3b7215bf6f5cfe4`](https://github.com/originalRecipe1/unfurlit/tree/3bb9201a1775824bbc5fe670f3b7215bf6f5cfe4),
  based on main `ae422ec`, version name 1.4.0 / code 13. The subsequent release
  commit adds documentation only; application code and build configuration are unchanged.
- Fixture: all 64 entries in
  [social-links.json](../app/src/socialLinks/assets/social-links.json), including
  the two Tumblr video/mixed-media cases added since the 62-case 1.3.1 baseline.
- Environment: Android 16 / API 36 Google APIs x86_64 emulator, a residential
  connection, anonymous access. This was not a GitHub Actions run.
- Build: **debug-based `linkCheck`, without R8 optimization**, with local passive
  diagnostic observers. An attempt to instrument a release-derived minified build
  failed before tests started because AndroidJUnitRunner could not load shared
  AndroidX/Kotlin classes (`androidx.tracing.Trace`, then `kotlin.LazyKt`). Those
  harness failures are outside the 64-case result. No production keep rules changed.
- Engines: yt-dlp 2026.08.19 with EJS 0.8.0, and gallery-dl 1.32.13 assembled from
  the existing six pinned wheels. Both bundled engine resources are byte-identical
  to the published 1.3.1 APK. The gallery-dl source-build PR is excluded from 1.4.0.

Each case opens its fixture from History in a fresh app process. A media case
passes only when its expected types, count and specified soundtrack match and
every returned item renders or plays. Every gallery page is visited. Images must
decode and draw; video must render a frame and advance at least three seconds;
selected audio must advance. An expected-error case must show its specified error
screen. The harness records the first playback error and does not wait for the
app's automatic URL refresh, so a failed sample does not establish that recovery failed.

### Failure triage and before/after evidence

**Verified observation:** `youtube-big-buck-bunny` extracted one video with separate
audio, then playback returned `ERROR_CODE_IO_BAD_HTTP_STATUS` / HTTP 403 before
a frame or audio output started. A fresh-process retry with the same 1.4.0 APK
passed, as did a fresh 1.3.1 control on the same API 36 environment and connection.
The full-run count remains **63/64**.

**Classification: pre-existing intermittent playback failure; no regression identified.**
The same fixture produced a playback HTTP 403 on 2026-10-05 at `8ea5d43`, before
1.3.1, and passed an unchanged retry. Another YouTube video hit the same failure
in the earlier 50-attempt sample (1/50). The bundled engines and player HTTP-403
recovery code have not changed since 1.3.1. This evidence supports an intermittent
media-endpoint refusal, but its root cause, dependence on a particular connection,
and the outcome of automatic recovery in this failed sample are **not verified**.

The following six targeted controls used 1.3.1 `c76c789` with the same current
fixtures and debug-based viewer checks. These are separate checks, not a new full
1.3.1 baseline. All times below include navigation and rendering/playback checks.

| Case | 1.3.1 control | 1.4.0 full run | Separate 1.4.0 retry |
| --- | --- | --- | --- |
| `youtube-big-buck-bunny` | PASS: success: 1 video (Progressive, separate audio), from Youtube (38.2 s) | FAIL: Playback HTTP 403 (19.2 s) | PASS, 31.5 s |
| `x-mixed-media` | FAIL: Video only; photo missing (14.7 s) | PASS: success: 1 video, 1 image (Progressive), from Twitter (12.3 s) | Not run |
| `tumblr-photo-post` | FAIL: NetworkFailure (7.3 s) | PASS: success: 4 images, from Tumblr (5.4 s) | Not run |
| `tumblr-native-video` | FAIL: NetworkFailure (5.7 s) | PASS: success: 1 video (Progressive), from Tumblr (7.2 s) | Not run |
| `tumblr-mixed-media` | FAIL: NetworkFailure (8.8 s) | PASS: success: 1 video, 1 image (Progressive), from Tumblr (7.2 s) | Not run |
| `pixiv-artwork` | FAIL: AuthenticationRequired (15.4 s) | PASS: success: 1 image, from Pixiv (8.0 s) | Not run |

All other first-pass cases passed, including the previously failing X mixed-media,
Tumblr photo and Pixiv cases, and both Imgur cases that failed in the 1.3.1 full run.
The new Tumblr native-video and mixed-media cases also passed. The Imgur results
demonstrate current availability; no Imgur-specific fix is claimed.

**Not verified:** optimized release playback on a physical ARM64 phone, GIF
animation lifecycle in this sweep, audible output from physical speakers,
full-length playback, other regions/connections, or the install-over-1.3.1 upgrade.
The [1.4.0 phone record](releases/1.4.0-phone-test.md) remains blank for the signed,
optimized release APK test. A passing image decode is not an animation check.

### Per-case results

Times include navigation and viewer checks; they are not extraction benchmarks.

| Case | Expected | Result | Observed | Seconds |
| --- | --- | --- | --- | ---: |
| `youtube-video` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 29.6 |
| `youtube-short-link` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 22.1 |
| `youtube-big-buck-bunny` | success: 1 video | **FAIL** | success: 1 video (Progressive, separate audio), from Youtube; playback HTTP 403 before first frame/audio | 19.2 |
| `youtube-shorts` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 29.9 |
| `youtube-shorts-sign-in-fallback` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 23.5 |
| `vimeo-video` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Vimeo | 14.5 |
| `vimeo-player` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Vimeo | 14.2 |
| `vimeo-unlisted` | success: 1 video | **PASS** | success: 1 video (Hls), from Vimeo | 14.7 |
| `tumblr-native-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from Tumblr | 7.2 |
| `reddit-video` | success: 1 video | **PASS** | success: 1 video (Hls/Progressive, separate audio), from Reddit | 17.9 |
| `reddit-native-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from Reddit | 15.0 |
| `x-video` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Twitter | 11.8 |
| `x-second-video` | success: 1 video | **PASS** | success: 1 video (Hls), from Twitter | 13.3 |
| `x-animated-gif` | success: 1 video | **PASS** | success: 1 video (Progressive), from Twitter | 8.5 |
| `instagram-post` | success: 3 videos | **PASS** | success: 3 videos (Progressive), from Instagram | 20.1 |
| `instagram-reel` | success: 1 video | **PASS** | success: 1 video (Progressive), from Instagram | 12.5 |
| `instagram-video-carousel` | success: 2 videos | **PASS** | success: 2 videos (Progressive), from Instagram | 17.9 |
| `tiktok-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from TikTok | 11.3 |
| `tiktok-second-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from TikTok | 11.3 |
| `bluesky-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from Bluesky | 17.2 |
| `imgur-gifv` | success: 1 video | **PASS** | success: 1 video (Progressive), from Imgur | 17.4 |
| `peertube-video` | success: 1 video | **PASS** | success: 1 video (Hls), from PeerTube | 13.5 |
| `dailymotion-video` | success: 1 video | **PASS** | success: 1 video (Hls), from Dailymotion | 16.4 |
| `twitch-clip` | success: 1 video | **PASS** | success: 1 video (Progressive), from TwitchClips | 10.9 |
| `direct-video-file` | success: 1 video | **PASS** | success: 1 video (Progressive), from Generic | 9.6 |
| `instagram-photo-carousel` | success: 11 images | **PASS** | success: 11 images, from Instagram | 7.4 |
| `instagram-eight-photo-post` | success: 8 images | **PASS** | success: 8 images, from Instagram | 7.3 |
| `instagram-single-photo` | success: 1 image | **PASS** | success: 1 image, from Instagram | 4.7 |
| `tiktok-photos-with-audio` | success: 3 images, with soundtrack | **PASS** | success: 3 images, soundtrack, from TikTok | 9.9 |
| `tiktok-photo-link` | success: 16 images, with soundtrack | **PASS** | success: 16 images, soundtrack, from TikTok | 9.1 |
| `tiktok-single-photo` | success: 1 image | **PASS** | success: 1 image, soundtrack, from TikTok | 5.9 |
| `reddit-image-post` | success: 1 image | **PASS** | success: 1 image, from Reddit | 16.0 |
| `reddit-gallery` | success: 3 images | **PASS** | success: 3 images, from Reddit | 12.3 |
| `reddit-gallery-comments` | success: 3 images | **PASS** | success: 3 images, from Reddit | 20.1 |
| `reddit-gallery-share` | success: 4 images | **PASS** | success: 4 images, from Reddit | 18.2 |
| `reddit-gallery-short` | success: 3 images | **PASS** | success: 3 images, from Reddit | 18.7 |
| `reddit-external-imgur` | success: 1 video | **PASS** | success: 1 video (Progressive), from Imgur | 17.7 |
| `reddit-external-streamable` | success: 1 video | **PASS** | success: 1 video (Progressive), from Streamable | 16.6 |
| `reddit-direct-image` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.9 |
| `reddit-preview-image` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.6 |
| `reddit-image-link-wrapper` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.2 |
| `reddit-mirror-post` | success: 1 image | **PASS** | success: 1 image, from Reddit | 16.1 |
| `x-photo-post` | success: 4 images | **PASS** | success: 4 images, from X/Twitter | 10.9 |
| `x-mixed-media` | success: 2 video+image | **PASS** | success: 1 video, 1 image (Progressive), from Twitter | 12.3 |
| `bluesky-image` | success: 1 image | **PASS** | success: 1 image, from Bluesky | 12.1 |
| `imgur-image` | success: 1 image | **PASS** | success: 1 image, from Imgur | 9.2 |
| `imgur-album` | success: 19 images | **PASS** | success: 19 images, from Imgur | 13.6 |
| `flickr-photo` | success: 1 image | **PASS** | success: 1 image, from Flickr | 15.7 |
| `tumblr-photo-post` | success: 4 images | **PASS** | success: 4 images, from Tumblr | 5.4 |
| `tumblr-mixed-media` | success: 2 video+image | **PASS** | success: 1 video, 1 image (Progressive), from Tumblr | 7.2 |
| `mastodon-photos` | success: 4 images | **PASS** | success: 4 images, from Mastodon.social | 14.2 |
| `pixiv-artwork` | success: 1 image | **PASS** | success: 1 image, from Pixiv | 8.0 |
| `pinterest-pin` | success: 1 image | **PASS** | success: 1 image, from Pinterest | 9.5 |
| `wikimedia-commons-file` | success: 1 image | **PASS** | success: 1 image, from Wikimedia | 8.6 |
| `direct-image-file` | success: 1 image | **PASS** | success: 1 image, from Generic | 5.9 |
| `soundcloud-track` | success: 1 audio | **PASS** | success: 1 audio (Hls), from Soundcloud | 18.6 |
| `bandcamp-track` | success: 1 audio | **PASS** | success: 1 audio (Progressive), from Bandcamp | 24.1 |
| `mixcloud-show` | success: 1 audio | **PASS** | success: 1 audio (Dash), from Mixcloud | 14.9 |
| `direct-audio-file` | success: 1 audio | **PASS** | success: 1 audio (Progressive), from Generic | 9.8 |
| `non-media-page` | UnsupportedUrl | **PASS** | UnsupportedUrl | 10.0 |
| `missing-page` | MediaUnavailable | **PASS** | MediaUnavailable | 5.7 |
| `imgur-missing-image` | MediaUnavailable | **PASS** | MediaUnavailable | 6.8 |
| `invalid-scheme` | UnsupportedUrl | **PASS** | UnsupportedUrl | 2.1 |
| `private-address` | UnsupportedUrl | **PASS** | UnsupportedUrl | 2.1 |

## Tumblr native video follow-up — 2026-10-09

**Verified:** the native Tumblr adapter for [#49](https://github.com/originalRecipe1/unfurlit/issues/49)
plays the reported video through legacy, canonical and dashboard links, and
preserves both the video and photo in a mixed post. These targeted checks used
an API 36 x86_64 emulator with anonymous access and the diagnostic viewer checks
described below. The baseline is main `19d07c4`; engines remain yt-dlp 2026.08.19
and gallery-dl 1.32.13. The earlier **57/62** release result is unchanged.

Times below measure extraction only, once per URL and build. They are observations,
not a performance benchmark. Every passing video rendered a frame and advanced
at least three seconds; audio also advanced where present. Every image
decoded and drew on its gallery page.

| Case | Main | Native adapter | Verification |
| --- | --- | --- | --- |
| [Native video, legacy link](https://maskofthedragon.tumblr.com/post/626907179849564160/mona-talking-in-english) | NetworkFailure, 6.921 s | PASS, 1.151 s | Video + AAC audio |
| [Same video, canonical link](https://www.tumblr.com/maskofthedragon/626907179849564160) | Not run | PASS, 0.644 s | Video + AAC audio |
| [Same video, dashboard link](https://www.tumblr.com/blog/view/maskofthedragon/626907179849564160) | Not run | PASS, 1.085 s | Video + AAC audio |
| [Mixed video/photo post](https://mikf123.tumblr.com/post/167633596145) | NetworkFailure, 9.974 s | PASS, 1.489 s | Silent video then photo |
| [Four-photo post](https://mikf123.tumblr.com/post/172687798174/photo-post) | PASS, 1.307 s | PASS, 1.702 s | All four photos |
| [GIF-and-photo gallery](https://www.tumblr.com/k-eke/768588119781130240) | PASS, 1.324 s | PASS, 0.858 s | Both images |
| [External YouTube embed](https://prozdvoices.tumblr.com/post/673201091169681408/what-recording-voice-acting-sounds-like) | NetworkFailure, 9.706 s | NetworkFailure, 6.922 s | Pre-existing failure in yt-dlp's legacy-page request |
| [Old reblog](https://bartlebyshop.tumblr.com/post/180294460076/duality-of-bird) | AuthenticationRequired, 16.659 s | AuthenticationRequired, 10.594 s | No matching public post data; existing engines still fail |

The original video failure logs yt-dlp's `Remote end closed connection without
response` while requesting the legacy blog address. The native adapter instead
reads the matching post's MP4 from its public permalink data. External providers
still use the existing engines. The two new permanent fixtures,
`tumblr-native-video` and `tumblr-mixed-media`, require their media types and counts;
the live suite now has 64 cases and no new known-failure exemption.

**Verified:** invalid-scheme and private-address checks still report
`UnsupportedUrl`. Unit tests cover mixed/reblog order, original History URLs,
optional page-error and size-limit fallback, and terminal cancellation and
unsafe-target rejection.

**Not verified:** physical ARM64 playback, audible output, release-optimized
viewer behavior, a live oversized-post fallback, or a new full 64-case run.
The GIF control checks decoding and drawing; animation lifecycle was not re-tested.

## 1.3.1 — 2026-10-06

**Verified: 57/62 cases passed; 5 failed.** This includes
52/57 media cases and 5/5 expected-error checks. Every fixture ran once in the full run;
failed media expectations remain failures. No fixture was removed or changed
for this run.

- Source: [v1.3.1](https://github.com/originalRecipe1/unfurlit/tree/c76c789b78605bf7caa1da122f1761217c68b5e9),
  commit `c76c789b78605bf7caa1da122f1761217c68b5e9`, version code 12.
- Fixture: all 62 entries in the release's
  [social-links.json](https://github.com/originalRecipe1/unfurlit/blob/c76c789b78605bf7caa1da122f1761217c68b5e9/app/src/socialLinks/assets/social-links.json).
- Environment: API 36 Google APIs x86_64 emulator, a residential connection,
  anonymous access. This was not run on a GitHub Actions runner.
- Engines: yt-dlp 2026.08.19 with EJS 0.8.0, and gallery-dl 1.32.13. The test APK's
  two bundled engine resources were hash-checked against the published release.
- Method: a local diagnostic link-check build opens each fixture from History
  in a fresh app process. Observers record image decoding/drawing, player frames
  and audio progress; the diagnostic changes are not part of the app release or PR.

A media case passes only when its expected types, count and soundtrack (when
the case specifies one) match and its media renders or plays. Every returned
gallery item is visited for a passing case. Video must render a frame and advance at least three seconds;
selected audio must also advance. Images must decode and draw on the active
page. An expected-error case passes only when its specified error screen appears.

**Not verified:** complete playback of each track/video, audible output from
physical speakers, device-specific codec support, and availability from other
connections or regions. These are short emulator checks of individual links,
not site-wide guarantees or a substitute for physical-phone testing.

| Media | Passed | Failed |
| --- | ---: | ---: |
| Video | 25/26 | 1 |
| Photos and galleries | 23/26 | 3 |
| Audio | 4/4 | 0 |
| Mixed media | 0/1 | 1 |
| Error handling | 5/5 | 0 |

Failures observed in this run:

- `imgur-gifv`: returned an image (`format_id: "0"`) instead of the expected
  video, then failed to resolve `i.imgur.com` when loading that image. The DNS
  error explains the failed image load, not the wrong media type; its cause is
  not verified.
- `x-mixed-media` ([#37](https://github.com/originalRecipe1/unfurlit/issues/37)):
  the video played, but the expected second item, a photo, was missing.
- `imgur-image`: extracted an image URL, but its viewer failed to resolve `i.imgur.com`.
- `tumblr-photo-post` ([#38](https://github.com/originalRecipe1/unfurlit/issues/38)):
  `NetworkFailure`; yt-dlp reported that the remote end closed the connection
  without a response. None of the four expected images opened.
- `pixiv-artwork` ([#39](https://github.com/originalRecipe1/unfurlit/issues/39)):
  `AuthenticationRequired`; gallery-dl reported that a `refresh-token` is required.

**Verified separate retry:** both Imgur cases passed afterward with the same APK,
each in a fresh app process on a freshly started emulator. `imgur-gifv` extracted
and played one progressive video (`format_id: "twitter"`, 15.5 s total);
`imgur-image` decoded and drew one image (10.9 s). The full-run count stays
**57/62**. The 19-image Imgur album
and the Reddit post linking to an Imgur video also passed during the full run.
**Not verified:** why `imgur-gifv` returned the wrong media type in the full run,
and the cause of the temporary DNS lookup failures.

All five YouTube cases, all three Vimeo cases, all 13 Reddit cases, and all four
audio cases passed during the full run. Bandcamp selected the lossy `vorbis`
format and audio playback advanced successfully.

Per-case results follow. A **PASS** for media includes the rendering/playback
checks above; the observed column describes what extraction returned. Elapsed
times include History navigation and viewer checks, so they are not extraction
benchmarks.

| Case | Expected | Result | Observed | Seconds |
| --- | --- | --- | --- | ---: |
| `youtube-video` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 30.1 |
| `youtube-short-link` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 21.7 |
| `youtube-big-buck-bunny` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 19.8 |
| `youtube-shorts` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 31.7 |
| `youtube-shorts-sign-in-fallback` | success: 1 video | **PASS** | success: 1 video (Progressive, separate audio), from Youtube | 24.1 |
| `vimeo-video` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Vimeo | 14.9 |
| `vimeo-player` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Vimeo | 13.6 |
| `vimeo-unlisted` | success: 1 video | **PASS** | success: 1 video (Hls), from Vimeo | 13.6 |
| `reddit-video` | success: 1 video | **PASS** | success: 1 video (Hls/Progressive, separate audio), from Reddit | 16.4 |
| `reddit-native-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from Reddit | 17.0 |
| `x-video` | success: 1 video | **PASS** | success: 1 video (Hls, separate audio), from Twitter | 14.1 |
| `x-second-video` | success: 1 video | **PASS** | success: 1 video (Hls), from Twitter | 14.0 |
| `x-animated-gif` | success: 1 video | **PASS** | success: 1 video (Progressive), from Twitter | 8.8 |
| `instagram-post` | success: 3 videos | **PASS** | success: 3 videos (Progressive), from Instagram | 21.1 |
| `instagram-reel` | success: 1 video | **PASS** | success: 1 video (Progressive), from Instagram | 13.4 |
| `instagram-video-carousel` | success: 2 videos | **PASS** | success: 2 videos (Progressive), from Instagram | 17.2 |
| `tiktok-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from TikTok | 10.8 |
| `tiktok-second-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from TikTok | 10.3 |
| `bluesky-video` | success: 1 video | **PASS** | success: 1 video (Progressive), from Bluesky | 15.7 |
| `imgur-gifv` | success: 1 video | **FAIL** | success: 1 image, from Imgur; unexpected image; missing video; DNS lookup for i.imgur.com failed | 7.6 |
| `peertube-video` | success: 1 video | **PASS** | success: 1 video (Hls), from PeerTube | 11.1 |
| `dailymotion-video` | success: 1 video | **PASS** | success: 1 video (Hls), from Dailymotion | 15.0 |
| `twitch-clip` | success: 1 video | **PASS** | success: 1 video (Progressive), from TwitchClips | 10.8 |
| `direct-video-file` | success: 1 video | **PASS** | success: 1 video (Progressive), from Generic | 9.4 |
| `instagram-photo-carousel` | success: 11 images | **PASS** | success: 11 images, from Instagram | 7.7 |
| `instagram-eight-photo-post` | success: 8 images | **PASS** | success: 8 images, from Instagram | 6.6 |
| `instagram-single-photo` | success: 1 image | **PASS** | success: 1 image, from Instagram | 4.3 |
| `tiktok-photos-with-audio` | success: 3 images, with soundtrack | **PASS** | success: 3 images, soundtrack, from TikTok | 9.5 |
| `tiktok-photo-link` | success: 16 images, with soundtrack | **PASS** | success: 16 images, soundtrack, from TikTok | 9.2 |
| `tiktok-single-photo` | success: 1 image | **PASS** | success: 1 image, soundtrack, from TikTok | 7.5 |
| `reddit-image-post` | success: 1 image | **PASS** | success: 1 image, from Reddit | 15.3 |
| `reddit-gallery` | success: 3 images | **PASS** | success: 3 images, from Reddit | 12.2 |
| `reddit-gallery-comments` | success: 3 images | **PASS** | success: 3 images, from Reddit | 19.5 |
| `reddit-gallery-share` | success: 4 images | **PASS** | success: 4 images, from Reddit | 17.4 |
| `reddit-gallery-short` | success: 3 images | **PASS** | success: 3 images, from Reddit | 19.9 |
| `reddit-external-imgur` | success: 1 video | **PASS** | success: 1 video (Progressive), from Imgur | 18.0 |
| `reddit-external-streamable` | success: 1 video | **PASS** | success: 1 video (Progressive), from Streamable | 15.5 |
| `reddit-direct-image` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.4 |
| `reddit-preview-image` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.8 |
| `reddit-image-link-wrapper` | success: 1 image | **PASS** | success: 1 image, from Reddit | 11.7 |
| `reddit-mirror-post` | success: 1 image | **PASS** | success: 1 image, from Reddit | 15.1 |
| `x-photo-post` | success: 4 images | **PASS** | success: 4 images, from X/Twitter | 15.1 |
| `x-mixed-media` | success: 2 video+image | **FAIL** | success: 1 video (Progressive), from Twitter; missing image; 1 item instead of 2 | 12.5 |
| `bluesky-image` | success: 1 image | **PASS** | success: 1 image, from Bluesky | 11.9 |
| `imgur-image` | success: 1 image | **FAIL** | success: 1 image, from Imgur; DNS lookup for i.imgur.com failed | 8.8 |
| `imgur-album` | success: 19 images | **PASS** | success: 19 images, from Imgur | 13.9 |
| `flickr-photo` | success: 1 image | **PASS** | success: 1 image, from Flickr | 16.3 |
| `tumblr-photo-post` | success: 4 images | **FAIL** | NetworkFailure; wrong outcome | 8.4 |
| `mastodon-photos` | success: 4 images | **PASS** | success: 4 images, from Mastodon.social | 14.1 |
| `pixiv-artwork` | success: 1 image | **FAIL** | AuthenticationRequired; wrong outcome | 12.8 |
| `pinterest-pin` | success: 1 image | **PASS** | success: 1 image, from Pinterest | 10.4 |
| `wikimedia-commons-file` | success: 1 image | **PASS** | success: 1 image, from Wikimedia | 7.8 |
| `direct-image-file` | success: 1 image | **PASS** | success: 1 image, from Generic | 5.8 |
| `soundcloud-track` | success: 1 audio | **PASS** | success: 1 audio (Hls), from Soundcloud | 18.6 |
| `bandcamp-track` | success: 1 audio | **PASS** | success: 1 audio (Progressive), from Bandcamp | 23.6 |
| `mixcloud-show` | success: 1 audio | **PASS** | success: 1 audio (Dash), from Mixcloud | 15.4 |
| `direct-audio-file` | success: 1 audio | **PASS** | success: 1 audio (Progressive), from Generic | 9.2 |
| `non-media-page` | UnsupportedUrl | **PASS** | UnsupportedUrl | 10.0 |
| `missing-page` | MediaUnavailable | **PASS** | MediaUnavailable | 5.7 |
| `imgur-missing-image` | MediaUnavailable | **PASS** | MediaUnavailable | 7.0 |
| `invalid-scheme` | UnsupportedUrl | **PASS** | UnsupportedUrl | 2.1 |
| `private-address` | UnsupportedUrl | **PASS** | UnsupportedUrl | 2.1 |

### Imgur GIFV follow-up — 2026-10-06

**Verified:** five further local API 36 emulator attempts all extracted and
played the MP4 video, `format_id: "twitter"`. Full yt-dlp JSON, the JSON passed
to Unfurlit's parser, stderr and viewer results were saved for every attempt.
These used the same 1.3.1 source and release engine with extra local diagnostic
output enabled. They do not change the full-run **57/62** count.

| Attempt | Selected format | Parser result | Playback | Total seconds |
| --- | --- | --- | --- | ---: |
| 1 | `twitter` / MP4 | Video | **PASS** | 14.6 |
| 2 | `twitter` / MP4 | Video | **PASS** | 10.9 |
| 3 | `twitter` / MP4 | Video | **PASS** | 9.6 |
| 4 | `twitter` / MP4 | Video | **PASS** | 7.7 |
| 5 | `twitter` / MP4 | Video | **PASS** | 10.4 |

Each raw JSON dump also lists an unselected GIF format `0`. A separate capture
of Imgur's current API response identifies that asset as `type: "image"`,
`ext: "gif"`, with `is_animated: true`. In the pinned
[ImgurIE](https://github.com/yt-dlp/yt-dlp/blob/3a08beaf031ab68f966401ead017ac81fe8486cf/yt_dlp/extractor/imgur.py#L115),
`_real_extract` accepts animated images and adds this API format without a
format ID. Its `.gifv` webpage request is nonfatal; the webpage supplies the
`twitter` MP4 candidate. If that page provides no video, only the API GIF remains.
[yt-dlp's format processing](https://github.com/yt-dlp/yt-dlp/blob/3a08beaf031ab68f966401ead017ac81fe8486cf/yt_dlp/YoutubeDL.py#L3000)
assigns `"0"` to the first format without an ID.

**Verified controlled replay:** using the captured API response and simulating
an unavailable `.gifv` page produces a single GIF format `0` with Unfurlit's
release format selector. Restoring the captured page selects the MP4 `twitter`
format. This reproduces a path to the wrong media type, not the original failure.

**Verified parser behavior:** the unchanged release `YtDlpJsonParser` classifies
`.gif` as Image through `looksLikeImage()` before considering video codecs.
A local diagnostic test parsed all five live JSON outputs as Video and the
two replay outputs as Video (`twitter`) and Image (`0`), respectively. The
parser therefore supplies the Image classification; it does not invent format
`0` or turn an MP4 into an image in these checks.

**Not verified:** why the original full-run extraction lacked the MP4 candidate.
That attempt did not save raw JSON or yt-dlp warnings, so a failed `.gifv`
request or a different page response remains unconfirmed. The later viewer DNS
error alone does not establish the extraction cause.

Earlier dated runs are retained below as historical results.

## Initial local run — 2026-09-17

Initial local run: 2026-09-17, Android API 36 x86_64 emulator, bundled yt-dlp
2026.08.19, anonymous access. These results describe this environment and time;
they do not guarantee availability from GitHub-hosted runners or other regions.

| Case | Expected | Observed |
| --- | --- | --- |
| youtube-video | Success | Media unavailable |
| youtube-short-link | Success | Success |
| vimeo-video | Success | Sign-in required |
| vimeo-player | Success | TLS fingerprint blocked |
| reddit-video | Success | Success |
| reddit-native-video | Success | Success |
| x-video | Success | Extraction failed |
| x-second-video | Success | Success |
| instagram-post | Success | Success |
| instagram-reel | Success | Success |
| tiktok-video | Success | Extraction failed |
| tiktok-second-video | Success | Success |
| non-media-page | Unsupported URL | Unsupported URL |
| missing-page | Media unavailable | Media unavailable |
| invalid-scheme | Unsupported URL | Unsupported URL |
| private-address | Unsupported URL | Unsupported URL |

11 of 16 expectations passed: seven successful extractions and all four negative
cases. Five positive cases failed. They remain failures in the live workflow so
compatibility gaps stay visible; the deterministic pull-request checks are
independent. In particular, the older upstream YouTube test clip now reports
unavailable, and Vimeo requires sign-in or rejects the client's TLS fingerprint.
The TLS error also exposed an overly broad error classifier: a diagnostic mention
of “security/cookies” was interpreted as authentication. The classifier now checks
sign-in language instead, and has a deterministic regression test.

See [development](development.md#social-link-regression-pipeline) for the fixture,
workflow, commands and test limits. Successful extraction does not verify actual
playback. Keep real media URLs, cookies and raw engine output out of this document.

## TikTok photos with audio

Additional check on 2026-09-17: [the reported photo post](https://vm.tiktok.com/ZGdQaShC6/)
passed the new native photo adapter on the same API 36 emulator. It extracted
three ordered photos and a 37-second soundtrack. Manual viewer checks confirmed
image display, swiping to the second photo while the same audio player continued,
and pausing audio when the app entered the background. The live fixture asserts
three image entries and a separate soundtrack, so returning only audio fails.
The original 16-case results above were not rerun for this addition.

## Instagram photo carousel

Additional check on 2026-09-17: [the reported Instagram post](https://www.instagram.com/p/DXnKI92jWYQ/)
passed the native photo adapter with all 11 photos in order. Manual emulator checks
confirmed image display and swiping between slides. No soundtrack was exposed in
the anonymous post response; Instagram photo-post soundtracks are not supported.
The new live fixture asserts 11 image entries, so extracting only video items or
a thumbnail cannot pass. A focused regression run also passed both original
Instagram video/reel cases and the TikTok photo-with-audio case. All 60 unit tests,
Android lint, and the deterministic emulator tests passed.

## Additional Instagram photo carousel

On 2026-09-17, [this additional post](https://www.instagram.com/p/DSXSG7pjHPH/)
returned eight photos. Its live test requires all eight image entries.
Instagram photo-post soundtracks are intentionally unsupported; this does not
affect audio in Instagram videos or TikTok photo posts.

After removing experimental Instagram soundtrack extraction on 2026-09-17,
all five focused live cases passed again: both Instagram photo posts, the
Instagram video and reel, and TikTok photos with audio. All 61 unit tests,
Android lint, debug builds, and deterministic emulator tests also passed.

## Media types on the CI runner

On 2026-09-25 the fixture grew to 55 cases grouped by media type, and the
**Live social links** workflow ran all of them on a GitHub-hosted runner: API 30
x86_64 managed emulator, bundled yt-dlp 2026.08.19 and gallery-dl 1.32.13,
anonymous access. Sites block datacenter addresses more often than phones on home
connections, so this run understates what users can open. A second run of only
the 14 failed cases reproduced every failure with the same reason.

| Media | Passed | Worked |
| --- | --- | --- |
| Video | 11 of 22 | Instagram posts, Reels and video carousels; an X video and animated GIF; a TikTok video; Bluesky; Imgur GIFV; PeerTube; Dailymotion; a Twitch clip |
| Photos and galleries | 21 of 23 | All Instagram and TikTok photo posts; Reddit image posts, galleries, and direct, preview, `reddit.com/media` and mirror links; a four-photo X post; Bluesky; Imgur images and albums; Flickr; Mastodon; Pinterest; Wikimedia Commons; a direct JPEG |
| Audio | 4 of 4 | SoundCloud (HLS), Bandcamp (progressive), Mixcloud (DASH), a direct Ogg file |
| Mixed media | 0 of 1 | — |
| Error handling | 5 of 5 | All cases, including a missing Imgur image reported as unavailable |

Every TikTok photo post returned its soundtrack, including the single-photo post.
The X video, PeerTube and Dailymotion used HLS; the other passing videos, including the X
animated GIF, were progressive.

These cases failed, with the reason from the app's redacted extraction log:

| Case | Outcome | Reason |
| --- | --- | --- |
| youtube-video | Media unavailable | The upstream test clip is unavailable (dead fixture). |
| youtube-short-link, youtube-big-buck-bunny, youtube-shorts | Sign-in required | YouTube asked the runner to “confirm you’re not a bot”. Big Buck Bunny played locally on 2026-09-15. |
| vimeo-video | Sign-in required | Vimeo's web client requires an account. |
| vimeo-player | Extraction failed | Vimeo blocked the client's TLS fingerprint. |
| reddit-video, reddit-native-video | Sign-in required | Reddit blocked both engines (“blocked by network security”). Reddit photo posts still open through gallery-dl, but for a video post it only offers a `ytdl:` DASH manifest link, which the bundled entry point drops, so even its OAuth retry returns no media and Reddit's block is reported. |
| x-video | Extraction failed | The linked Amplify video no longer exists (dead fixture, also noted on 2026-09-04). |
| tiktok-video | Extraction failed | TikTok blocked the runner's IP address for this post. |
| tumblr-photo-post | Network failure | Tumblr closed yt-dlp's connection without a response. Network failures do not fall back to gallery-dl. |
| pixiv-artwork | Sign-in required | gallery-dl needs a Pixiv `refresh-token`, so anonymous Pixiv links cannot open. |
| x-mixed-media | Only the video | yt-dlp returned the post's video without its photo; gallery-dl is only tried when yt-dlp fails. |

The first direct-video case, a Blender download URL, returned HTTP 404 and is
not counted among the failures above. Its replacement, a Wikimedia Commons WebM
file, passed a focused run as one progressive video, so the current fixture
expects 12 of 22 video cases to pass from CI.

## YouTube public Shorts recovery — 2026-10-01

The reported public Short `x2zTi8aEjsA` reproduced `AuthenticationRequired`
(“Please sign in”) on the unchanged app in a local API 30 x86_64 emulator.
YouTube's public embedded player returned video and audio for the same link
without an account, so extraction now includes `web_embedded` alongside the
default clients.

The published 1.3.0 APK also omitted `yt_dlp_ejs`: the source-build target did
not include the JavaScript solver bundled in the official yt-dlp executable
used by development builds. Without it, the embedded-player path still failed.
Source builds now compile and package the pinned EJS sources and verify the
package and both solver scripts. The live workflow uses this same source-built
extractor, and the reported Short is a permanent success case.

With the fix, the reported Short, the original Shorts case, the short-link case,
and Big Buck Bunny all extracted video plus separate audio on the local Android
emulator. All four runtime tests passed, including the new solver packaging
check. Host requests for the reported Short's video and audio returned HTTP 206.
These checks establish extraction and stream access; physical-phone playback
has not been retested. The earlier GitHub runner bot challenges are a separate
access limitation, and remain failures if YouTube rejects every client.

Build review checks on the same date also confirmed that both source-built EJS
scripts match the checksum-verified official yt-dlp release byte for byte. A
deliberately altered script failed the update workflow's comparison. Forcing
`current-ejs-version` to fail its import caused upstream Make to download the EJS
wheel, after which the source-build script rejected the build. These checks used
Node.js 22; the F-Droid build steps have not been adapted or validated for EJS.

## Reddit gallery URL forms — 2026-10-05

Verified on the local API 30 x86_64 emulator, anonymously, with bundled yt-dlp
2026.08.19 and gallery-dl 1.32.13. The baseline is PR #29's initial `1dec86b`
build, which already routes explicit `/gallery/` links directly to gallery-dl.
The follow-up disables yt-dlp's GenericIE only for Reddit post URLs, after
preflight resolves share/short links and the Reddit gate handling selects the
extraction URL. Explicit galleries retain their direct route.

RedditIE's self-recursion guard misses a post's media URL `/gallery/<id>` without
a trailing slash. GenericIE redirects that URL back to the comments page and
restarts RedditIE. This was also reproduced on upstream master
`51bab8a0116f4d8004c315706d809782607d5847`. With `default,-generic`, the
unsupported gallery handoff terminates promptly and allows gallery-dl to run.

| Live fixture | Media | Before | After | Verification |
| --- | --- | --- | --- | --- |
| reddit-gallery-comments | 3 images, hrrh23 | Timeout, 120.0 s | Success, 14.0 s | Verified |
| reddit-gallery-share | 4 images, wjs1lb | Timeout, 122.5 s | Success, 14.8 s | Verified |
| reddit-gallery-short | 3 images, hrrh23 | Timeout, 121.0 s | Success, 15.7 s | Verified |
| reddit-gallery | 3 images, explicit gallery URL | Success, 6.9 s | Success, 7.7 s | Verified |
| reddit-video | 1 native video | Success, 14.0 s | Success, 11.3 s | Verified |
| reddit-native-video | 1 native video | Success, 9.1 s | Success, 8.4 s | Verified |
| reddit-image-post | 1 image | Success, 18.3 s | Success, 11.3 s | Verified |
| reddit-external-imgur | 1 external video | Success, 13.4 s | Success, 12.5 s | Verified |
| reddit-external-streamable | 1 external video | Success, 8.0 s | Success, 8.3 s | Verified |
| reddit-direct-image | 1 image | Success, 8.5 s | Success, 8.9 s | Verified |
| reddit-preview-image | 1 image | Success, 8.5 s | Success, 7.5 s | Verified |
| reddit-image-link-wrapper | 1 image | Success, 8.1 s | Success, 8.5 s | Verified |
| reddit-mirror-post | 1 image | Success, 18.2 s | Success, 11.3 s | Verified |

The mobile share URL `https://www.reddit.com/r/woodworking/s/TT8dsTXe9V`
resolves to the comments page for the four-image fox-lamp template gallery.
It was tested separately after adding it to the fixture; the other 12 cases ran
together. These are single-run elapsed extraction times, not a performance
benchmark. The three gallery URL variants and two external-video controls are
now permanent live fixtures and appear in the link-check APK's History.

A separate verbose audit using the pinned yt-dlp executable verified that both
native videos use RedditIE, and the external posts use RedditIE followed by
ImgurIE or StreamableIE. GenericIE was invoked for the old image-post, mirror
and direct-image cases, but failed to extract them; the app succeeds through
gallery-dl. No existing successful Reddit fixture depended on GenericIE for
media extraction. Direct image URLs still retain the default extractors.
An external host supported only by GenericIE can be affected by disabling it;
unlisted hosts have not been verified.

These checks verify extraction, media types and counts. Confirmed on a physical
ARM64 phone: the initial explicit-gallery fix. Playback and rendering with this
follow-up APK have not yet been verified on a physical device.
