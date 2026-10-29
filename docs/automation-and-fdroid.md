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
release notes for that build. Its tests run in Android CI. F-Droid picks up the
published release tag by itself; see
[F-Droid auto-update configuration](#f-droid-auto-update-configuration).

## F-Droid submission and publication

[MR !47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809) was
merged by linsui on 2026-10-14 as squash commit `aed2ca8f`. The official
[build log](https://monitor.f-droid.org/builds/log/io.github.originalrecipe1.unfurlit/14)
records a successful 1.4.1 build on 2026-10-16 and
`compared built binary to supplied reference binary successfully`.
[MR !53855](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53855),
"Update Unfurlit to 1.4.2, enable AutoUpdateMode", was merged on 2026-10-23 as
squash commit `167949b6c16e159ddbdd185b30145b6784eb7464`.

The [package page](https://f-droid.org/packages/io.github.originalrecipe1.unfurlit/)
is live. **Checked on 2026-10-23:** the
[F-Droid API](https://f-droid.org/api/v1/packages/io.github.originalrecipe1.unfurlit)
listed only 1.4.1 / 14, and the build log for version 15 returned HTTP 404, so
1.4.2 was not yet built or published by F-Droid. F-Droid's 1.4.1 APK is byte for
byte the GitHub `Unfurlit-1.4.1.apk` (SHA-256
`6b18c80ff099b4af9b21e21bb5ab8bd4e770a82b9033677161fbbe3f538ba8c9`), signed by
`3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c`.

**Checked on 2026-10-29:** the API listed 1.4.2 / 15 and 1.4.1 / 14. The
first automatic update worked: F-Droid's checkupdates bot found the `v1.4.3`
tag and committed `17896ac0` ("bot: Update Unfurlit to 16") to fdroiddata on
2026-10-29, adding 1.4.3 / 16. F-Droid had not yet built or published it.

The [canonical recipe](fdroid/io.github.originalrecipe1.unfurlit.yml) is a
byte-identical copy of fdroiddata's metadata at `17896ac0`. It contains three
builds, 1.4.1 / 14 at `c2ebe5e09f91a12457f1e73a42a3ed2a812a8fd2`, 1.4.2 / 15
at `c29faaec30adb4fd5988f0eda42ff86723b4cd95` and 1.4.3 / 16 at
`38581db1bff318785b50753963deb2c75be533fb`, sets `AutoUpdateMode: Version`,
and lists only the `Online Media Player` category. `org.peek.app` was the
historical submission branch name in the fdroiddata fork. Release 1.4.1 added
gallery-dl built from source (#53) and audited runtime trims (#58). See the
[1.4.1](fdroid/validation-1.4.1.md) and [1.4.2](fdroid/validation-1.4.2.md)
validation records.

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

Official metadata lives in fdroiddata; this repository keeps a byte-identical copy in
[`fdroid/io.github.originalrecipe1.unfurlit.yml`](fdroid/io.github.originalrecipe1.unfurlit.yml).
Since !53855 (merged 2026-10-23) it sets `AutoUpdateMode: Version`, so new
releases no longer need their own fdroiddata merge request. F-Droid controls its
own build and publication queue; GitHub Actions cannot publish directly into its index.

### How F-Droid finds a new release

This section describes `fdroidserver/checkupdates.py` at fdroidserver commit
`2d1e622fbbce7eec379453983435e5b14bea22b5`, read on 2026-10-23.

1. **Tags.** `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.]+)?$`
   makes checkupdates list the repository's tags, newest tagged commit first, and keep the
   ones the regex matches from the start of the name. It examines at most the
   five newest matching tags.
2. **versionCode and versionName.** For each of those tags it checks out the tag
   and applies `UpdateCheckData: app/build.gradle.kts|versionCode\s*=\s*(\d+)||v(.*)`.
   The versionCode is the first `versionCode = <number>` in `app/build.gradle.kts`
   at that tag. The third field is empty, so the versionName comes from the tag
   name: `v(.*)` turns `v1.4.3` into `1.4.3`. The tag with the highest versionCode
   wins.
3. **Current version.** If that versionCode is higher than `CurrentVersionCode`,
   `CurrentVersion` and `CurrentVersionCode` are set from the tag. A lower one is
   an error.
4. **New build block.** With `AutoUpdateMode: Version`, unless the last build
   block already has the new versionCode, checkupdates copies the last build
   block and sets its `versionName`, `versionCode` and `commit` from the tag; the
   tag is then resolved to its full commit hash when possible. Every other field
   (`subdir`, `submodules`, `sudo`, `gradle`, `build`) is copied unchanged, apart
   from a fixed list of obsolete OpenJDK `sudo` lines this recipe doesn't use.
5. **Merge request.** When run with `--merge-request`, it commits the metadata change
   on a branch named after the app ID and pushes it, which opens an fdroiddata
   merge request titled `bot: Update Unfurlit to <versionCode>`. It skips the app
   while that branch holds commits not made by the bot. When the bot runs and
   when its merge request is merged is up to F-Droid.

After the merge, F-Droid's build server builds the new block and compares the
result with the APK that `Binaries` points to, as for 1.4.1.

### Releasing

A release therefore needs only the `v<versionName>` tag from the existing
**Publish release tag** workflow (`release-tag.yml`). That workflow creates the
tag and the GitHub release with `Unfurlit-<versionName>.apk`, the file
`Binaries` points to. `versionCode` at the tag must be higher than the last
F-Droid build. Every tag the regex matches counts, including suffixed ones such
as `v1.5.0-rc.1`, so don't push matching tags for builds that shouldn't reach
F-Droid.

### When a manual fdroiddata merge request is still needed

Because the new block is a copy of the previous one, checkupdates can't carry
changes to:

- build steps (`build`, `gradle`, `subdir` or other build fields);
- `sudo` packages;
- submodules or how they are checked out;
- signing: `AllowedAPKSigningKeys`, or `Binaries` if the APK name or URL changes.

A release with such a change needs a manual merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata):

1. Fork fdroiddata on GitLab and branch from its current default branch.
2. In `metadata/io.github.originalrecipe1.unfurlit.yml`, add a build block for
   the new version with the changed fields and the release tag's full commit
   hash, and set `CurrentVersion` and `CurrentVersionCode` to that version.
   checkupdates then finds the last block already current and adds nothing; if
   the last block's versionCode were higher than `CurrentVersionCode`, it would
   fail. Later releases copy this new block.
3. Validate it in an fdroidserver buildserver with the same commands as the
   [1.4.2 validation record](fdroid/validation-1.4.2.md#build-setup-and-reproduction):
   the source scanner, a full `fdroid build --test --on-server --no-tarball --scan-binary`
   against the published APK, and the APK scanners.
4. Open the merge request and link the validation record. If the bot's merge
   request for the same version is already open, mention it so only one is merged.
5. After the merge, copy the merged file into this repository and check it with
   `cmp` against fdroiddata's raw file at the merge commit.

The recipe retains `Binaries` and `AllowedAPKSigningKeys`. Builds 14 and 15 use
source submodules and Gradle's gallery-dl preparation task, without gallery-dl
wheels. **Verified locally** (1.4.1 on 2026-10-12, 1.4.2 on 2026-10-17): both
builds passed the full build, source and APK scanners, and the signed comparison
with the published APK; see the
[1.4.1](fdroid/validation-1.4.1.md) and [1.4.2](fdroid/validation-1.4.2.md)
records. The official build reproduced the published 1.4.1 binary on 2026-10-16,
as linked above.

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
sudo dependencies. The canonical recipe has no wheel URLs, downloads or
local-wheel override. Gradle's `preparePinnedGalleryDl` task runs
`prepare_gallery_dl_sources.py` and consumes the staged files itself, so no
separate F-Droid `prebuild` staging step is needed. The 1.4.2 block keeps these
settings.

**Verified locally on 2026-10-09:** all 442 package and license files match the
six pinned wheels byte for byte, including all 428 `.py` files and the CA bundle.
An intentionally changed `requests/__init__.py` in a scratch archive fails the
comparison. CI enforces this through `scripts/check_gallery_dl.py`; the wheel
references are verification inputs only. The [development guide](development.md)
documents the explicit tag updater; no scheduled gallery-dl update job was added.
The 1.4.1 recipe was merged on 2026-10-14; !53855 enabled auto-update on 2026-10-23.

## 32-bit ARM support

A reviewer on !47809 also raised that release builds support only arm64.
32-bit ARM remains deferred: reproducible per-ABI
APKs need matching per-ABI upstream releases and a new `versionCode` scheme.
The existing ABI filters, release assets, and version-code scheme remain in
place.
