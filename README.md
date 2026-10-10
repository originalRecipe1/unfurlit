# Unfurlit

Watch videos, view photos, and play audio from social links in a native Android viewer.
Paste a link or share it to Unfurlit to open its media without using the official app or the limited online website.

**Android 7.0+** · [Installation](docs/usage.md#installation) · [Privacy](PRIVACY.md) · [Documentation](docs/README.md)

| Open a link | Watch | Revisit | Dark mode |
| :---: | :---: | :---: | :---: |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Unfurlit home screen with a link field and Paste and Open buttons"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="A video playing in Unfurlit with its title and creator"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="History with a thumbnail, media details, and removal controls"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Unfurlit home screen in dark mode"> |

## Features

- Open public links from **YouTube, Vimeo, Instagram, TikTok, Reddit, X/Twitter, PeerTube, Bandcamp**, and more.
- Watch fullscreen videos and audio, zoom into photos, with gallery/carousel support.
- Revisit media in **local history**.

Media extraction runs **on your device**, with no account, backend or ads.
Site availability varies; [supported content and limitations](docs/usage.md#sites-and-access) explain what to expect.

## Supported links and media

- YouTube: videos and Shorts.
- Vimeo: videos, including page links and unlisted links with an access hash.
- Instagram: videos, Reels, photos and carousels (no photo-post soundtracks).
- TikTok: videos and photo posts, including available photo soundtracks.
- Reddit: videos, photos and galleries, including gallery posts opened from share,
  comments and `redd.it` short links.
- X/Twitter: videos, photos and galleries.
- PeerTube: videos.
- Bandcamp: audio tracks.
- Tumblr: native videos, photos and mixed photo/video posts.
- Pixiv: public illustrations and multi-page artwork (no Ugoira animations).
- Imgur, Bluesky, Flickr and many other sites: photos and galleries.

TikTok, Instagram, Tumblr and Pixiv photo posts use Unfurlit's own extractors (TikTok including
the post's soundtrack). Instagram falls back to the bundled
[gallery-dl](https://github.com/mikf/gallery-dl) engine if that fails, as do photos
and galleries on other sites. Direct image links also open. See the
[dated test results](docs/social-link-baseline.md) for the links and media checked.

Other sites may work too; audio and galleries depend on the source. Availability varies by post,
region and platform changes. Private, login-gated or restricted posts may not
open; signing in is not available.

## Site compatibility

On **2026-10-09**, the full local API 36 emulator run of the **1.4.0 release
candidate** passed **63/64 cases**, including 5 expected-error checks. X mixed
video/photo posts, Tumblr photos and videos, and public Pixiv artwork passed.
The only failure was an intermittent YouTube playback HTTP 403; the same APK
passed a separate retry. The first-pass count remains 63/64.

These checks used the debug-based link-check harness for extraction, image
rendering and short playback samples. Testing the optimized release APK on a
physical phone is still pending. See the
[dated results, failure triage and per-case outcomes](docs/social-link-baseline.md#140--2026-10-09)
for the environment and limits. Availability can change.

[Report a bug](https://github.com/originalRecipe1/unfurlit/issues) · [Build from source](docs/development.md#build) · [GPL-3.0-only](LICENSE)
