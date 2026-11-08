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

## HTTP API (protocol 2, v0.4.0)
All routes except `/health` and static web assets require the `dl_session` cookie returned by login.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Unauthenticated status: service, status, protocol |
| POST | `/api/login` | Body `{"pin":"123456"}`. 200 sets the session cookie; 401 wrong code; 429 locked for 60 s after 5 failures |
| GET | `/api/info` | This node's ID |
| GET | `/api/shares` | Shared folders `[{id, name}]` |
| GET | `/api/browse?share=<id>&path=<relative>` | Folder listing `[{name, isDir, size, mime, modified}]` |
| GET | `/api/file?share=<id>&path=<relative>[&download=1]` | File bytes, supports `Range`. HTML, SVG and script types are always sent as downloads |

Transport is plain HTTP until secure pairing and TLS are implemented; the access code and files are not encrypted on the network.
