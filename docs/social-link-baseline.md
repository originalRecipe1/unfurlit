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

## Media types on the CI runner

On 2026-09-25 the fixture grew to 55 cases grouped by media type, and the
**Live social links** workflow ran all of them on a GitHub-hosted runner: API 30
x86_64 managed emulator, bundled yt-dlp 2026.08.19 and gallery-dl 1.32.13,
anonymous access. Sites block datacenter addresses more often than phones on home
connections, so this run understates what users can open. A second run of only
the 14 failed cases reproduced every failure with the same reason.

| Media | Passed | Worked |
| --- | --- | --- |
| Video | 11 of 22 | Instagram posts, Reels and video carousels; an X video and animated GIF; a TikTok video; Bluesky; Imgur GIFV; PeerTube; Dailymotion; a Twitch clip |
| Photos and galleries | 21 of 23 | All Instagram and TikTok photo posts; Reddit image posts, galleries, and direct, preview, `reddit.com/media` and mirror links; a four-photo X post; Bluesky; Imgur images and albums; Flickr; Mastodon; Pinterest; Wikimedia Commons; a direct JPEG |
| Audio | 4 of 4 | SoundCloud (HLS), Bandcamp (progressive), Mixcloud (DASH), a direct Ogg file |
| Mixed media | 0 of 1 | — |
| Error handling | 5 of 5 | All cases, including a missing Imgur image reported as unavailable |

Every TikTok photo post returned its soundtrack, including the single-photo post.
The X video, PeerTube and Dailymotion used HLS; the other passing videos, including the X
animated GIF, were progressive.

These cases failed, with the reason from the app's redacted extraction log:

| Case | Outcome | Reason |
| --- | --- | --- |
| youtube-video | Media unavailable | The upstream test clip is unavailable (dead fixture). |
| youtube-short-link, youtube-big-buck-bunny, youtube-shorts | Sign-in required | YouTube asked the runner to “confirm you’re not a bot”. Big Buck Bunny played locally on 2026-09-15. |
| vimeo-video | Sign-in required | Vimeo's web client requires an account. |
| vimeo-player | Extraction failed | Vimeo blocked the client's TLS fingerprint. |
| reddit-video, reddit-native-video | Sign-in required | Reddit blocked both engines (“blocked by network security”). Reddit photo posts still open through gallery-dl, but for a video post it only offers a `ytdl:` DASH manifest link, which the bundled entry point drops, so even its OAuth retry returns no media and Reddit's block is reported. |
| x-video | Extraction failed | The linked Amplify video no longer exists (dead fixture, also noted on 2026-09-04). |
| tiktok-video | Extraction failed | TikTok blocked the runner's IP address for this post. |
| tumblr-photo-post | Network failure | Tumblr closed yt-dlp's connection without a response. Network failures do not fall back to gallery-dl. |
| pixiv-artwork | Sign-in required | gallery-dl needs a Pixiv `refresh-token`, so anonymous Pixiv links cannot open, although Pixiv is listed as supported. |
| x-mixed-media | Only the video | yt-dlp returned the post's video without its photo; gallery-dl is only tried when yt-dlp fails. |

The first direct-video case, a Blender download URL, returned HTTP 404 and is
not counted among the failures above. Its replacement, a Wikimedia Commons WebM
file, passed a focused run as one progressive video, so the current fixture
expects 12 of 22 video cases to pass from CI.
