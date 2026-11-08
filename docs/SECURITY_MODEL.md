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
