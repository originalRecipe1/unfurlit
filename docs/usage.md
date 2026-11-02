# Using Unfurlit

## Installation

Unfurlit runs on Android 7.0 or newer on 64-bit ARM devices. Release APKs include only ARM64 native libraries.
Install it from either source:

- the APK attached to an **Unfurlit** release on
  [GitHub Releases](https://github.com/originalRecipe1/unfurlit/releases);
- [F-Droid](https://f-droid.org/packages/io.github.originalrecipe1.unfurlit/).

F-Droid rebuilds each release from source, checks that it matches the GitHub
APK, and then distributes the GitHub APK with its original signature. Both
sources therefore use the same signing key, so you can switch between them and
keep updating without uninstalling or losing History. Android only installs a
version at least as new as the one you have, so F-Droid offers an update only
once it has caught up with your GitHub version. F-Droid lists a release only
after its own build, so it can lag behind GitHub.

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
- Animated GIFs play on the active gallery page while the app is in the foreground.
  History thumbnails stay still.
- Audio has playback controls.
- Videos and audio keep playing in the background, for example with the screen
  off, with controls in the notification, on the lock screen, and on headsets.
  In the background a video plays only its sound. A photo post's soundtrack
  pauses when you leave the app, and swiping Unfurlit away from recents stops
  playback.
- Posts with multiple extracted media items appear in a swipeable gallery.

The first link can take longer while the bundled media extractor initializes.
Unfurlit streams media rather than saving a permanent copy of the video or audio.

### Open links directly in Unfurlit

Unfurlit can open post and video links tapped in other apps, such as YouTube
and Vimeo videos, X, Instagram, TikTok, Tumblr and Bluesky posts, Pixiv artwork
and Bandcamp tracks. Profiles, search and home pages keep opening in your
browser.

On Android 12 and later, Android doesn't let Unfurlit claim links
automatically:

1. On Home, tap **Open links in Unfurlit**, then **Open settings**.
2. Choose to open links **In the app** (some devices say **Open supported
   links**), then tap **Add link** and pick the sites you want.
3. Return to Unfurlit. The dialog closes once at least one site is on. If
   none is on, it stays open and shows **Not turned on yet**.

Reddit isn't in the list for now, because Reddit posts aren't working (see
the Reddit note below).

If a site's own app is installed, it may keep opening that site's links, and
Settings may not let Unfurlit take them. Turn that site's links off under
**Open by default** in the site app's settings, then turn them on for Unfurlit.

On Android 11 and earlier, Android asks which app to use. Choose Unfurlit; tap
**Always** to stop the question for that site.

Some apps open links in their own built-in browser, so Android never offers
Unfurlit. Use the app's option to open the link in another browser, or share
the link to Unfurlit instead.

**Open link** in the viewer still opens your browser for a site turned on for
Unfurlit.

## Sites and access

The [compatibility table](../README.md#compatibility) lists which sites and media
types the latest baseline covers.

TikTok, Instagram, Tumblr and Pixiv photo posts use Unfurlit's own extractors (TikTok including
the post's soundtrack). A Tumblr post with an embedded YouTube or Vimeo video opens
that video the same way as its YouTube or Vimeo link, in post order alongside any
Tumblr photos or videos; History keeps the Tumblr link. Embeds from other sites, such
as Instagram or Spotify, are left to the general engines as before. Photos and galleries on other sites use the bundled
gallery-dl engine. Instagram photos currently rely on Unfurlit's own extractor:
anonymous tests of the gallery-dl fallback hit Instagram's login page. Direct
image links also open. See the [dated test results](social-link-baseline.md) for the
links and media checked. Pixiv artwork uses its anonymous web data; no refresh
token is needed. Login-only artwork and Ugoira frame animations are not supported.

Reddit posts shared from alternative front ends such as eddrit or Redlib, Reddit
"copy image link" links (`reddit.com/media?url=…`), and resized
`preview.redd.it` images are opened from the matching Reddit post or original
`i.redd.it` image, so extraction contacts Reddit rather than the front end. The
viewer and History keep the link you shared.

Other sites may work too; audio and galleries depend on the source. Availability varies by post,
region and platform changes. Private, login-gated or restricted posts may not
open; signing in is not available.

Tap **Supported links and media** on Home for a summary in the app.
The current extraction results are in the
[1.4.2 baseline](social-link-baseline.md#142--2026-10-16).

**Reddit — 2026-10-14:** Reddit currently blocks signed-out apps.
1.4.2 and later show a Reddit-specific message with **Open link** for blocked posts.
Direct `i.redd.it` and `v.redd.it` media links still opened in observations on
2026-10-14; this is not a guarantee of continued availability. See the
[dated Reddit note](social-link-baseline.md#reddit-signed-out-block--2026-10-14).

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
instead of adding a duplicate. History loads in pages and keeps the 1,000 most
recent visits; recording a visit removes older entries and their thumbnails.

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
connection or extraction failure, **Open link** to check the source in the
link's default app or an app chooser that excludes Unfurlit, or **Try another
link** to return home. For persistent issues, include a public example
link and the app version from Android’s app settings in a
[bug report](https://github.com/originalRecipe1/unfurlit/issues). Do not post private
links, login cookies, or credentials. Report security concerns through the
[security policy](../SECURITY.md).

[All documentation](README.md)
