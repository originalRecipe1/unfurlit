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

Since 1.0.0, Unfurlit uses `io.github.originalrecipe1.unfurlit` for both its application
ID and source namespace. This is a new installation, not an upgrade to
the old Peek app. Old installations and their history remain intact; there is no
history migration. Its private database is named `unfurlit-history.db`.

The signing identity and extractor build compatibility aliases remain stable.
The GitHub repository is now `originalRecipe1/unfurlit`. Existing media features
are retained. Home
supports animated Home/History paging and predictive Back; see
[`history-navigation.md`](history-navigation.md).

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
- `org.peek.app`: the historical fdroiddata fork branch name used for submission.
- Historical release notes, compatibility explanations, and old test observations.

## Name research

On September 12, 2026, exact-name general web searches and searches scoped to
Google Play, Apple's App Store, F-Droid, GitLab, and Codeberg found no app named
Unfurlit. GitHub's repository-name search returned zero results. These are
preliminary discovery results and do not establish exclusive rights or prove
the absence of unindexed uses.
