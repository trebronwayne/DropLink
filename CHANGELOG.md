# Changelog

## Unreleased

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
