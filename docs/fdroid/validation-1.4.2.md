# Unfurlit 1.4.2 F-Droid validation

**VERIFIED locally on 2026-10-17 against the published release.** The tested
1.4.2 / 15 block uses the full merge commit, with `Binaries` and
`AllowedAPKSigningKeys` retained. No release source or binary was changed.

[fdroiddata MR !53855](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53855),
which adds this 1.4.2 / 15 block and sets `AutoUpdateMode: Version`, was merged
on 2026-10-23 as `167949b6c16e159ddbdd185b30145b6784eb7464`. The
[canonical recipe](io.github.originalrecipe1.unfurlit.yml) is now a byte-identical
copy of that merged file. The temporary validation recipe below is historical: it
tested `AutoUpdateMode: None` and only the 1.4.2 block, so it covers the signed
build, not the auto-update setting. The two draft files have been retired; there
is only one recipe YAML in this directory. No GitLab activity was performed for
this documentation.

| Item | Value |
| --- | --- |
| Version / code | 1.4.2 / 15 |
| Tag / full commit | `v1.4.2` / `c29faaec30adb4fd5988f0eda42ff86723b4cd95` |
| Release | [Unfurlit 1.4.2](https://github.com/originalRecipe1/unfurlit/releases/tag/v1.4.2) |
| Publication workflow | [Publish release tag #39010572020](https://github.com/originalRecipe1/unfurlit/actions/runs/39010572020) — PASS |
| Android CI run / artifact | [Run #38930558346](https://github.com/originalRecipe1/unfurlit/actions/runs/38930558346) / `unfurlit-unsigned-release-apk` |
| Tested temporary recipe SHA-256 | `5d559c920beb294291bcfdff8046c3912c2782f8df654649102aa8b88de85212` |
| Unsigned F-Droid rebuild / CI SHA-256 | `70219c665e6bf164f74bd5736718750d60b6c5c4387dc8b4975e54705de951bf` |
| Published / signature-copied APK SHA-256 | `749ac41a231fe3561cafcd37ae8ecbc823fd838733aa9d1f30641b6c7b068872` |
| Signing certificate SHA-256 | `3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c` |

## Publication and CI comparison

**VERIFIED:** the tag resolves to the tested merge commit. The downloaded
`Unfurlit-1.4.2.apk` matches its `.sha256` file and GitHub asset digest. The
release also contains the R8 mapping. `apksigner verify --verbose --print-certs`
and `zipalign -c -v 4` both exited 0; the signer matches the allowlist.

All 235 original unsigned CI ZIP entries have identical contents in the
published APK; added entries are signature metadata. F-Droid's
`apksigcopier.copy_apk` stripped signing data from both APK copies, yielding
byte-identical unsigned files with the CI hash above. `common.verify_apks`
copied the published signature onto the CI APK and verified it; the complete
result matched the published APK byte for byte.

## F-Droid results

| Check | Exit code | Result |
| --- | ---: | --- |
| Source scanner (`--exit-code`) | 0 | VERIFIED |
| Full F-Droid build, including binary scan, published-APK comparison and signer allowlist | 0 | VERIFIED |
| Standalone APK scanner: unsigned rebuild | 0 | VERIFIED |
| Standalone APK scanner: published APK | 0 | VERIFIED |
| Retained signature-copy verification and full-file equality | 0 | VERIFIED |
| Diffoscope: signature-copied rebuild versus published APK | 0 | VERIFIED |
| Unsigned rebuild versus merge-commit CI (`cmp`) | 0 | VERIFIED |
| Published-versus-CI signature-stripped and signature-copy comparisons | 0 | VERIFIED |

The full build fetched the published APK through `Binaries`. Its final output
included:

```text
...successfully verified
compared built binary to supplied reference binary successfully
supplied reference binary has allowed signer 3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c
success: io.github.originalrecipe1.unfurlit
1 build succeeded
```

The unsigned rebuild is 19,358,189 bytes. The published and signature-copied
APKs are both 19,387,076 bytes. A separate `common.verify_apks` invocation
retained the copied-signature APK for whole-file comparison and diffoscope.

## Build setup and reproduction

Validation used `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`,
image digest `sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`,
and fdroidserver commit `c21c177ff6d813697aaf9c988ca9fbb2b571b468`.
The recipe's sudo setup steps, source submodules and build commands were retained.
Validation used the existing dependency cache and normal network access.
Temporary container metadata and sudo configuration were restored afterward.

With the recipe below installed as `metadata/io.github.originalrecipe1.unfurlit.yml`
in the configured buildserver:

```sh
fdroid scanner --verbose --exit-code io.github.originalrecipe1.unfurlit:15
fdroid build --verbose --test --on-server --no-tarball --scan-binary io.github.originalrecipe1.unfurlit:15
fdroid scanner --verbose --exit-code build/io.github.originalrecipe1.unfurlit/app/build/outputs/apk/release/app-release-unsigned.apk
fdroid scanner --verbose --exit-code tmp/binaries/io.github.originalrecipe1.unfurlit_15.binary.apk
```

The temporary recipe tested (trailing whitespace omitted below; the hash above
identifies the tested file). This does not synchronize the canonical file:

```yaml
AntiFeatures:
  NonFreeNet:
    en-US: Connects to third-party social platforms and media CDNs
Categories:
  - Online Media Player
License: GPL-3.0-only
AuthorName: originalRecipe1
SourceCode: https://github.com/originalRecipe1/unfurlit
IssueTracker: https://github.com/originalRecipe1/unfurlit/issues

AutoName: Unfurlit

RepoType: git
Repo: https://github.com/originalRecipe1/unfurlit.git
Binaries:
  https://github.com/originalRecipe1/unfurlit/releases/download/v%v/Unfurlit-%v.apk

Builds:
  - versionName: 1.4.2
    versionCode: 15
    commit: c29faaec30adb4fd5988f0eda42ff86723b4cd95
    subdir: app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y git make nodejs npm python3 tar zip
    gradle:
      - yes
    build:
      - ../scripts/build_yt_dlp_from_source.sh
      - echo "unfurlit.ytdlp.file=$PWD/../build/yt-dlp-source/yt-dlp" >> ../gradle.properties
      - echo "unfurlit.ytdlp.sha256=$(sha256sum ../build/yt-dlp-source/yt-dlp | awk
        '{print $1}')" >> ../gradle.properties

AllowedAPKSigningKeys: 3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c

AutoUpdateMode: None
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.]+)?$
UpdateCheckData: app/build.gradle.kts|versionCode\s*=\s*(\d+)||v(.*)
CurrentVersion: 1.4.2
CurrentVersionCode: 15
```

The build still assembles gallery-dl and its five dependencies from pinned source
submodules, with no gallery-dl wheels or pip. Gradle stages and consumes the
sources itself; no separate F-Droid prebuild staging step is needed.

**NOT VERIFIED:** a fresh-cache or network-isolated build, and official F-Droid
build/publication of 1.4.2. !53855 was merged on 2026-10-23; when checked that day,
the F-Droid API still listed only 1.4.1 / 14 and the version 15 build log returned
HTTP 404. Successful local validation does not establish those outcomes. The
[phone record](../releases/1.4.2-phone-test.md) contains the owner's results and
identifies unreported details.

## Historical prerelease validation — 2026-10-16

**VERIFIED** at candidate commit `f9d66ab5ea0e24016891acdac1abe5396c86ff05`:

| Check | Exit code |
| --- | ---: |
| Source scanner (`fdroid scanner --exit-code`) | 0 |
| Full `fdroid build --test --on-server --no-tarball --scan-binary` for version 15 | 0 |
| Standalone APK scanner (`fdroid scanner --exit-code`) | 0 |
| Unsigned APK `cmp` against same-commit CI | 0 |
| Diffoscope against same-commit CI | 0 |

Both unsigned APKs are 19,358,189 bytes, SHA-256
`46fd65de8ff7b6a0a26647417678aa4d87e523401a5963d6a2738928281911b9`.
[Android CI run 38925176950](https://github.com/originalRecipe1/unfurlit/actions/runs/38925176950)
was dispatched on the release branch so checkout used the actual candidate SHA;
both jobs passed. PR CI also passed, but its APK embedded a synthetic merge SHA
and initially differed only in `META-INF/version-control-info.textproto` content.
No binary rewriting was used to obtain the successful comparison.
