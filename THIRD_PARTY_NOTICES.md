# Third-party notices

This inventory covers the release runtime dependency families currently pulled
by `releaseRuntimeClasspath` and the components bundled by youtubedl-android
0.18.1. Test-only libraries are not shipped in the release APK.

| Component or family | Version used | License |
| --- | --- | --- |
| AndroidX (Activity, AppCompat, Collection, Compose, Core, Lifecycle, Media3, Profile Installer, Saved State, Startup, Tracing and related modules) | resolved by the pinned version catalog / Compose BOM | Apache-2.0 |
| Kotlin standard library, coroutines, and serialization | resolved transitively; Kotlin 2.2.10 toolchain | Apache-2.0 |
| Coil | 3.3.0 | Apache-2.0 |
| OkHttp and Okio | 4.12.0 / transitive | Apache-2.0 |
| Jackson annotations, core, and databind | 2.11.1 (transitive) | Apache-2.0 |
| Apache Commons IO | 2.5 (transitive) | Apache-2.0 |
| JetBrains annotations and JSpecify | transitive | Apache-2.0 |
| youtubedl-android | 0.18.1 | GPL-3.0 |
| yt-dlp | 2026.08.19 | Unlicense |
| CPython runtime embedded by youtubedl-android | 3.12 | Python-2.0 / PSF-2.0 |
| QuickJS runtime embedded by youtubedl-android | bundled native runtime | MIT |
| PyCryptodome embedded in the Python runtime | 3.23.0 | BSD-2-Clause and public-domain portions |
| Mutagen embedded in the Python runtime | bundled Python package | GPL-2.0-or-later |
| gallery-dl, in the separately run image engine | 1.32.13 | GPL-2.0-only |
| Requests, in the image engine | 2.34.2 | Apache-2.0 |
| urllib3, in the image engine | 2.8.0 | MIT |
| idna, in the image engine | 3.20 | BSD-3-Clause |
| certifi, in the image engine | 2026.7.22 | MPL-2.0 |
| charset-normalizer, in the image engine | 3.5.1 | MIT |
| OpenSSL | bundled with the Python runtime | Apache-2.0 |
| zlib | bundled with the Python runtime | Zlib |
| libffi | bundled with the Python runtime | MIT |
| bzip2 | bundled with the Python runtime | bzip2-1.0.6 |
| XZ Utils / liblzma | bundled with the Python runtime | 0BSD and public-domain portions |
| SQLite | bundled with the Python runtime | Public Domain |
| ncurses | bundled with the Python runtime | MIT-like ncurses license |
| GNU Readline | bundled with the Python runtime | GPL-3.0-or-later |
| Expat | bundled with the Python runtime | MIT |
| Android C++ shared runtime and Termux Android support libraries | bundled with the Python runtime | Apache-2.0 and respective upstream licenses |

The app itself is GPL-3.0-only; see `LICENSE`. The image engine
(`app/gallery-dl/__main__.py` plus the packages above, assembled into one zip)
is a separate program that the app starts as a process and reads JSON from; its
entry point imports gallery-dl and is licensed GPL-2.0-or-later so that it stays
compatible with gallery-dl's GPL-2.0-only license. Each package's license file
is included in the engine zip. Maven coordinates and resolved
versions can be audited with:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Canonical license texts and corresponding source are available from the linked
upstream projects and their source distributions:

- <https://github.com/JunkFood02/youtubedl-android>
- <https://github.com/yt-dlp/yt-dlp>
- <https://github.com/mikf/gallery-dl>
- <https://github.com/psf/requests>
- <https://github.com/FFmpeg/FFmpeg> (not included by Unfurlit's current dependency set)
- <https://www.python.org/downloads/source/>
- <https://bellard.org/quickjs/>
- <https://github.com/Legrandin/pycryptodome>
- <https://github.com/quodlibet/mutagen>

Before changing runtime dependencies or the Android extraction runtime, update
this file and inspect the resulting release APK again.

## Store-listing demo media

The F-Droid phone screenshots include a frame from *Big Buck Bunny*:

> © copyright 2008, Blender Foundation / www.bigbuckbunny.org

The film is licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)
and is available from the [Blender Foundation](https://peach.blender.org/). The
media is used only in store-listing screenshots and is not bundled in the APK.
