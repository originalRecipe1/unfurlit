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
the post's soundtrack). Photos and galleries on other sites use the bundled
[gallery-dl](https://codeberg.org/mikf/gallery-dl) engine. Instagram photos currently
rely on Unfurlit's own extractor: anonymous tests of the gallery-dl fallback hit
Instagram's login page. Direct image links also open. See the
[dated test results](docs/social-link-baseline.md) for the links and media checked.

Other sites may work too; audio and galleries depend on the source. Availability varies by post,
region and platform changes. Private, login-gated or restricted posts may not
open; signing in is not available.

## Site compatibility

On **2026-10-10**, **1.4.1 passed 64/64 extraction fixtures**, including five
expected-error checks. This emulator run checks extraction, not full playback
of every fixture. The **1.4.1 release-build phone test passed on a Pixel 7 /
Android 17**, including the required links and History thumbnails after restart.
See the [extraction baseline](docs/social-link-baseline.md#141--2026-10-10)
and [phone record](docs/releases/1.4.1-phone-test.md) for scope and limitations.

**Reddit — 2026-10-14:** Reddit currently blocks signed-out apps. Current development
builds show a Reddit-specific message with **Open link** for blocked posts.
Direct `i.redd.it` and `v.redd.it` media links still opened in observations on
2026-10-14; this is not a guarantee of continued availability. See the
[dated Reddit note](docs/social-link-baseline.md#reddit-signed-out-block--2026-10-14).

[Report a bug](https://github.com/originalRecipe1/unfurlit/issues) · [Build from source](docs/development.md#build) · [GPL-3.0-only](LICENSE)
