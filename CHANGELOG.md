# Changelog

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
