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

## TikTok photos with audio

Additional check on 2026-09-17: [the reported photo post](https://vm.tiktok.com/ZGdQaShC6/)
passed the new native photo adapter on the same API 36 emulator. It extracted
three ordered photos and a 37-second soundtrack. Manual viewer checks confirmed
image display, swiping to the second photo while the same audio player continued,
and pausing audio when the app entered the background. The live fixture asserts
three image entries and a separate soundtrack, so returning only audio fails.
The original 16-case results above were not rerun for this addition.

## Instagram photo carousel

Additional check on 2026-09-17: [the reported Instagram post](https://www.instagram.com/p/DXnKI92jWYQ/)
passed the native photo adapter with all 11 photos in order. Manual emulator checks
confirmed image display and swiping between slides. No soundtrack was exposed in
the anonymous post response; Instagram photo-post soundtracks are not supported.
The new live fixture asserts 11 image entries, so extracting only video items or
a thumbnail cannot pass. A focused regression run also passed both original
Instagram video/reel cases and the TikTok photo-with-audio case. All 60 unit tests,
Android lint, and the deterministic emulator tests passed.

## Additional Instagram photo carousel

On 2026-09-17, [this additional post](https://www.instagram.com/p/DSXSG7pjHPH/)
returned eight photos. Its live test requires all eight image entries.
Instagram photo-post soundtracks are intentionally unsupported; this does not
affect audio in Instagram videos or TikTok photo posts.

After removing experimental Instagram soundtrack extraction on 2026-09-17,
all five focused live cases passed again: both Instagram photo posts, the
Instagram video and reel, and TikTok photos with audio. All 61 unit tests,
Android lint, debug builds, and deterministic emulator tests also passed.
