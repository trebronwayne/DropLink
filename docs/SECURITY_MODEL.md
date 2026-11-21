# Security Model

## Security objectives
Protect node identity, private files, camera/microphone access, internet gateway usage and user privacy.

## Trust boundaries
- Discovery: untrusted.
- Paired node: authenticated identity, but no blanket service authorization.
- Individual service: separately authorized.
- Administrator/coordinator: no automatic ownership of other nodes' data.

## Requirements
1. Cryptographic node identity protected by Android Keystore where appropriate.
2. Authenticated encrypted sessions.
3. Explicit approval for pairing.
4. Per-service and per-operation authorization.
5. Short-lived session credentials with revocation.
6. Rate limiting and input validation.
7. Audit events for sensitive access.
8. No unauthenticated administrative endpoints.
9. Camera/microphone use requires OS permission and visible user awareness.
10. Gateway access requires explicit consent and abuse controls.

## File access
Use Android Storage Access Framework or narrowly scoped platform APIs. Do not request broad storage permissions unless a specific approved feature and current Android policy justify them. Share selected roots or documents, not the entire private filesystem.

## Receiving files (uploads)
Shared folders stay read-only. Files sent *to* the phone are a separate, opt-in path with these controls:
1. Only paired devices (a valid session cookie) can ask. Each device has a policy set on the phone: **Ask** (default), **Always allow** or **Block**.
2. With **Ask**, the device first sends only names and sizes. Nothing is stored until the phone's owner chooses **Approve once**, **Always approve from this device** or **Deny**, inside the app. The notification only opens the app; there are no approve buttons on the lock screen.
3. An approval covers exactly the announced files. Each file can be sent once, its size must match what was announced, and the approval expires after 30 minutes. A pending request expires after 5 minutes.
4. Limits: 50 files and 16 GB per request, 4 GB per file, 3 waiting requests and 10 requests a minute per device, and a free-space check. Names are cleaned (no folders, control characters or hidden names).
5. Files are written as pending items and only become visible when every byte has arrived, so a dropped connection never leaves a half file. They are saved in `Downloads/DropLink` through MediaStore (no storage permission) and are never executed or opened by DropLink.
6. Blocking a device denies its waiting requests, and revoking a device removes its session.

## Camera
Camera service must be started by a visible user action, use required Android permissions and foreground-service declarations, show persistent/clear active state as required, and stop promptly when revoked.

## Threat model to test
- Rogue nearby device.
- Spoofed display name.
- Replayed pairing request.
- Stolen paired device.
- Malicious file name/path.
- Unauthorized service invocation.
- Resource exhaustion from repeated requests.
- Gateway abuse.
- Accidental camera exposure.
- Node disconnect during transfer.

## Incident response
Provide immediate disconnect, revoke-node and revoke-service controls. Record security-relevant events without logging file contents, credentials or private media.

## Implemented in v0.5.0
- Transport: TLS with a per-node self-signed certificate. There is no certificate authority on a local network, so trust comes from comparing the SHA-256 fingerprint shown in the app with the one the client sees. DropLink-to-DropLink clients will pin it automatically.
- Pairing: request, matching confirmation code on both screens, explicit approval on the phone, then a session token. Only a SHA-256 hash of the token is stored on the phone. Revocation takes effect immediately.
- Limits: 3 pending requests, 5 requests per minute, 2 minute expiry.
- Known limitations: the TLS private key is in app-private storage protected by the Android sandbox, not by hardware. The confirmation code stops approving the wrong request but does not by itself stop an active man-in-the-middle, which is why the fingerprint comparison matters. Audit logs and per-service permissions are still to do.

## Transport architecture (v0.6.1)
Ktor's CIO engine has no TLS support, so `TlsFrontend` terminates HTTPS on the public port with Android's TLS stack (TLS 1.2/1.3, the node's self-signed certificate) and relays to the DropLink server on 127.0.0.1 at a random port. Apps on the same phone can reach that loopback port, but every data route still needs a paired session, and pairing requests are rate limited and need on-screen approval.
