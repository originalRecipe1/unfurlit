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
release notes for that build. Its tests run in Android CI. Review the F-Droid
submission candidate separately before advancing it to a new app release.

## Unfurlit rebrand submission

The [F-Droid merge request !47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809)
proposes `metadata/io.github.originalrecipe1.unfurlit.yml` from the `org.peek.app`
branch of the `originalRecipe1/fdroiddata` fork. `org.peek.app` is the retained
branch name; the proposed metadata has not been merged into official `fdroiddata`.

The local candidate is [`fdroid/io.github.originalrecipe1.unfurlit.yml`](fdroid/io.github.originalrecipe1.unfurlit.yml).
It targets Unfurlit `1.4.0`, version code 13, pinned to tag `v1.4.0` at commit
`c2a1b5cacf4cf4e58e7eee3b14147d910d142b52`. The matching patch for MR !47809
is prepared locally and has not been posted. Review of the gallery-dl build
approach remains tracked in
[issue #40](https://github.com/originalRecipe1/unfurlit/issues/40).
The candidate retains the `Unfurlit-%v.apk` release filename. Its application ID is
`io.github.originalrecipe1.unfurlit`, and its repository and release URLs point to
`originalRecipe1/unfurlit`. The signing certificate, extractor version, and
source-build properties are retained.

Earlier local validation is recorded for [1.0.0](fdroid/validation-1.0.0.md)
and [1.0.1](fdroid/validation-1.0.1.md). The 1.3.1 unsigned APK comparison and
remaining scanner questions are recorded below.
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

After an automation pull request is merged, `release-tag.yml` rebuilds the merged
commit and creates a GitHub release and `v<versionName>` tag. It can also be run
manually to tag a normal app release. Release APKs use pinned yt-dlp and EJS
submodules, with the challenge solver built using Node.js 22 and its npm lockfile.
The exported EJS build directory substitutes integrity-pinned
`@rollup/wasm-node` 4.52.5 for native Rollup 4.52.5; the submodule stays unchanged.
PR CI first builds a debug APK using the official asset, then builds and packages
the source extractor into the APKs it verifies. Both solver files must match the
checksum-verified official asset byte for byte, in normal Android CI as well as
the updater workflow. Normal development builds use the
official prebuilt yt-dlp asset. A human merge is the
gate between an upstream extractor update and an app release. The local F-Droid
candidate installs Debian Node.js and npm for the same build script; reviewer
acceptance of its build dependencies is still pending.

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
upstream signature. The 1.3.1 candidate passed the tagged, signed release
comparison at `c76c789`, including the allowlisted signing-certificate check.
All four secrets are required; a missing or partial configuration
fails before a tag or GitHub release can be created.

Keep the original keystore and credentials backed up securely outside GitHub;
losing them prevents seamless upgrades of upstream-signed APKs. For a PKCS#12
keystore, use the same value for the store and key password secrets; JKS
keystores can use distinct passwords. Reproducible publication additionally
requires `Binaries` and `AllowedAPKSigningKeys` in F-Droid metadata and a verified
byte-for-byte match for a tagged release.

## F-Droid auto-update configuration

Official F-Droid metadata does not live in this repository. The proposed file is
`metadata/io.github.originalrecipe1.unfurlit.yml` on the fork's `org.peek.app`
branch, pending merge in !47809. The local Unfurlit candidate is
[`fdroid/io.github.originalrecipe1.unfurlit.yml`](fdroid/io.github.originalrecipe1.unfurlit.yml),
which targets `1.4.0` at commit `c2a1b5cacf4cf4e58e7eee3b14147d910d142b52`.
It retains the 1.3.1 build block and adds 1.4.0 / 13 with the same build steps,
including the six pinned PyPI wheels. Use that file for the full metadata.
The 1.4.0 candidate has not yet had a new F-Droid build or signed-APK comparison;
the successful 1.3.1 checks below are historical evidence.
Its `sudo` commands install `git make nodejs npm python3 tar zip` from Debian.
Its build block uses the current property names:

```yaml
build:
  - ../scripts/build_yt_dlp_from_source.sh
  - echo "unfurlit.ytdlp.file=$PWD/../build/yt-dlp-source/yt-dlp" >> ../gradle.properties
  - echo "unfurlit.ytdlp.sha256=$(sha256sum ../build/yt-dlp-source/yt-dlp | awk '{print $1}')" >> ../gradle.properties
```

The previous Peek v5 recipe, pinned to
`737f6d09667fd774742933c6dbe792dc12fd22e8`, was normalized and linted successfully
in the official `fdroiddata` configuration with the `fdroidserver` container on
2026-09-04. That source scan reported zero problems, and its offline build
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

Auto-update remains disabled locally with `AutoUpdateMode: None`. The original
1.3.0 build block had no Node.js or npm and could not build releases containing
the EJS solver. The current candidate retains 1.3.1's installation steps,
but EJS and gallery-dl dependency acceptance is still unresolved.
Restore `AutoUpdateMode: Version`
only after reviewers accept the build approaches, the candidate targets the
appropriate release tag, and it passes F-Droid validation (lint, scanner,
offline build, an empty-cache build, and the signed release comparison).
F-Droid can then update
its build metadata from release tags and queue new builds. Publication is
asynchronous and remains controlled by F-Droid.

The earlier 1.3.0 candidate on !47809 lacked `yt_dlp_ejs` and had the YouTube
regression fixed in 1.3.1. Playback checks are recorded separately in the
[social-link baseline](social-link-baseline.md).

### EJS dependency choice and 1.3.1 validation

Use `@rollup/wasm-node` 4.52.5 in the copied build tree. It avoids Rollup's
platform-specific native packages and produces the official solver bytes under
both Node.js 22 and Debian Node.js 20.19.2 / npm 9.2.0. The package's exact
SHA-512 integrity is pinned in `scripts/prepare_ejs_wasm.py`. The script changes
the copied manifest and lockfile only, retaining every unrelated dependency pin.
A direct npm dependency alias is used because npm 9.2.0 rejects the equivalent
`overrides` entry with `Invalid comparator`. `npm ci --omit=optional
--ignore-scripts --no-audit --no-fund` omits optional native dependencies and
does not run install scripts. The optional macOS `fsevents` entry remains in the
lockfile but is not installed.

WASM is still a **prebuilt build dependency**. Its file is
`node_modules/rollup/dist/wasm-node/bindings_wasm_bg.wasm`; it is not included in
the APK. This choice is proposed for review, not presented as scanner approval.
The Debian-only Rollup route is not viable with the upstream configuration:
`astring` and `meriyah` are not packaged, and replacing plugins would no longer
use the configuration whose byte identity has been verified.

On 2026-10-05, the candidate was tested in
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, the image specified
by [fdroiddata's CI](https://github.com/f-droid/fdroiddata/blob/9aa94c3af32e3470915da2267f1f12e3931dc230/.gitlab-ci.yml#L349).
The pulled image digest was
`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`.
Validation used fdroidserver commit `c21c177ff6d813697aaf9c988ca9fbb2b571b468`,
the matching fdroiddata configuration, Debian Node.js 20.19.2 / npm 9.2.0,
Java 21, and Android SDK 36.

- **Verified:** `fdroid lint` passed, and the candidate's source scan reported
  `0 problems found`.
- **Verified:** a separate scan of the installed EJS dependency tree reported
  `ERROR: Found WebAssembly binary file at node_modules/rollup/dist/wasm-node/bindings_wasm_bg.wasm`
  and `1 problems found`. The normal source scan runs before the build script
  downloads npm dependencies, so its zero count does not resolve this finding.
- **Verified:** `fdroid build --verbose --test --on-server --no-tarball
  io.github.originalrecipe1.unfurlit:12` built the unsigned APK from `f206f5f`
  using the candidate's exact build steps. Diffoscope 297 returned exit 0 against
  the unsigned APK from [CI run 37355691393](https://github.com/originalRecipe1/unfurlit/actions/runs/37355691393)
  at that same commit. Both entire APKs have SHA-256
  `4594cd0ce23ba3a6112a63f7ed5d8a5606f3d0ea43c78d1e899983d56b436d7e`.
- **Verified after publication on 2026-10-05:** the recipe was re-pinned to the
  `v1.3.1` tag at `c76c789` and the same `fdroid build` command exited 0. It
  downloaded the published APK, successfully verified the rebuilt APK using
  the upstream signature, and accepted the allowlisted signing certificate.
  This resolves the pre-publication `Binaries` HTTP 404. The unsigned rebuild
  SHA-256 is `07f3f08d749cb0915f6a0206c4955de9f4ada87ae96aba1d8cca167d6793ba72`;
  the published signed APK SHA-256 is
  `185d020bd664ffbe7c062c6e37f6970bb4747dc134dd0ad1ba5edb8566cc7ef4`.
- **Not verified:** a network-isolated offline build of this candidate and
  F-Droid dependency acceptance remain outstanding. Successful reproducibility
  does not resolve the scanner's prebuilt-dependency findings.

The full source-built yt-dlp hash is
`1d641a354c1f2cca803ad8c6d841f5ab65ffcd0eb99ce6b358fc08c7d309ae86`
in both the Node.js 22 build and the Debian build. The solver hashes match the
official yt-dlp 2026.08.19 release:

- `core.min.js`: `18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00`
- `lib.min.js`: `c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577`

The local-file Gradle path never downloads the release asset and
rejects checksum or version mismatches. The youtubedl-android runtime is resolved
from Maven Central, a trusted Maven repository; it contains the native Python and
QuickJS runtimes documented in `THIRD_PARTY_NOTICES.md`.

Merge request !47809 originally proposed Peek v5. The prepared update now targets
Unfurlit 1.4.0 at `c2a1b5cacf4cf4e58e7eee3b14147d910d142b52`; the matching
metadata patch has not been posted. The gallery-dl review is tracked in
[issue #40](https://github.com/originalRecipe1/unfurlit/issues/40).
Official F-Droid acceptance and publication remain pending. GitHub Actions cannot
publish directly into the official repository; F-Droid detects tags and controls
its own build and signing queue.

## gallery-dl image engine and F-Droid

Builds from 1.3.0 on also bundle the gallery-dl image engine, which
`preparePinnedGalleryDl` assembles from six pinned, pure-Python PyPI wheels
(gallery-dl and the requests stack). A reviewer on !47809 raised that these
wheels are downloaded during Gradle's `preBuild`, outside the F-Droid scanner.
The 1.4.0 candidate retains that build from 1.3.1. On 2026-10-05, all 428 `.py`
files in the six hash-verified wheels were compared byte for byte against the
matching upstream tags and hash-verified PyPI source distributions:

| Package | Version | Upstream tag | `.py` files | Tag differences | Sdist differences |
| --- | --- | --- | ---: | --- | --- |
| gallery-dl | 1.32.13 | `v1.32.13` | 346 | None | None |
| requests | 2.34.2 | `v2.34.2` | 19 | None | None |
| urllib3 | 2.8.0 | `2.8.0` | 36 | Generated `urllib3/_version.py` is absent from the tag | None |
| idna | 3.20 | `v3.20` | 10 | None | None |
| certifi | 2026.7.22 | `2026.07.22` | 5 | None | None |
| charset_normalizer | 3.5.1 | `3.5.1` | 12 | None | None |

**Verified:** every wheel `.py` file matches its sdist, including urllib3's
generated version file; there are no other missing or differing package `.py`
files. `certifi/cacert.pem` also matches both the tag and sdist. All six wheels
are pure Python and contain no native or WASM libraries. The chosen
charset_normalizer wheel uses its Python files, without the optional compiled
extensions or source `.pyx`/`.pxd` files.

**Not verified:** reviewer acceptance of downloading these wheels outside the
scanner. [PR #53](https://github.com/originalRecipe1/unfurlit/pull/53) implements
the source build and CI package comparison, but remains unmerged for 1.4.1.
It is not part of the 1.4.0 candidate.

## 32-bit ARM support

A reviewer on !47809 also raised that release builds support only arm64.
32-bit ARM is deferred beyond the first F-Droid release: reproducible per-ABI
APKs need matching per-ABI upstream releases and a new `versionCode` scheme.
The existing ABI filters, release assets, and version-code scheme remain in
place for the first release.
