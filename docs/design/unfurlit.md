# Unfurlit identity

Unfurlit is the selected name. The mark depicts a folded panel opening into a
media window, with a play-shaped cutout. It is original vector artwork.

## Color and geometry

The app continues to use `dynamicLightColorScheme` / `dynamicDarkColorScheme`
on Android 12+ and follows the system's light/dark setting. UI elements use
Material color roles; the wordmark's symbol is tinted with `primary`. The
existing fallback palette remains available for older Android versions.

The launcher provides separate adaptive foreground and monochrome vectors.
Its two-panel silhouette and negative-space cutout survive monochrome theming.
The artwork is scaled into the adaptive icon's central safe region. The blue
store/non-themed launcher palette is a fallback, not an override of the user's
personalized in-app or themed launcher colors.

The source store artwork is [`unfurlit-icon.svg`](unfurlit-icon.svg). Export it
without raster redrawing:

```sh
inkscape docs/design/unfurlit-icon.svg --export-type=png \
  --export-filename=fastlane/metadata/android/en-US/images/icon.png \
  --export-width=512 --export-height=512
```

Keep the Android foreground, monochrome, legacy launcher, and in-app vectors
consistent with that source when changing the silhouette.

## Compatibility

Unfurlit 1.0.0 uses `io.github.originalrecipe1.unfurlit` for both its application
ID and source namespace. This is a new installation, not an upgrade to
`org.peek.app`. Old installations and their history remain intact; there is no
history migration. Its private database is named `unfurlit-history.db`.

The signing identity and extractor build compatibility aliases remain stable.
The GitHub repository is now `originalRecipe1/unfurlit`. Existing media features
are retained. Home
supports animated Home/History paging and predictive Back; see
[`history-navigation.md`](history-navigation.md). The release remains version
1.0.0, build 7, since it has not been published.

## History presentation

History uses date sections and rounded cards with 80dp previews, a platform label,
title, creator, and compact media/time details. Back uses an arrow and each card has a direct remove icon. The toolbar
labels its bulk action “Clear all” and requires confirmation. Missing artwork uses a
Material-colored media icon. Card removal animates the remaining list.

Database version 2 adds a nullable thumbnail blob without modifying existing
visits. A new visit is recorded immediately, then its preview is fetched with
a five-second timeout using the existing network protections. Only a re-encoded
JPEG (at most 192px and 48KiB) is saved; remote URLs and headers are not stored.
Browsing History loads local bytes. Removing a visit also removes its thumbnail;
a pending download cannot recreate deleted entries. Existing visits retain their
fallback icons unless the media is opened again as a new visit.

## Remaining old-name references

Visible app and store branding, application/UI/theme class names, request
identification, build resource names, and current release artifacts use Unfurlit.
The following references intentionally retain the old spelling:

- `peek.ytdlp.*`: aliases accepted for older local build commands; current commands
  use `unfurlit.ytdlp.*`.
- `org.peek.app`: the existing GitLab submission branch, pending the manual
  metadata update described below.
- Historical release notes, compatibility explanations, and old test observations.

The existing GitLab MR title still needs the user's manual change to
**New app: Unfurlit**, alongside replacing the old metadata filename with
`io.github.originalrecipe1.unfurlit.yml` after the signed 1.0.0 release exists.

## Name research

On September 12, 2026, exact-name general web searches and searches scoped to
Google Play, Apple's App Store, F-Droid, GitLab, and Codeberg found no app named
Unfurlit. GitHub's repository-name search returned zero results. These are
preliminary discovery results and do not establish exclusive rights or prove
the absence of unindexed uses.

The earlier [`rebrand-concepts.html`](rebrand-concepts.html) remains an
exploration board, not a specification of app behavior or a release screenshot.

## Validation on September 12, 2026

- 44 Android unit tests, 5 release-updater tests, and 17 UI instrumentation tests passed; UI tests ran on an
  Android 16 / API 36 emulator. They cover full navigation, short/diagonal
  swipes, bidirectional paging, movement before release, retained typed input,
  button navigation, predictive Back cancellation/completion, and both edges.
  History tests cover row/remove actions, clear confirmation, empty state,
  database migration, thumbnail persistence, and deletion during preview loading.
- Debug APK, test APK, Android lint, and the offline source-extractor release
  build passed. Lint reports dependency-update notices; the monochrome icon
  warning has been resolved.
- The release APK reports `Unfurlit`, version code 7, application ID
  `io.github.originalrecipe1.unfurlit`. Its embedded extractor matches the locally source-built hash.
- Light and dark screens were inspected, and changing the emulator's
  personalized system palette changed the app's accent colors.
- A live YouTube Big Buck Bunny link played and created a history entry with
  a locally stored thumbnail. The History layout was checked in light/dark
  modes and at 150% font size.
  The four store screenshots show the actual app: light home, playback,
  history, and dark home. The existing film attribution is retained.
- With the new application ID, all 17 UI tests and live TikTok playback
  passed on the emulator. A preview build installed and launched on a physical
  ARM64 phone, but its initial media request timed out before extraction;
  playback under this ID was not confirmed.
- The prepared F-Droid recipe passed `fdroid lint` and `fdroid rewritemeta`
  using official F-Droid category/anti-feature definitions. Full F-Droid source
  scanning and signed-binary reproducibility verification await the new release.

The rebrand is prepared for review. No 1.0.0 release has been published and the
live F-Droid submission still references v5.
