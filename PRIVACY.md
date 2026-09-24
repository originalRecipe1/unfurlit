# Privacy

Unfurlit has no project-operated backend, analytics, advertising SDK, telemetry, or
mandatory account. URL extraction runs on the device.

Opening media still sends requests from the device to the submitted social
platform, its redirect targets, CDNs, and other hosts required by that platform.
Those services can observe the device's IP address, request metadata, and any
cookies the operating environment supplies. Unfurlit does not make this access
anonymous.

Temporary cookies obtained during extraction can be used for video/audio
playback. They remain in memory for that media source and are sent only to
matching domains and paths while valid; they are not saved in History.

Viewing history is opt-in through use of the viewer and is stored only in Unfurlit's
private on-device SQLite database. It contains the original page URL, basic
display metadata, media type/count, duration, viewing time, and a small thumbnail when available. After a view,
Unfurlit may fetch its preview image from the original media host and save a
resized copy locally. Browsing History uses these local copies without making
network requests. It excludes direct CDN URLs, request headers, cookies,
descriptions, and raw extractor output. Removing a visit also removes its
saved thumbnail. History is excluded from Android backup and device transfer
and can be deleted per item or cleared in full.

While media plays, Unfurlit publishes its title and creator to Android's media
controls so they can appear in the notification, on the lock screen, and on
connected headsets or car systems. Other apps can connect to these controls only
if Android trusts them with media control, such as the system UI. No artwork is
fetched for them.

Unfurlit reads the clipboard only after the user presses the Paste button. Release
logging redacts URLs and common secret fields and does not deliberately log
cookies, authorization headers, direct media URLs, or raw extractor output.

If optional cookie import is added later, this document and the in-app disclosure
must be updated before release.
