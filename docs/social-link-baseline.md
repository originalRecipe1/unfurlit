# Social-link extraction baseline

Initial local run: 2026-09-17, Android API 36 x86_64 emulator, bundled yt-dlp
2026.08.19, anonymous access. These results describe this environment and time;
they do not guarantee availability from GitHub-hosted runners or other regions.

| Case | Expected | Observed |
| --- | --- | --- |
| youtube-video | Success | Media unavailable |
| youtube-short-link | Success | Success |
| vimeo-video | Success | Sign-in required |
| vimeo-player | Success | TLS fingerprint blocked |
| reddit-video | Success | Success |
| reddit-native-video | Success | Success |
| x-video | Success | Extraction failed |
| x-second-video | Success | Success |
| instagram-post | Success | Success |
| instagram-reel | Success | Success |
| tiktok-video | Success | Extraction failed |
| tiktok-second-video | Success | Success |
| non-media-page | Unsupported URL | Unsupported URL |
| missing-page | Media unavailable | Media unavailable |
| invalid-scheme | Unsupported URL | Unsupported URL |
| private-address | Unsupported URL | Unsupported URL |

11 of 16 expectations passed: seven successful extractions and all four negative
cases. Five positive cases failed. They remain failures in the live workflow so
compatibility gaps stay visible; the deterministic pull-request checks are
independent. In particular, the older upstream YouTube test clip now reports
unavailable, and Vimeo requires sign-in or rejects the client's TLS fingerprint.
The TLS error also exposed an overly broad error classifier: a diagnostic mention
of “security/cookies” was interpreted as authentication. The classifier now checks
sign-in language instead, and has a deterministic regression test.

See [development](development.md#social-link-regression-pipeline) for the fixture,
workflow, commands and test limits. Successful extraction does not verify actual
playback. Keep real media URLs, cookies and raw engine output out of this document.
