# Using Unfurlit

## Installation

Unfurlit runs on Android 7.0 or newer on 64-bit ARM devices. Release APKs include only ARM64 native libraries.
Install the APK attached to an **Unfurlit** release on
[GitHub Releases](https://github.com/originalRecipe1/unfurlit/releases). If no
Unfurlit release is listed yet, see the [build instructions](development.md#build).

Older **Peek** releases use `org.peek.app`. Unfurlit uses
`io.github.originalrecipe1.unfurlit`, so it installs separately and does not
transfer the old app's history. The older app and its data remain intact.

## Open media

Paste a public link into Unfurlit and tap **Open**, share a link to it from
another app, or select it from Android's **Open with** chooser where available.
Opening the app alone does not start loading media.

- Videos have playback and seeking controls, adapt to portrait or landscape
  content, and support fullscreen. Back exits fullscreen first.
- On Android 8.0 and later, a playing video continues in a picture-in-picture
  window when you leave the app or tap the picture-in-picture button. The window
  has a play/pause action; closing it pauses the video.
- Photos support pinch-to-zoom and panning.
- Audio has playback controls.
- Videos and audio keep playing in the background, for example with the screen
  off, with controls in the notification, on the lock screen, and on headsets.
  In the background a video plays only its sound. A photo post's soundtrack
  pauses when you leave the app, and swiping Unfurlit away from recents stops
  playback.
- Posts with multiple extracted media items appear in a swipeable gallery.

The first link can take longer while the bundled media extractor initializes.
Unfurlit streams media rather than saving a permanent copy of the video or audio.

## Sites and access

- YouTube: videos and Shorts.
- Instagram: videos, Reels, photos and carousels (no photo-post soundtracks).
- TikTok: videos and photo posts, including available photo soundtracks.
- Reddit and X/Twitter: videos, photos and galleries.
- PeerTube: videos.
- Imgur, Bluesky, Flickr and many other sites: photos and galleries.

Photos and galleries outside Instagram and TikTok come from the bundled
gallery-dl engine: when yt-dlp finds no video in a post, Unfurlit tries
gallery-dl, which supports image posts on hundreds of sites. Direct links to an
image also open. In a live run on 2026-09-25, 21 of 23 photo and gallery links
opened; see the [detailed results](social-link-baseline.md#media-types-on-the-ci-runner).
Pixiv needs a sign-in, so Pixiv links do not open.

Reddit posts shared from alternative front ends such as eddrit or Redlib, Reddit
"copy image link" links (`reddit.com/media?url=…`), and resized
`preview.redd.it` images are opened from the matching Reddit post or original
`i.redd.it` image, so extraction contacts Reddit rather than the front end. The
viewer and History keep the link you shared.

Other sites may work too; audio and galleries depend on the source. Availability varies by post,
region and platform changes. Private, login-gated or restricted posts may not
open; signing in is not available.

Tap **Supported links and media** on Home for this list in the app. See the
[compatibility results](experiment-results.md) for tested links.

Private, login-gated, age-restricted, or region-restricted posts may not open.
Importing login cookies, choosing quality manually, and saving media are not
currently available. TikTok photo posts support swiping and zooming through pictures while their
soundtrack plays, with audio controls below the gallery. The soundtrack loops and
pauses when you leave the app. Instagram photo posts and carousels also support swiping and zooming, but
separate photo-post soundtracks are not supported. Instagram videos retain their audio. Other image and gallery support
depends on what each site’s extractor provides. Unfurlit focuses on the media; comments and threads are
intentionally outside its scope.

## Appearance

Unfurlit follows the system light/dark setting. On Android 12 and newer it uses
your personalized Material colors. Android 13 and newer can also theme the
launcher icon.

## History

Swipe left within Home, or tap **History** on Home or the viewer, to see prior
viewing events. Swipe right in History to return; the pages follow your finger
with a card transition. Android's edge Back gesture also previews the return
and can be cancelled. A link typed on Home is retained while visiting History.
Opening an entry extracts
the original page again so stale stream URLs are never reused. Individual events can
be removed with their trash icon, and **Clear all** in the toolbar clears the
entire history after confirmation. Visits appear in date groups with small
thumbnails and media-type icons. Viewing a link again moves it to the top
instead of adding a duplicate, and only the 1,000 most recent visits are kept.

History is stored in the app's private SQLite database and is excluded from Android
backup and device transfer. A record contains the original page URL, basic display
metadata, media type/count, duration, and viewing time. Direct CDN URLs, request
headers, cookies, descriptions, and raw extractor output are not stored. Small
thumbnail copies are saved locally after viewing when available; older entries
and unavailable artwork use themed media icons. Thumbnails are removed with
their entries, and browsing History makes no network requests.

## Privacy and troubleshooting

Extraction runs on your device. Unfurlit has no project-operated backend,
analytics, or advertising. Opening media contacts the source platform and its
media hosts, so those services can still see your IP address and request data.
See the [privacy policy](../PRIVACY.md).

If a link fails, the viewer explains whether media could not be found, is
unavailable, may need sign-in, or could not be reached. Use **Try again** for a
connection or extraction failure, **Open link** to check the source in your
browser, or **Try another link** to return home. Engine version details are no
longer shown on the error screen. For persistent issues, include a public example
link and the app version from Android’s app settings in a
[bug report](https://github.com/originalRecipe1/unfurlit/issues). Do not post private
links, login cookies, or credentials. Report security concerns through the
[security policy](../SECURITY.md).

[All documentation](README.md)
