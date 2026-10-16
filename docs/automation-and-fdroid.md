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
release notes for that build. Its tests run in Android CI. Submit the corresponding F-Droid
metadata update separately after publishing a new app release.

## F-Droid submission and publication

[MR !47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809) was
merged by linsui on 2026-10-14 as squash commit `aed2ca8f`. The official
[build log](https://monitor.f-droid.org/builds/log/io.github.originalrecipe1.unfurlit/14)
records a successful 1.4.1 build on 2026-10-16 and
`compared built binary to supplied reference binary successfully`.
The package page returned HTTP 404 when checked on 2026-10-16; index publication
is not yet verified.

The [canonical recipe](fdroid/io.github.originalrecipe1.unfurlit.yml) is a
byte-identical copy of official fdroiddata metadata as checked on 2026-10-16.
It contains one build: 1.4.1 / 14 at
`c2ebe5e09f91a12457f1e73a42a3ed2a812a8fd2`, and only the
`Online Media Player` category. `org.peek.app` was the historical submission
branch name in the fdroiddata fork. The release includes gallery-dl built from
source (#53) and audited runtime trims (#58). See the
[local 1.4.1 validation record](fdroid/validation-1.4.1.md).

Store title, description, icon and screenshots come from the release's
`fastlane/metadata/android/en-US` directory. See F-Droid's
[metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/) and
[graphics documentation](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).

## Weekly yt-dlp updates

`.github/workflows/update-yt-dlp.yml` performs the following steps:

1. Reads the latest stable release from the official `yt-dlp/yt-dlp` repository.
2. Downloads `yt-dlp` and `SHA2-256SUMS` from that release.
3. Rejects unexpected version formats, checksum mismatches, changed immutable
   releases, and version downgrades.
4. Updates the pinned engine version and checksum, advances the yt-dlp source
   submodule to the same release and EJS to the version its Makefile requires,
   refreshes their third-party notices from the catalog and npm lockfile, and
   increments Unfurlit's literal `versionCode` and patch component of `versionName`.
5. Runs unit tests, Android lint, and APK builds, then verifies both the official
   release asset and the locally source-built yt-dlp file embedded in debug APKs.
   Both source-built EJS solver scripts must match the checksum-verified official
   release byte for byte; a moved EJS tag with different output fails the update.
6. Opens a pull request for review. It never merges the update itself.

The workflow can also be run manually from the Actions tab. For its pull-request
step to work, enable **Settings → Actions → General → Workflow permissions →
Allow GitHub Actions to create and approve pull requests**. The workflow itself
still requests only `contents: write` and `pull-requests: write`.

After an automation pull request is merged, `release-tag.yml` selects
`github.event.pull_request.merge_commit_sha` for checkout and the release tag
target, rebuilds that commit and creates a GitHub release and `v<versionName>`
tag. **Not verified against a real GitHub event:** the automatic merged-PR path
has only been checked with local selection tests. It can also be run
manually to tag a normal app release: enter the full CI- and phone-tested merge
SHA in `release_commit`. The workflow requires that SHA to be on the default
branch, checks it out, and uses it as the tag target even if `main` advances.
**Verified on 2026-10-12:** the manual path published `v1.4.1` from the exact
phone-tested merge commit in
[release run #38247646619](https://github.com/originalRecipe1/unfurlit/actions/runs/38247646619).
See the [1.4.1 handoff record](releases/1.4.1-phone-test.md#apk-handoff-and-publication).
Release APKs use pinned yt-dlp and EJS
submodules, with the challenge solver built using Node.js 22 and its npm lockfile.
The exported EJS build directory substitutes integrity-pinned
`@rollup/wasm-node` 4.52.5 for native Rollup 4.52.5; the submodule stays unchanged.
PR CI first builds a debug APK using the official asset, then builds and packages
the source extractor into the APKs it verifies. Both solver files must match the
checksum-verified official asset byte for byte, in normal Android CI as well as
the updater workflow. Normal development builds use the
official prebuilt yt-dlp asset. A human merge is the
gate between an upstream extractor update and an app release. The merged F-Droid
recipe installs Debian Node.js and npm for the same build script.

### Signed and reproducible GitHub APKs

To attach an installable upstream APK and SHA-256 file to each GitHub release,
configure all four Actions secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_SIGNING_KEY_ALIAS`: key alias
- `ANDROID_SIGNING_STORE_PASSWORD`: keystore password
- `ANDROID_SIGNING_KEY_PASSWORD`: key password

The workflow builds yt-dlp from the pinned source submodule, passes that artifact
to Gradle, aligns the unsigned release APK, and signs/verifies it with Android
Build Tools 34.0.0. F-Droid must reproduce the unsigned APK before it can copy the
upstream signature. The 1.4.1 recipe passed the tagged, signed release comparison
at `c2ebe5e`, including the allowlisted signing-certificate check; see the
[validation record](fdroid/validation-1.4.1.md).
All four secrets are required; a missing or partial configuration
fails before a tag or GitHub release can be created.

Keep the original keystore and credentials backed up securely outside GitHub;
losing them prevents seamless upgrades of upstream-signed APKs. For a PKCS#12
keystore, use the same value for the store and key password secrets; JKS
keystores can use distinct passwords. Reproducible publication additionally
requires `Binaries` and `AllowedAPKSigningKeys` in F-Droid metadata and a verified
byte-for-byte match for a tagged release.

## F-Droid auto-update configuration

Official metadata lives in fdroiddata; this repository keeps a matching copy in
[`fdroid/io.github.originalrecipe1.unfurlit.yml`](fdroid/io.github.originalrecipe1.unfurlit.yml).
`AutoUpdateMode: None` means each new version needs a new fdroiddata merge
request. Switching to `AutoUpdateMode: Version` is a possible future fdroiddata
change, not a change already made. F-Droid controls its own build and publication
queue; GitHub Actions cannot publish directly into its index.

The recipe retains `Binaries` and `AllowedAPKSigningKeys`. Build 14 uses source
submodules and Gradle's gallery-dl preparation task, without gallery-dl wheels.
**Verified locally on 2026-10-12:** the full build, source scanner and standalone
APK scanners exited 0. The unsigned rebuild matched merge-commit CI byte for byte;
after signature copying it matched the published v1.4.1 APK. Diffoscope exited 0.
See the [complete validation record](fdroid/validation-1.4.1.md).
The official build reproduced the published binary on 2026-10-16, as linked above.

### Historical validations

Peek v5 at `737f6d09667fd774742933c6dbe792dc12fd22e8` passed metadata lint,
source scanning, offline and empty-Gradle-cache builds on 2026-09-04.
Its F-Droid rebuild matched the signed upstream APK and accepted the allowlisted
certificate. SDK 36 supplemented the older platform in that validation image.

The 1.4.0 recipe at `c2a1b5cacf4cf4e58e7eee3b14147d910d142b52` reproduced the
published APK after signature copy on 2026-10-10; source and APK scanners exited 0.
The signed APK SHA-256 was
`7bbb9323f4c9acc367b0b987899c60b5e3ec8c4e5857359f6430ab74a13710b2`.
These older build blocks are not in the merged recipe.

### EJS dependency choice and 1.3.1 validation

EJS uses integrity-pinned `@rollup/wasm-node` 4.52.5 in a copied build tree,
leaving submodules unchanged. The WASM build dependency is not packaged in the
APK. The 2026-10-05 source scan passed, but a separate installed-dependency scan
reported that WASM file; the source scan precedes npm dependency downloads.
This historical distinction remains relevant when auditing build dependencies.

At `f206f5f`, the unsigned APK matched CI (diffoscope exit 0); the published
1.3.1 at `c76c789` subsequently passed signature-copy comparison and signer
allowlisting. Solver bytes matched the official yt-dlp 2026.08.19 release under
Node.js 22 and Debian Node.js 20.19.2 / npm 9.2.0. A network-isolated 1.3.1
build was not verified. Validation used fdroidserver
`c21c177ff6d813697aaf9c988ca9fbb2b571b468` and
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, image digest
`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`,
with Java 21 and Android SDK 36. These are historical local identifiers, not
claims about the official 2026-10-16 server configuration.

## gallery-dl image engine and F-Droid

Releases 1.3.0 through 1.4.0 assembled gallery-dl and its requests stack from
six pinned pure-Python wheels. On 2026-10-05 all 428 `.py` files matched their
source distributions; upstream tags also matched except for urllib3's generated
version file. The CA bundle matched too. The merged 1.4.1 recipe supersedes this
wheel-based build with pinned sources.

For 1.4.1, `preparePinnedGalleryDl` now assembles the same package files from
six release-tag submodules. It copies LICENSE/NOTICE files from source, preserves
package data, generates urllib3's `_version.py`, excludes charset-normalizer's
`.pyx`/`.pxd` and Python caches, and omits all wheel metadata. The deterministic
ZIP writer retains sorted entries and timezone-independent fixed timestamps.
No pip, Python build backend or wheel download is part of an APK build.

The recipe's existing `submodules: true` checks out all eight submodules (these
six plus yt-dlp and EJS) at their gitlink commits. Python 3 is already in its
sudo dependencies. The canonical 1.4.1 recipe has no wheel URLs, downloads or
local-wheel override. Gradle's `preparePinnedGalleryDl` task runs
`prepare_gallery_dl_sources.py` and consumes the staged files itself, so no
separate F-Droid `prebuild` staging step is needed. The recipe contains only
1.4.1 at the published merge commit.

**Verified locally on 2026-10-09:** all 442 package and license files match the
six pinned wheels byte for byte, including all 428 `.py` files and the CA bundle.
An intentionally changed `requests/__init__.py` in a scratch archive fails the
comparison. CI enforces this through `scripts/check_gallery_dl.py`; the wheel
references are verification inputs only. The [development guide](development.md)
documents the explicit tag updater; no scheduled gallery-dl update job was added.
The recipe was merged on 2026-10-14; auto-update remains disabled.

## 32-bit ARM support

A reviewer on !47809 also raised that release builds support only arm64.
32-bit ARM is deferred beyond the first F-Droid release: reproducible per-ABI
APKs need matching per-ABI upstream releases and a new `versionCode` scheme.
The existing ABI filters, release assets, and version-code scheme remain in
place for the first release.
