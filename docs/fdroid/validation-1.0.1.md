# Unfurlit 1.0.1 F-Droid validation

Validated locally on September 15, 2026, against the published release.

| Item | Value |
| --- | --- |
| Application ID | `io.github.originalrecipe1.unfurlit` |
| Version / code | `1.0.1` / `8` |
| Tag | `v1.0.1` |
| Commit | `fe8c1cd88c4e1460633e5f7058408768860e1269` |
| Published APK SHA-256 | `827b5039af26302b02f0d32be75e0829dfae4af33b25fd3e5fba6ffb1b09a103` |
| Signing certificate SHA-256 | `3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c` |

## Results

- Metadata lint and `rewritemeta --list` passed using the current fdroidserver
  source (`a35fdfddd9c66823987a410566a6101186e39c84`) and ruamel.yaml 0.18.6.
- The source scan passed without scanner exceptions.
- The source build succeeded from the exact release commit, including yt-dlp
  built from its pinned submodule.
- F-Droid copied the published APK's signatures onto the rebuilt APK and
  successfully verified it, including the allowlisted signing certificate.

The final build output included:

```text
...successfully verified
compared built binary to supplied reference binary successfully
supplied reference binary has allowed signer 3528e91676bde711bf40c70bce363f7c76a554ae9de91f221635b6e975400a3c
success: io.github.originalrecipe1.unfurlit
1 build succeeded
```

## Environment and reproduction

Used fdroidserver 2.4.5 for the build, Python 3.12.11 with standard zlib, JDK 21,
Android SDK 36, Gradle 8.14.5, and apksigner from Android Build Tools 36.0.0.
The recipe's `sudo` package-install commands were skipped locally; `make` and
`zip` were already installed.

Run the build in UTC with a Gradle cache that has no Python transforms created
in another time zone. Both the existing cache and a fresh non-UTC build produced
ZIP timestamps two hours ahead of the published release, despite the tagged
project's `-Duser.timezone=UTC` setting. File contents and Unix modes matched,
but signature verification requires the archive bytes to match too. A fresh
UTC transform matched the published archive exactly and the full verification
then passed. No app-source or recipe changes were required for that build.

The successful check used a separate Gradle user home with cached Maven
dependencies, no reused transformed archives, and a new daemon started in UTC.
Its local Gradle configuration pointed Java toolchain discovery to JDK 21.
With the [candidate](io.github.originalrecipe1.unfurlit.yml) in `metadata/` and
F-Droid configured for those tools:

```sh
TZ=UTC JAVA_HOME=/path/to/jdk-21 GRADLE_USER_HOME=/path/to/utc-gradle-home \
  fdroid build --stop io.github.originalrecipe1.unfurlit:8
```

The metadata and description of merge request
[!47809](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47809) were updated
to 1.0.1 in commit `132bcd67f19e725e8f9dcafdaa31215048c11678`.
The new [GitLab pipeline](https://gitlab.com/originalRecipe1/fdroiddata/-/pipelines/2849435558)
passed all nine jobs: F-Droid build, APK check, source check, schema validation,
repository scripts, metadata normalization, metadata lint, repository redirects,
and update detection. Official F-Droid review and publication remain pending.
