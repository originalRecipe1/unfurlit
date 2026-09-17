# Unfurlit

Watch videos, view photos, and play audio from social links in a native Android viewer.
Paste a link or share it to Unfurlit to open its media.

**Android 7.0+** · [Installation](docs/usage.md#installation) · [Privacy](PRIVACY.md) · [Documentation](docs/README.md)

| Open a link | Watch | Revisit | Dark mode |
| :---: | :---: | :---: | :---: |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Unfurlit home screen with a link field and Paste and Open buttons"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="A video playing in Unfurlit with its title and creator"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="History with a thumbnail, media details, and removal controls"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Unfurlit home screen in dark mode"> |

## Features

- Open public links from **YouTube, Instagram, TikTok, Reddit, X/Twitter, PeerTube**, and more.
- Watch fullscreen, seek through videos and audio, zoom into photos, and swipe through galleries.
- Revisit media in **local history**, with thumbnails and swipe navigation.

Media extraction runs **on your device**, with no account, backend or ads.
Site availability varies; [supported content and limitations](docs/usage.md#sites-and-access) explain what to expect.

## Site compatibility

**Last checked: 2026-09-17** · Two public links per site, without signing in.

| Results for tested links | Sites | Notes |
| --- | --- | --- |
| Working | Reddit, Instagram | Both links extracted successfully on each site. |
| Sometimes troublesome | YouTube, X/Twitter, TikTok | One of two links worked on each site. The other YouTube video was unavailable; X and TikTok had extraction failures. |
| Troublesome | Vimeo | Neither link worked: one required sign-in, the other was blocked by the site. |

These are extraction checks on an Android emulator, not full playback tests or
site-wide guarantees. Other sites, including PeerTube, were not checked in this
run. See the [detailed results](docs/social-link-baseline.md) and
[live test pipeline](docs/development.md#social-link-regression-pipeline).

[Report a bug](https://github.com/originalRecipe1/unfurlit/issues) · [Build from source](docs/development.md#build) · [GPL-3.0-only](LICENSE)
