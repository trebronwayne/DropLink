# Network Protocol and Federation

**Status:** Draft. No wire protocol is implemented yet.

## 1. Protocol goals
- Discover nearby DropLink nodes.
- Authenticate node identity.
- Pair with explicit user approval.
- Exchange capabilities and service advertisements.
- Maintain federated membership.
- Route requests to the owning node.
- Tolerate temporary disconnection and reconnection.

## 2. Identity
Each installation generates a cryptographic key pair. `node_id` is derived from or securely bound to its public key. Display names and IP addresses are mutable metadata, never identity.

## 3. Discovery
Candidate mechanisms:
- Android NSD / DNS-SD on a shared LAN.
- Wi-Fi Direct discovery where supported.
- QR/manual pairing for bootstrap.
Discovery announces only minimal metadata. Discovery does not grant access.

## 4. Pairing
1. Discover candidate node.
2. Exchange protocol version and ephemeral handshake material.
3. Display peer identity and requested network.
4. Require local approval.
5. Authenticate key possession and establish encrypted session.
6. Store trust relationship according to user choice.
7. Exchange capabilities and service catalog.

Use a reviewed cryptographic protocol/library; do not design custom cryptography.

## 5. Federation records
A network registry tracks:
- `network_id`
- `node_id`
- public identity fingerprint
- display name
- protocol version
- reachable endpoints
- last-seen timestamp
- connection state
- service IDs and status
- authorization relationship

Membership is not equivalent to authorization for every service.

## 6. Service advertisement
A service descriptor contains:
- `service_id`
- `owner_node_id`
- `service_type`
- `service_version`
- endpoint or endpoint-discovery method
- supported operations
- access policy reference
- status

Initial service types: `FILE_STORAGE`, `FILE_TRANSFER`, `MEDIA_STREAM`, `CAMERA_STREAM`, `INTERNET_GATEWAY`, `SYNC`.

## 7. Control messages
Proposed event names:
- `PAIR_REQUEST`
- `PAIR_ACCEPTED`
- `NODE_JOINED`
- `NODE_UPDATED`
- `NODE_DISCONNECTED`
- `NODE_REMOVED`
- `SERVICE_ADDED`
- `SERVICE_UPDATED`
- `SERVICE_REMOVED`
- `HEARTBEAT`

Messages require a protocol version, request/event ID, sender node ID and timestamp. State-changing messages must be authenticated and replay-resistant.

## 8. Transport
Initial candidates:
- TLS-protected TCP for reliable control and file operations.
- HTTP APIs for service requests.
- WebSocket for live control events.
- HTTP Range for media seeking and partial reads.
- WebRTC for real-time camera/audio sessions.

Transport selection is abstracted from service APIs.

## 9. Reachability and routing
The registry must distinguish:
- known node
- trusted node
- currently reachable node
- node reachable only through a supported relay

Do not assume Wi-Fi Direct automatically creates a multi-hop mesh. Validate hardware and Android concurrency constraints.

## 10. Versioning
Protocol versions use explicit compatibility negotiation. Unknown mandatory capabilities fail safely. Optional capabilities can be ignored. Breaking changes require a documented version transition.
