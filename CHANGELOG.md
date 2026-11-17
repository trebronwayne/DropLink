# Changelog

## Unreleased

### Camera Nodes (new)
- Any paired browser can become a Camera Node and share its camera and microphone: front, back, or both when the device allows it, with a light, standard or high picture. Sound can be turned off. A phone can switch cameras while sharing without dropping viewers.
- The phone's owner decides per device: **Ask me** (allow this time, always, or deny, with a notification), **Always**, or **Block**. Nodes show on the Devices screen only as a small "camera live" line and a policy switch, so the phone stays uncluttered.
- Other paired devices open **Cameras** in the web client to see every node, online or offline, and watch one or several at once. They can listen, take snapshots, record moments and go full screen. A node that goes offline shows as offline and comes back by itself.
- The picture and sound go straight between the two browsers (WebRTC). The phone only approves the node and carries the small set-up messages, so it needs no camera or microphone permission and the app's permissions did not change.
- The node keeps the screen awake while sharing and tells the phone when the page closes. Revoking a device or blocking it ends its node.

### Media player (new)
- A new Player tab plays music and video. Pinned player on top (video or cover art), controls under it, and the list scrolling below, newest first, music and video together. A mini player keeps controls on every screen.
- Music only lists common formats (mp3, m4a, aac, wav, flac, ogg) and video mp4, mkv, webm, 3gp, m4v, mov, so ringtones and voice notes stay out. "Newest" is when a file appeared on the phone, so a file you just received is always at the top. The list updates by itself when files are added.
- Player tabs: Recent (what you played, music and video), Music, Videos and Favorites. Music and video are no longer mixed in one list; each is newest first.
- Favorites (heart on the song or video, or in its menu), a queue you can edit (play next, add to queue, move, remove, clear), a sleep timer (15 to 60 minutes or end of item), and a menu on every row (also on long press).
- Videos: continue where you left off, with a progress line on the thumbnail, a "Resumed at ... Start over" note, and a small floating Continue button while you browse. Double-tap the left or right half of a video to jump 10 seconds. Fixed the second seek bar that appeared when tapping the video.
- Songs: the cover stays in view and only the line being sung flows beside it. Tap that box for all the lyrics, with a quick text-size button. Lyrics also have a size and an on/off setting in Settings.
- Live streaming now has its own live queue: when you turn it on, the item playing becomes the first in the queue, only queued items play and in that order, and paired devices see only that list (as "Live queue"). Add items with the menu on a song or video.
- Player settings (continue videos, keep screen on, lyrics next to cover, lyrics size, shortest music, clear recent, forget positions) live in Settings, with shortcuts in the player.
- Background play with notification and lock-screen controls (Media3 session service). Video pauses when you leave the app; music carries on. Shuffle, repeat, speed 0.5x to 2x, full-screen video.
- Lyrics: finds a .lrc, .lyr, .srt, .vtt, .ass or .txt with the same name next to the song, and reads lyrics stored inside mp3 (USLT and SYLT frames, including LRC text inside USLT) and flac files. Synced lyrics highlight and scroll with the song; tap a line to seek. Plain lyrics show as text.
- Subtitles: srt, vtt, ass/ssa next to a video (for example Movie.en.srt), with a track picker. Add folders for lyrics and subtitles in Settings, because Android only lets apps read these files in folders you open to them. Or pick a file by hand for one item.
- Received music and video open in the DropLink player. DropLink also opens audio and video from other apps.
- Live media streaming: switch it on in the Player and paired devices get a Live page in their browser that follows the phone (same item, position, play and pause, speed) with lyrics, subtitles and the queue. Off every time the app starts; only the current queue can be fetched.

### Fixes
- Home: shared folders are now small explorer-style rows (28px icon, no large background box).
- Web client on phones: content now uses the full width (a tablet-width rule was leaving half the screen empty).
- Web client: Home is Home again. With one shared folder it opens that folder only when the page first loads.
- Files sent to a device: the web page checks every 3 seconds and when it comes back to the front, shows a toast, a bar that stays until the files are downloaded, a tab-title count, and optional system notifications. The Inbox page shows when it last checked and any error. Files on offer are no longer lost when the hosting service restarts itself.

### Devices, sending files, resumable transfers
- Web client restyled to match the app (same colours, pill buttons, rounded cards) and made more responsive: tablet and big-screen layouts, phone sheets, safe-area spacing, landscape phones.
- New Devices screen in the app: each paired device shows who it is (name and model typed by the visitor, since browsers hide it), browser and system, when it was paired, and whether it is online or was seen recently. Visitors are asked for their name and device model when they connect, and once later if they paired before.
- Send files from the phone: pick several files, send to everyone online or to chosen devices. Only devices that are online or were online in the last 10 minutes can be chosen. Devices see the files under "From the phone" and get a notice.
- Resumable transfers: files sent to the phone continue where they stopped (the phone keeps the partial file, also across new requests), and downloads use stricter range validation (ETag, Last-Modified, If-Range) so a browser can resume them. The app shows per-file progress and status (waiting, receiving, paused, done) both ways.
- Received files open with other apps: tap a file in Transfers.
- Fixed the battery optimization row not opening: the one-tap dialog does nothing once DropLink is already allowed, so the full battery list (then app settings) opens instead.

### Softer app design
- New calm look for the app in light and dark themes: rounded cards with hairline edges, pill buttons, a soft white (dark theme) or ink (light theme) main action color, and one small teal accent from the logo for icons, toggles and focus.
- Settings are now organised into groups: This phone, Hosting, Trusted devices, Diagnostics, Roadmap and About. Long content (certificate fingerprint, hosting log, roadmap) folds away until opened. Upload permission per device is a Ask / Always / Block selector.
- Softer bottom bar and side navigation, rounded text fields, pill status badge and switches.

### Support
- A **Support DropLink on Ko-fi** button in the app's About card and in the web client's Settings opens https://ko-fi.com/nobertlamn. GitHub's Sponsor button on the repository points to the same page (`.github/FUNDING.yml`).

### Notification sound
- Pairing requests, incoming-file requests and received-file notifications now play the DropLink sound (`app/src/main/res/raw/droplink_notification.ogg`; the WAV and OGG sources are in `branding/sounds/`).
- A **Notification sound** switch in Settings turns it off. Android fixes a channel's sound when it is created, so DropLink keeps a sound and a silent version of each channel and picks one from this switch. The hosting notification stays silent.

### Receive files
- Paired devices can now send files to the phone. The phone's owner approves each request once, approves always for that device, or denies it; a device can also be set to Ask, Always or Block in Settings, under Trusted devices.
- Received files are saved in `Downloads/DropLink` (no storage permission needed) and listed under Transfers, with a notification when they arrive.
- The web client has a Send files dialog with drag and drop, a waiting screen, per-file progress and retry. Press `U` to open it.

### Release build
- R8 code shrinking, optimisation and resource shrinking are on for release builds (`app/proguard-rules.pro`). CI stores `mapping.txt` for each signed release.

### Web client redesign
- Fluent-style look (flat surfaces, thin borders, one accent) with a neutral graphite dark theme and a paper-white light theme. Accents: Blue, Teal, Indigo, Green, Graphite.
- Easier playback: resume where you left off, "Continue where you left off" on Home, sleep timer, queue reorder and clear, ±10 second buttons, saved speed and volume, more keyboard shortcuts (`?` shows them), toasts for quick feedback and an image zoom in the viewer.
- Accessibility: skip link, landmarks, dialog focus handling, labelled controls, announced track changes, text size, high contrast, reduce motion, compact density, forced-colors support and larger touch targets.
- README now introduces Aubree with her pictures.

### Aubree, the DropLink mascot
- App: a first-run "Meet Aubree" welcome, small banner toasts (hosting on or off, someone wants to connect, a device joined, folder shared, connection test, Battery Saver, errors), a Home card that shows what the server is doing, and Aubree on empty screens, the pairing request, the problem report and the Battery Saver warning. Uses AubreeKit 1.0.0 from JitPack.
- Web client: Aubree welcomes a device that asks to connect, waits with it for approval, celebrates when it is approved, and appears on the connecting, can't-reach-the-phone, error and empty screens. She shows as a still picture when the system asks for reduced motion.
- Server: WebP pictures are served with the right content type. The web client's type check now passes with React 19 types.
- Removed Aubree from the "Coming soon" lists.

### Host a page
- Publish a website from a folder at `/site/`, with sign-in accounts (salted PBKDF2 hashes), a `private/` area for signed-in visitors, and `/site-api/` endpoints so pages can sign people in and send information to the phone. Optional "approved devices only" mode.
- New "Host a page" card under Files; guide in `docs/HOST_PAGE.md`; working example in `samples/login-site/`.

### Production
- Problem report and hosting log now say to email trebronwayne@gmail.com, with an "Email report/log" button (nothing is sent unless the user presses send); developer-directed wording removed, crash and event logs unchanged.
- About card: send feedback, report an issue on GitHub, Privacy Policy, source. "Coming soon" card lists upcoming features.
- Added `PRIVACY.md` and an offline copy at `/privacy.html`; rewritten README with banner, screenshots and roadmap.

### Speed
- Folders are read with one Storage Access Framework query (was about five queries per file) and cached briefly; listings open near-instantly.
- Web client files are read once, gzip-compressed, served from memory with ETag, and cached forever by the browser (hashed filenames).
- New `/api/thumb` endpoint: grid view loads small JPEG thumbnails instead of full photos.
- Web client paints cached folder listings immediately, then refreshes; folders are prefetched on hover; long folders render in chunks.
- TLS session cache enabled so returning browsers skip the full handshake.

### Background hosting
- Wi-Fi high-performance lock added (the low-latency lock alone only works while the app is on screen).
- Watchdog sends a real HTTP request to the engine every 10 s over a raw socket (Android blocks http:// URLs even to localhost, which made an earlier version restart the server in a loop). It needs 3 misses in a row and never counts a frozen period as a failure.
- Internal restarts no longer flip the visible hosting state; the TLS front end no longer crashes while stopping.
- When Android freezes the app, the log now records why (process importance, battery exemption, standby bucket).
- Inexact keep-alive alarm that can fire in Doze; server threads run at foreground priority.
- DropLink asks once, with Android's one-tap dialog, to be exempt from battery optimization.

- DropLink now warns on the Home screen when Battery Saver is on while hosting (it pauses background apps), and records the Battery Saver state in the log.

### Connecting devices
- Each address is now labelled (Hotspot, USB tethering, Wi-Fi network). Mobile-data addresses are recognised and hidden, since nothing on a hotspot can reach them.
- The host screen shows a QR code per address, plus Copy and Share buttons. Scanning opens DropLink with nothing to type.
- The web client has a "Connect a device" button that shows the same QR codes (`/api/addresses`, `/api/qr`, sign-in required).

- Optional, collapsed Wi-Fi join code under the main QR ("Device not on this hotspot yet?"); name and password are entered once and kept only on the phone.

### Install and reconnect
- Web manifest, icons and a service worker so the client can be installed and opens from a cached shell (browsers only run the service worker on trusted HTTPS, so with the phone's self-signed certificate it may be a shortcut only).
- "Can't reach the phone" screen: retries automatically, offers Scan new QR code (BarcodeDetector) and typing the new address.
- `connect/`: an optional launcher PWA to host on trusted HTTPS; remembers the address, opens it in one tap, rescans when it changes.

### Web client
- Rebuilt with a soft neumorphic look (light/dark/system, five accent colors).
- Persistent player: music and video keep playing while you browse; queue, shuffle, repeat, speed, Media Session lock-screen controls, resume after reload, auto-recovery after a dropped connection.
- Favorites, Recent, Home, file-type filters, multi-select download, Play all, swipe/preload image viewer, keyboard shortcuts.

## 0.8.0
- Official DropLink logo, redrawn as clean vectors (`branding/droplink-logo.svg`): launcher icon (adaptive, with a themed monochrome layer), notification icon, logo in the app and in the web client, browser tab icon.
- Web file browser rebuilt in Fluent style with React: navigation pane with shared folders, breadcrumb, command bar (filter, sort by name, date or size, ascending or descending, list or grid view), file-type SVG icon set, image thumbnails in grid view, a viewer with previous and next, keyboard shortcuts, skeleton loading, empty states, dark mode and a phone layout.
- Background hardening: a watchdog checks the server every 15 seconds, notices when Android freezes or throttles the app, and restarts the server if it stops answering. A new "Hosting activity" log in Settings records screen on and off, app foreground and background, memory warnings, and when Android restarts or stops the service.

## 0.7.0
- Reliability: while hosting, the service holds a partial wake lock and a Wi-Fi lock, and Settings has a "Keep hosting reliable" card with battery-optimization status and a shortcut to the battery settings. If Android stops the hosting service unexpectedly, the problem report now says so.
- Test connection button on the device card: checks that the server answers on this phone and on each network address, and lists the phone's network interfaces. Shows whether a problem is the server or the network.
- Open on this phone button: opens the server through this phone's loopback address, so you can check the server without any other device.
- New `docs/TROUBLESHOOTING.md`.

## 0.6.1
- Fix: starting the server crashed with "CIO Engine does not currently support HTTPS". HTTPS is now terminated by a built-in TLS front end using Android's own TLS stack; the DropLink server behind it listens on this phone's loopback address only.
- Testing mode (HTTP) still serves plain HTTP directly on the public port.

## 0.6.0
- Performance focus for the main server: file streaming uses 256 KB buffers, folder paths are cached briefly to avoid slow file-provider lookups, and shared-folder lookup no longer re-checks every folder per request.
- Speed test: `/api/speedtest/down` and `/api/speedtest/up`, with a Speed test page in the web client (1 to 8 simultaneous streams) that measures the network path without touching storage.
- Live server statistics on the phone's Home screen: active transfers, current MB/s, total served.
- New `docs/PERFORMANCE.md` with what limits speed and how to measure it.

## 0.5.1
- On-device problem report: caught startup errors and uncaught crashes are saved and shown on Home with Copy report and Clear buttons, so problems can be reported without a computer.
- Server startup now reports which step failed (port check, certificate, server creation, server start) instead of crashing silently.
- Testing mode (HTTP) switch in Settings, off by default, to tell whether a problem comes from HTTPS.
- Discovery failures can no longer stop hosting or crash the app.

## 0.5.0
- HTTPS only: the server uses a self-signed RSA certificate created on first use and kept in app-private storage. The certificate's SHA-256 fingerprint is shown in the app so it can be compared with the browser's certificate details.
- Pairing replaces the temporary access code: a device requests access, both screens show the same 6-digit confirmation code, and the phone owner approves or denies. Approved devices get a long-lived session token (only its hash is stored). Pending requests expire after 2 minutes and are rate limited. Trusted devices can be revoked in Settings.
- Pairing notification (high priority) when a request arrives.
- Discovery: nodes advertise `_droplink._tcp` with Android NSD while hosting; the Servers screen lists nodes found on the network and can open them in the browser.
- Web client: pairing screen replaces the code entry.
- CI: release workflow no longer fails on the retired `tools` SDK package; APK signature check uses the explicit build-tools path.

## 0.4.0
- Real HTTP server (Ktor CIO) replaces the raw socket. `/health` stays open; everything else needs a session.
- Shared folders: pick folders with the system folder picker (Storage Access Framework). Access is read-only and limited to those folders.
- Temporary access control: a 6-digit code per hosting session, session cookie, login lockout after 5 wrong tries. Replaced by secure pairing in the next stage.
- File API: `/api/shares`, `/api/browse`, `/api/file` with HTTP Range support for seeking in video and audio.
- React + TypeScript web client (`web/`), built into `app/src/main/assets/web` and served by the phone itself.
- Device card shows this phone's network addresses, a Copy address button and the access code.

## 0.3.0
- Fluent 2 style design system in pure Compose (tokens, theme, components) with Material icons.
- Adaptive navigation: bottom bar on phones, Windows 11 style pane on tablets and large screens. Destinations: Home, Servers, Files, Transfers, Settings.
- Settings screen with copyable node ID and version.
- Fix: notification permission is now requested before hosting on Android 13+.
- Fix: hosting state is live from the service, so notification Stop and failures update the UI.
- Fix: failed socket binds retract the foreground notification and reset state; server socket uses SO_REUSEADDR.
- Fix: dark-mode launch flash (day/night window theme); edge-to-edge layout.

## 0.2.0
- Node identity (Android Keystore EC key) and user-started local health endpoint.
