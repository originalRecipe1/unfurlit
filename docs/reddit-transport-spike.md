# Reddit anonymous-session transport spike — 2026-11-02

Step 1 only. This change contains the manual instrumentation spike, an optional
Gradle init script for a separate debug package, and this results record. No
production code, dependencies, routes, CI expectations, or adapter implementation
changed. The owner decides whether to proceed to Step 2.

## Method

Pixel 7, Android SDK 37, main baseline `ec8a0a4` (restore Reddit support). The same
34-case matrix ran once on Wi-Fi and once on mobile data with Wi-Fi disabled.
Before every case, the test reads the active default network through the shell
and refuses to make requests if it differs from the selected network. Mobile
data was already enabled. A detached on-device script disabled Wi-Fi, waited ten
seconds, ran the mobile suite, and restored Wi-Fi on exit, because switching
Wi-Fi off disconnects wireless ADB.

The ten post fixtures are the four videos plus `reddit-image-post`,
`reddit-gallery`, `reddit-gallery-comments`, `reddit-gallery-share`,
`reddit-gallery-short`, and `reddit-mirror-post`. Direct-image, preview-image,
and image-wrapper fixtures are outside this post-discovery experiment.

Each request uses the existing safe OkHttp client, public DNS/redirect checks,
a 20-second call timeout, and the production 4 MiB decompressed-body limit.
There are no extraction-engine calls. Modes:

- `desktop`: desktop Chrome headers, no session; four video fixtures only.
- `desktop-session`: desktop Chrome headers and a fresh anonymous session;
  all ten post fixtures, fetching HTML through unchanged `PublicPageLoader`.
- `desktop-session-json`: the same session setup and headers, then append
  `.json` to the resolved post URL's path while preserving its query string;
  all ten fixtures. The same safe OkHttp client reads the actual status and
  bounded body together, including error responses.
- `session-mobile-ua`: session HTML mode with the original Android Chrome
  User-Agent instead; all ten fixtures. Other navigation headers are identical.

Each session case obtains new cookies from a prior GET to `https://old.reddit.com/`
using a fresh, in-memory cookie jar on a copy of the safe client. Only unexpired
cookies matching the post's HTTPS Reddit URL are supplied to the actual fetch.
Redirects away from that origin strip the explicit Cookie header. The jar is
never persisted; no browser/user cookies are imported. No login, OAuth, WebView,
Cronet, TLS impersonation, or new dependency is used.

Before session setup, every case uses `RedditLinks.normalize`, production
`UrlPreflight.resolve`, and `RedditLinks.keepPostOverGate`. This resolves the
share/short variants and normalizes the mirror fixture; it does not guess the
post ID from an unrelated page. Requested IDs are retained where known and
otherwise obtained from the resolved post URL. The report records IDs and a
redirect boolean, never the fetched URL, body, or cookie values.

HTML passes only when the requested `<shreddit-post id="t3_<id>">` has a nonempty
`content-href`; another post cannot satisfy it. This is a discovery check, not
media validation. JSON passes only for HTTP 200, a JSON array whose first listing
contains a `kind=t3` child with the requested ID, and at least one usable HTTPS
video manifest/destination field or gallery metadata source. Parse failures,
missing posts, missing media and security-block responses are separate outcomes.

If `PublicPageLoader` rejects a non-success response, HTML mode can make one
separate bounded diagnostic GET through the same client to classify the error.
It cannot convert a failed loader request into a pass. Unsafe-network rejections
and IO failures are never retried. That diagnostic is not a captured copy of the
first response. `challenge` alone does not classify a page as blocked: successful
Reddit pages also contain challenge/captcha text.

Bytes below count the UTF-8 body. Elapsed milliseconds include production
preflight, session setup where applicable, and the final fetch (plus a separate
diagnostic only when applicable). Outcomes and numbers are embedded here so the
record does not depend on temporary local files. No response bodies or cookie
values were saved.

## Summary

| Mode | Wi-Fi passes | Mobile-data passes |
| --- | ---: | ---: |
| `desktop` | 0/4 | 0/4 |
| `desktop-session` | 8/10 | 8/10 |
| `desktop-session-json` | 0/10 | 0/10 |
| `session-mobile-ua` | 8/10 | 8/10 |

Wi-Fi: **16 passed, 18 failed, 34 total**. Negative comparisons fail assertions intentionally; these counts are not a claim that all Reddit cases work.

### Wi-Fi: `desktop`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | UNRECOGNISED_HTML | 8,455 | 287 | — | — / — |
| reddit-native-video | UNRECOGNISED_HTML | 8,475 | 112 | — | — / — |
| reddit-external-imgur | UNRECOGNISED_HTML | 8,448 | 99 | — | — / — |
| reddit-external-streamable | UNRECOGNISED_HTML | 8,476 | 95 | — | — / — |

### Wi-Fi: `desktop-session`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | POST_HTML | 1,093,738 | 1,279 | — | 200 / true |
| reddit-native-video | POST_HTML | 1,218,877 | 1,431 | — | 200 / true |
| reddit-image-post | UNRECOGNISED_HTML | 363,481 | 847 | — | 200 / true |
| reddit-gallery | POST_HTML | 1,094,462 | 1,411 | — | 200 / true |
| reddit-gallery-comments | POST_HTML | 1,094,784 | 1,168 | — | 200 / true |
| reddit-gallery-share | POST_HTML | 1,149,889 | 1,488 | — | 200 / true |
| reddit-gallery-short | POST_HTML | 1,094,865 | 1,508 | — | 200 / true |
| reddit-external-imgur | POST_HTML | 1,166,281 | 1,308 | — | 200 / true |
| reddit-external-streamable | POST_HTML | 1,052,758 | 1,383 | — | 200 / true |
| reddit-mirror-post | UNRECOGNISED_HTML | 363,765 | 832 | — | 200 / true |

### Wi-Fi: `desktop-session-json`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | SECURITY_BLOCK | 189,908 | 554 | 403 | 200 / true |
| reddit-native-video | SECURITY_BLOCK | 189,908 | 531 | 403 | 200 / true |
| reddit-image-post | SECURITY_BLOCK | 189,908 | 527 | 403 | 200 / true |
| reddit-gallery | SECURITY_BLOCK | 189,908 | 568 | 403 | 200 / true |
| reddit-gallery-comments | SECURITY_BLOCK | 189,908 | 653 | 403 | 200 / true |
| reddit-gallery-share | SECURITY_BLOCK | 189,908 | 876 | 403 | 200 / true |
| reddit-gallery-short | SECURITY_BLOCK | 189,908 | 571 | 403 | 200 / true |
| reddit-external-imgur | SECURITY_BLOCK | 189,908 | 542 | 403 | 200 / true |
| reddit-external-streamable | SECURITY_BLOCK | 189,908 | 523 | 403 | 200 / true |
| reddit-mirror-post | SECURITY_BLOCK | 189,908 | 498 | 403 | 200 / true |

### Wi-Fi: `session-mobile-ua`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | POST_HTML | 712,957 | 1,016 | — | 200 / true |
| reddit-native-video | POST_HTML | 740,929 | 1,093 | — | 200 / true |
| reddit-image-post | UNRECOGNISED_HTML | 374,253 | 1,023 | — | 200 / true |
| reddit-gallery | POST_HTML | 710,642 | 1,383 | — | 200 / true |
| reddit-gallery-comments | POST_HTML | 710,899 | 949 | — | 200 / true |
| reddit-gallery-share | POST_HTML | 716,455 | 1,267 | — | 200 / true |
| reddit-gallery-short | POST_HTML | 710,874 | 1,444 | — | 200 / true |
| reddit-external-imgur | POST_HTML | 720,135 | 1,071 | — | 200 / true |
| reddit-external-streamable | POST_HTML | 724,720 | 1,076 | — | 200 / true |
| reddit-mirror-post | UNRECOGNISED_HTML | 374,741 | 777 | — | 200 / true |

Mobile data, Wi-Fi off: **16 passed, 18 failed, 34 total**. Negative comparisons fail assertions intentionally; these counts are not a claim that all Reddit cases work.

### Mobile data, Wi-Fi off: `desktop`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | UNRECOGNISED_HTML | 8,455 | 292 | — | — / — |
| reddit-native-video | UNRECOGNISED_HTML | 8,475 | 112 | — | — / — |
| reddit-external-imgur | UNRECOGNISED_HTML | 8,448 | 104 | — | — / — |
| reddit-external-streamable | UNRECOGNISED_HTML | 8,476 | 62 | — | — / — |

### Mobile data, Wi-Fi off: `desktop-session`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | POST_HTML | 1,132,205 | 5,797 | — | 200 / true |
| reddit-native-video | POST_HTML | 1,219,027 | 1,878 | — | 200 / true |
| reddit-image-post | UNRECOGNISED_HTML | 363,490 | 2,293 | — | 200 / true |
| reddit-gallery | POST_HTML | 1,094,730 | 4,058 | — | 200 / true |
| reddit-gallery-comments | POST_HTML | 1,094,576 | 1,859 | — | 200 / true |
| reddit-gallery-share | POST_HTML | 1,228,291 | 2,861 | — | 200 / true |
| reddit-gallery-short | POST_HTML | 1,094,895 | 5,003 | — | 200 / true |
| reddit-external-imgur | POST_HTML | 1,071,915 | 2,787 | — | 200 / true |
| reddit-external-streamable | POST_HTML | 1,125,374 | 2,119 | — | 200 / true |
| reddit-mirror-post | UNRECOGNISED_HTML | 363,508 | 2,143 | — | 200 / true |

### Mobile data, Wi-Fi off: `desktop-session-json`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | SECURITY_BLOCK | 189,908 | 853 | 403 | 200 / true |
| reddit-native-video | SECURITY_BLOCK | 189,908 | 855 | 403 | 200 / true |
| reddit-image-post | SECURITY_BLOCK | 189,908 | 880 | 403 | 200 / true |
| reddit-gallery | SECURITY_BLOCK | 189,908 | 944 | 403 | 200 / true |
| reddit-gallery-comments | SECURITY_BLOCK | 189,908 | 835 | 403 | 200 / true |
| reddit-gallery-share | SECURITY_BLOCK | 189,908 | 1,028 | 403 | 200 / true |
| reddit-gallery-short | SECURITY_BLOCK | 189,908 | 1,286 | 403 | 200 / true |
| reddit-external-imgur | SECURITY_BLOCK | 189,908 | 895 | 403 | 200 / true |
| reddit-external-streamable | SECURITY_BLOCK | 189,908 | 704 | 403 | 200 / true |
| reddit-mirror-post | SECURITY_BLOCK | 189,908 | 1,122 | 403 | 200 / true |

### Mobile data, Wi-Fi off: `session-mobile-ua`

| Fixture | Outcome | Bytes | Elapsed ms | HTTP (JSON) | Bootstrap / loid |
| --- | --- | ---: | ---: | ---: | --- |
| reddit-video | POST_HTML | 712,245 | 1,458 | — | 200 / true |
| reddit-native-video | POST_HTML | 741,026 | 2,296 | — | 200 / true |
| reddit-image-post | UNRECOGNISED_HTML | 374,262 | 1,201 | — | 200 / true |
| reddit-gallery | POST_HTML | 710,826 | 2,055 | — | 200 / true |
| reddit-gallery-comments | POST_HTML | 710,982 | 1,163 | — | 200 / true |
| reddit-gallery-share | POST_HTML | 746,922 | 1,465 | — | 200 / true |
| reddit-gallery-short | POST_HTML | 710,852 | 1,531 | — | 200 / true |
| reddit-external-imgur | POST_HTML | 710,022 | 1,262 | — | 200 / true |
| reddit-external-streamable | POST_HTML | 710,670 | 1,393 | — | 200 / true |
| reddit-mirror-post | UNRECOGNISED_HTML | 374,273 | 1,139 | — | 200 / true |

## Interpretation and limits

On Wi-Fi, JSON outcomes: SECURITY_BLOCK: 10.

On mobile data, JSON outcomes: SECURITY_BLOCK: 10.

For these requests, the HTML-versus-JSON difference persists with anonymous
cookies and OkHttp on both networks. Python is not needed to reproduce the JSON
block. Session HTML access also worked with the Android User-Agent; desktop
headers are not required for those eight fixtures once the session exists.

The two HTML failures, `reddit-image-post` and `reddit-mirror-post`, refer to the
same underlying post (`15ko0qu`). Neither response exposed its requested
`<shreddit-post>` element. These results do not establish why that post fails or
demonstrate single-image support. Gallery HTML passes establish a post and media
link only, not a parsed, ordered image list.

Production preflight resolved the share fixture to post `wjs1lb` and the short
fixture to `hrrh23` on both networks. The explicit gallery also resolved to
`hrrh23`; mirror normalization retained `15ko0qu`. These are the IDs used for
the requested-post checks, not IDs guessed from recommended posts.

The owner makes the Step 2 decision from these results. This spike does not
establish playback, sound, seeking, History metadata, ordered gallery contents,
or reliability across other devices/networks or future Reddit changes. It does
not isolate which individual cookie is necessary. HTML is still matched only
to the requested post; unknown markup is not automatically proof of an account
requirement. Existing Python JSON failures remain separate observations.

The earlier four-video Wi-Fi experiment used mobile headers without session
setup and passed 0/4 (8,455 / 8,475 / 8,448 / 8,476 bytes). A subsequent session
experiment passed all four with desktop headers (1,200,266 / 1,209,087 /
1,157,502 / 1,082,023 bytes; 1,453 / 1,444 / 1,335 / 1,221 ms). Those preliminary
runs motivated this controlled matrix; the full matrix above is the decision
record. Session setup differed between the first phone and desktop proofs, so
the first failure did not isolate a TLS-fingerprint problem.

## Reproduce

Build the two debug APKs with Java 21 and Android SDK 36 available:

```bash
./gradlew -I scripts/reddit_transport_spike.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

The init script gives only the opted-in debug build the package ID
`io.github.originalrecipe1.unfurlit.redditspike`; its test package is
`io.github.originalrecipe1.unfurlit.redditspike.test`. They install beside the
normal app even when it uses a different signing key. The app's production
build configuration is unchanged. Normal test runs skip every spike case unless
`redditTransport=true` is explicitly supplied.

With an unlocked ARM64 phone connected to ADB, select it through `ANDROID_SERIAL`.
For each network run, set Wi-Fi on/off on the phone, wait for its default network
to settle, and supply `wifi` or `mobile` as appropriate. Use USB ADB for a manual
mobile-data run so disabling Wi-Fi cannot disconnect the test command. The
recorded run used the detached on-device procedure described above instead.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class io.github.originalrecipe1.unfurlit.data.extractor.reddit.RedditTransportSpikeTest \
  -e redditTransport true -e redditTransportNetwork wifi \
  io.github.originalrecipe1.unfurlit.redditspike.test/androidx.test.runner.AndroidJUnitRunner
```

Each case emits a `redditTransport` JSON instrumentation status and a
`RedditTransportSpike` logcat entry. The network is measured, not merely copied
from the argument. Temporary spike app/test packages are removed after testing;
the normal installed app and History are left intact.

## Validation

- `assembleDebug` and `assembleDebugAndroidTest` with the opt-in init script: pass.
- `lintDebug` with that script: pass (0 errors; 28 existing project warnings).
- Both full phone matrices and their expected failures are recorded above.
- A separate device invocation without `redditTransport=true` skipped all 34
  cases, confirming that the spike makes no live requests in normal test runs.
- No production files, dependency declarations, or social-links CI expectations
  changed. This PR ends at Step 1.
