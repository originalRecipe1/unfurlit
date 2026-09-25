# Release automation and F-Droid

Unfurlit separates extractor updates from runtime behavior. The app never downloads
new executable code. Instead, GitHub Actions checks for a new stable yt-dlp
release every Monday at 04:23 UTC.

## Version numbering

The first public Unfurlit release is **1.0.0**, Android `versionCode` **7**.
The earlier `0.1.0-experiment.N` names are retired. Build 6 was an unreleased
preview under the old application ID. The new ID installs separately from
both that preview and published build 5; existing history is not transferred.

Use three-part release names:

- Patch: fixes and extractor updates (`1.0.0` → `1.0.1`).
- Minor: new features (`1.0.1` → `1.1.0`).
- Major: a major product or compatibility release (`1.1.0` → `2.0.0`).

Increment `versionCode` independently for every release; never reset it when
changing the major or minor version. Git tags use `v1.0.0`, APK assets use
`Unfurlit-1.0.0.apk`, and store release notes use the build code (`7.txt`).
Local side-by-side builds append `-preview`; this is not part of the release tag.

The extractor updater increments only the patch and build code and creates
release notes for that build. Its tests run in Android CI. Manually prepared
app releases also update the F-Droid submission candidate to the new version.

## Unfurlit rebrand submission

The existing [F-Droid merge request !47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809)
is open on `originalRecipe1/fdroiddata:org.peek.app`. Its current recipe and
successful pipeline still reference the Peek v5 release documented below.

The prepared replacement is [`fdroid/io.github.originalrecipe1.unfurlit.yml`](fdroid/io.github.originalrecipe1.unfurlit.yml).
It targets Unfurlit `1.0.1`, version code 8, and the new
`Unfurlit-%v.apk` release filename. Its new application ID is
`io.github.originalrecipe1.unfurlit`, and its repository and release URLs point to
`originalRecipe1/unfurlit`. The signing certificate, extractor version, and
source-build properties are retained.
The signed [1.0.0 release](https://github.com/originalRecipe1/unfurlit/releases/tag/v1.0.0)
is published. The previous candidate pinned its full commit,
`80f79dd1328775d57c19c99d6592f54e552e8440`, and passed local validation on
September 12, 2026; see [the validation record](fdroid/validation-1.0.0.md).
The updated candidate targets `v1.0.1`; the previous F-Droid validation does not
cover this release's ARM64-only packaging, R8, or trimmed Python runtime.
Official F-Droid acceptance and publication are still pending.

Store title, description, icon, and screenshots are imported from the release's
`fastlane/metadata/android/en-US` directory. Changing only `AutoName` would not
replace the old APK branding or its screenshots. See F-Droid's
[metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/) and
[graphics documentation](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).

## Weekly yt-dlp updates

`.github/workflows/update-yt-dlp.yml` performs the following steps:

1. Reads the latest stable release from the official `yt-dlp/yt-dlp` repository.
2. Downloads `yt-dlp` and `SHA2-256SUMS` from that release.
3. Rejects unexpected version formats, checksum mismatches, changed immutable
   releases, and version downgrades.
4. Updates the pinned engine version and checksum, advances the yt-dlp source
   submodule to the same release, and increments Unfurlit's literal `versionCode`
   and the patch component of `versionName`.
5. Runs unit tests, Android lint, and APK builds, then verifies both the official
   release asset and the locally source-built yt-dlp file embedded in debug APKs.
6. Opens a pull request for review. It never merges the update itself.

The workflow can also be run manually from the Actions tab. For its pull-request
step to work, enable **Settings → Actions → General → Workflow permissions →
Allow GitHub Actions to create and approve pull requests**. The workflow itself
still requests only `contents: write` and `pull-requests: write`.

After an automation pull request is merged, `release-tag.yml` rebuilds the merged
commit and creates a GitHub release and `v<versionName>` tag. It can also be run
manually to tag a normal app release. Release APKs use the same pinned submodule
and source-built yt-dlp path as the F-Droid recipe; the official prebuilt yt-dlp
asset is used only by normal development builds. A human merge is deliberately
the gate between an upstream extractor release and an app/F-Droid release.

### Signed and reproducible GitHub APKs

To attach an installable upstream APK and SHA-256 file to each GitHub release,
configure all four Actions secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_SIGNING_KEY_ALIAS`: key alias
- `ANDROID_SIGNING_STORE_PASSWORD`: keystore password
- `ANDROID_SIGNING_KEY_PASSWORD`: key password

The workflow builds yt-dlp from the pinned source submodule, passes that artifact
to Gradle, aligns the unsigned release APK, and signs/verifies it with Android
Build Tools 34.0.0. This mirrors the F-Droid source-only recipe so F-Droid can
compare its unsigned rebuild with the upstream-signed APK before copying the
signature. All four secrets are required; a missing or partial configuration
fails before a tag or GitHub release can be created.

Keep the original keystore and credentials backed up securely outside GitHub;
losing them prevents seamless upgrades of upstream-signed APKs. For a PKCS#12
keystore, use the same value for the store and key password secrets; JKS
keystores can use distinct passwords. Reproducible publication additionally
requires `Binaries` and `AllowedAPKSigningKeys` in F-Droid metadata and a verified
byte-for-byte match for a tagged release.

## F-Droid auto-update configuration

Official F-Droid metadata does not live in this repository. The existing
submission's `metadata/org.peek.app.yml` in `fdroiddata` uses the following
verified build block for the previous Peek v5 release:

```yaml
AntiFeatures:
  NonFreeNet:
    en-US: Connects to third-party social platforms and media CDNs
Categories:
  - Internet
License: GPL-3.0-only
AuthorName: originalRecipe1
SourceCode: https://github.com/originalRecipe1/peek
IssueTracker: https://github.com/originalRecipe1/peek/issues

AutoName: Peek

RepoType: git
Repo: https://github.com/originalRecipe1/peek.git
Binaries: https://github.com/originalRecipe1/peek/releases/download/v%v/Peek-%v.apk

Builds:
  - versionName: 0.1.0-experiment.5
    versionCode: 5
    commit: 737f6d09667fd774742933c6dbe792dc12fd22e8
    subdir: app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y make zip
    gradle:
      - yes
    build:
      - ../scripts/build_yt_dlp_from_source.sh
      - echo "peek.ytdlp.file=$PWD/../build/yt-dlp-source/yt-dlp" >> ../gradle.properties
      - echo "peek.ytdlp.sha256=$(sha256sum ../build/yt-dlp-source/yt-dlp | awk '{print
        $1}')" >> ../gradle.properties

AllowedAPKSigningKeys: 3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.]+)?$
UpdateCheckData: app/build.gradle.kts|versionCode\s*=\s*(\d+)||v(.*)
CurrentVersion: 0.1.0-experiment.5
CurrentVersionCode: 5
```

This recipe was normalized and linted successfully in the current official
`fdroiddata` configuration with the current `fdroidserver` container on
2026-09-04. Its full source scan reported zero problems, and its offline build
produced the expected unsigned APK with an embedded yt-dlp hash matching the
source-built file. A second build starting with an empty Gradle cache confirmed
that the declared repositories resolve the complete dependency graph; Android
SDK 36 was mounted because the standalone image only bundled an older platform.
The public `v0.1.0-experiment.5` tag resolves to the exact commit pinned above.
For version code 5, `fdroid build` downloaded the developer-signed GitHub APK,
successfully compared it with the F-Droid source rebuild, and accepted the
allowlisted signing-certificate fingerprint shown in the metadata.
Repeat these checks if the recipe changes before submission.

Store text, the app icon, and phone screenshots are maintained in the upstream
`fastlane/metadata/android/en-US` directory. F-Droid imports those assets from
the tagged app source rather than from `fdroiddata`.

F-Droid will then notice the release tags, update its build metadata, and queue a
new build. Publication is asynchronous and remains controlled by F-Droid.

The source-build script constructs yt-dlp from the pinned git submodule before
calling Gradle. The local-file Gradle path never downloads the release asset and
rejects checksum or version mismatches. The youtubedl-android runtime is resolved
from Maven Central, a trusted Maven repository; it contains the native Python and
QuickJS runtimes documented in `THIRD_PARTY_NOTICES.md`.

The repository is public, the first reviewed release is tagged, and the v5 block
was submitted to `fdroiddata` in merge request !47809. Do not claim that official F-Droid publication is
active until that merge request has been accepted. GitHub Actions cannot publish
directly into the official repository; F-Droid detects tags and controls its own
build and signing queue.

## gallery-dl image engine and F-Droid

Builds from 1.3.0 on also bundle the gallery-dl image engine, which
`preparePinnedGalleryDl` assembles from six pinned, pure-Python PyPI wheels
(gallery-dl and the requests stack). The F-Droid recipe above has not been
updated or validated for it yet. Before the next F-Droid release, decide whether
the build may fetch those wheels (they contain only Python sources and license
files, and each is checksum-verified), or provide them from `srclibs` in a
`prebuild` step and pass the directory with `-Punfurlit.gallerydl.wheels=...`.
Wheels rebuilt from source archives would not match the pinned checksums, so
that route would need a separate checksum override like the yt-dlp one.
