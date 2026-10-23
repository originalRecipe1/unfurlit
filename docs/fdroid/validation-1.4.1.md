# Unfurlit 1.4.1 F-Droid validation

**VERIFIED locally on 2026-10-12 against the published release.** The build used
the then-canonical recipe (hash recorded below), with
`Binaries`, `AllowedAPKSigningKeys`, all three historical/current build blocks
and the recipe's `sudo` commands retained. No release source or binary was changed.

| Item | Value |
| --- | --- |
| Application ID | `io.github.originalrecipe1.unfurlit` |
| Version / code | `1.4.1` / `14` |
| Tag / full commit | `v1.4.1` / `c2ebe5e09f91a12457f1e73a42a3ed2a812a8fd2` |
| Release | [Unfurlit 1.4.1](https://github.com/originalRecipe1/unfurlit/releases/tag/v1.4.1) |
| Android CI artifact | [Run #38123985638](https://github.com/originalRecipe1/unfurlit/actions/runs/38123985638), `unfurlit-unsigned-release-apk` |
| Recipe SHA-256 tested on 2026-10-12 | `f1bd7d942db63a55669213ca21ffc05b2cd2f9f31209a2cfc6ab0a002c5a6989` |
| Unsigned F-Droid rebuild / CI APK SHA-256 | `2c14e108e003d6a14c38dfd4099276d9f264b47aeae7625eed17426cce8ba7de` |
| Published / signature-copied APK SHA-256 | `6b18c80ff099b4af9b21e21bb5ab8bd4e770a82b9033677161fbbe3f538ba8c9` |
| Signing certificate SHA-256 | `3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c` |

## Published APK and CI comparison

**VERIFIED:** the tag resolves to the full commit above. The published release
contains `Unfurlit-1.4.1.apk`, `Unfurlit-1.4.1.apk.sha256` and
`Unfurlit-1.4.1-mapping.txt`. The downloaded APK matches its checksum and GitHub
asset digest. `apksigner verify --verbose --print-certs` and `zipalign -c -v 4`
both exited 0; the signer matches the recipe's allowlist.

F-Droid's `apksigcopier.copy_apk` removed the APK signing block and signature
entries from copies of the published and CI APKs. Both resulting files were
byte-identical to the original unsigned CI artifact, with the unsigned hash
above; all 235 original ZIP entries also had identical contents. This was an
APK comparison, not a locally rebuilt substitute for the CI artifact.
`fdroidserver.common.verify_apks` then copied the release signature onto the CI
APK and verified it with `apksigner`; the resulting complete file was
byte-identical to the published APK.

## F-Droid results

| Check | Exit code | Result |
| --- | ---: | --- |
| Standalone source scanner with `--exit-code` | 0 | VERIFIED |
| Full `fdroid build`, including `--scan-binary`, published-binary comparison and allowed signer | 0 | VERIFIED |
| Standalone APK scanner: unsigned F-Droid rebuild | 0 | VERIFIED |
| Standalone APK scanner: published APK | 0 | VERIFIED |
| `cmp`: complete unsigned F-Droid rebuild versus merge-commit CI artifact | 0 | VERIFIED |
| Retained signature-copy verification and complete-file byte comparison | 0 | VERIFIED |
| `diffoscope`: signature-copied rebuild versus published APK | 0 | VERIFIED; no differences |
| Published-versus-CI signature-stripped and signature-copy comparison | 0 | VERIFIED |

The full build fetched the published `Unfurlit-1.4.1.apk` through `Binaries`.
Its final output included:

```text
...successfully verified
compared built binary to supplied reference binary successfully
supplied reference binary has allowed signer 3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c
success: io.github.originalrecipe1.unfurlit
1 build succeeded
```

The unsigned rebuild is 19,353,629 bytes; the published and signature-copied APKs
are both 19,382,980 bytes. A separate call to `common.verify_apks` retained the
copied-signature output for whole-file comparison and diffoscope.

## Build setup and reproduction

The existing buildserver uses
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, image digest
`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`.
The validation used fdroidserver commit
`c21c177ff6d813697aaf9c988ca9fbb2b571b468`, Debian Node.js 20.19.2, npm 9.2.0,
Python 3.13.5 and Android SDK 36. Validation used the existing dependency cache
and normal network access. The container's original metadata and sudoers files
were restored, the temporary sudo package was removed, and the container was
stopped afterward.

With the canonical recipe installed as
`metadata/io.github.originalrecipe1.unfurlit.yml` in the configured buildserver:

```sh
fdroid scanner --verbose --exit-code io.github.originalrecipe1.unfurlit:14
fdroid build --verbose --test --on-server --no-tarball --scan-binary io.github.originalrecipe1.unfurlit:14
fdroid scanner --verbose --exit-code build/io.github.originalrecipe1.unfurlit/app/build/outputs/apk/release/app-release-unsigned.apk
fdroid scanner --verbose --exit-code tmp/binaries/io.github.originalrecipe1.unfurlit_14.binary.apk
```

`submodules: true` checks out gallery-dl and its five dependencies, yt-dlp and
EJS at the release's pinned gitlinks. The recipe builds yt-dlp/EJS and passes its
checksum-checked local artifact to Gradle. Gradle's `preparePinnedGalleryDl`
task stages and consumes gallery-dl sources and licenses itself, including the
generated urllib3 version file. No separate F-Droid `prebuild`, gallery-dl wheels,
pip installation or Python build backend is used. Python's stdlib remains
unpacked; the release includes only the audited runtime trims.

## Official merge and build — 2026-10-16 update

[MR !47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809) was
merged by linsui on 2026-10-14 as squash commit `aed2ca8f`. The official
[build log](https://monitor.f-droid.org/builds/log/io.github.originalrecipe1.unfurlit/14)
records a successful 1.4.1 build on 2026-10-16 and
`compared built binary to supplied reference binary successfully`.
The package page returned HTTP 404 when checked on 2026-10-16. **Checked on
2026-10-23:** the [package page](https://f-droid.org/packages/io.github.originalrecipe1.unfurlit/)
is live, the F-Droid API lists 1.4.1 / 14, and F-Droid's APK is byte for byte the
published `Unfurlit-1.4.1.apk`.

The 1.4.1 block of the [current canonical recipe](io.github.originalrecipe1.unfurlit.yml)
is unchanged. Since fdroiddata MR !53855 (merged 2026-10-23) the recipe also
contains 1.4.2 / 15 and sets `AutoUpdateMode: Version`. The recipe hash and
three-block description above belong to the 2026-10-12 local validation,
not the subsequently merged metadata.

**NOT VERIFIED locally:** a fresh-cache or network-isolated build for this release.
Normal source scanning precedes EJS npm dependency downloads; see the
[historical dependency scan](../automation-and-fdroid.md#ejs-dependency-choice-and-131-validation).
The [physical-phone record](../releases/1.4.1-phone-test.md) contains the owner's
Pixel 7 / Android 17 PASS results. No new GitLab activity was performed for this audit.
