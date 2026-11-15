# Service API Contract

**Status:** Conceptual contract; endpoint paths and schemas are not frozen.

## Common rules
- Every service belongs to an owning node.
- Requests are authenticated and authorized at the owner.
- Every response identifies the owner node and service version.
- Long-running operations expose cancellation and status.
- Network loss is an expected state.

## File service
Capabilities: list authorized directory, metadata lookup, download, upload, delete only when explicitly granted, preview and range read.

File references include `owner_node_id` and opaque `resource_id`; never rely on a remote path supplied by an untrusted client.

## Media service
Provides metadata and stream sessions. HTTP Range support is required for seekable media. Streaming does not imply downloading or caching the complete file.

## Camera service
Provides camera capability metadata, explicit start/stop session, viewer authorization and WebRTC session negotiation. The host user controls camera activation.

## Internet gateway
Advertises gateway capability and policy. Initial target is application-level proxying for DropLink Browser. Full-device routing is a later Android-specific feature requiring explicit OS authorization.

## Sync service
Only explicitly selected folders/resources participate. Define conflict policy, deletion semantics, quotas and version history before implementation.

## Error model
Use stable machine-readable error codes such as:
- `UNAUTHORIZED`
- `FORBIDDEN`
- `NODE_UNREACHABLE`
- `SERVICE_UNAVAILABLE`
- `RESOURCE_NOT_FOUND`
- `QUOTA_EXCEEDED`
- `UNSUPPORTED_PROTOCOL`
- `TRANSFER_INTERRUPTED`

## HTTP API (protocol 3, v0.5.0)
HTTPS on port 47831. Every route except `/health`, `/api/pair/*` and static web assets needs the `dl_session` cookie (Secure, HttpOnly, SameSite=Strict) issued after a pairing request is approved.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Unauthenticated status: service, status, protocol |
| POST | `/api/pair/request` | Body `{"name":"Chrome on Android"}`. Returns `{id, code}`; 429 when rate limited |
| GET | `/api/pair/status?id=<id>` | `pending`, `approved` (sets the session cookie once), `denied` or `expired` |
| GET | `/api/info` | This node's ID |
| GET | `/api/shares` | Shared folders `[{id, name}]` |
| GET | `/api/browse?share=<id>&path=<relative>` | Folder listing `[{name, isDir, size, mime, modified}]` |
| GET | `/api/speedtest/down?mb=<1-200>` | Streams zero bytes for a download speed test (needs session) |
| POST | `/api/speedtest/up` | Discards an uploaded body (max 200 MB) and returns `{bytes, millis}` (needs session) |
| GET | `/api/upload/info` | This device's upload setting `{policy: ask\|always\|blocked, maxFiles, maxFileBytes, maxTotalBytes}` (needs session) |
| POST | `/api/upload/request` | Body `{"files":[{"name","size"}]}`. Returns `{id, status: pending\|approved, always}`. 403 `uploads_blocked`, 429 `too_many_requests`, 413 `too_large`/`file_too_large`, 507 `no_space` (needs session) |
| GET | `/api/upload/status?id=<id>` | `pending`, `approved`, `denied`, `expired` or `done` (needs session) |
| PUT | `/api/upload/file?id=<id>&index=<n>` | Raw body of file `n` of an approved request. `Content-Length` must equal the announced size. Returns `{saved, name}`. 403 `not_approved` (not approved, already received or in progress) (needs session) |
| POST | `/api/upload/cancel?id=<id>` | The sender gives up; the request stops accepting files (needs session) |
| GET | `/api/file?share=<id>&path=<relative>[&download=1]` | File bytes, supports `Range`. HTML, SVG and script types are always sent as downloads |
