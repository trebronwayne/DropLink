# Roadmap

## Phase 0 — Project foundation
- [x] Establish product vision and package identity.
- [x] Add initial Android Compose scaffold.
- [x] Draft architecture, protocol, security, service API and UI documents.
- [ ] Import and build in Android Studio.
- [ ] Create public GitHub repository and publish initial commit.

## Phase 1 — Node foundation
- [x] Generate persistent node identity using an Android Keystore EC key pair.
- [x] Persist the node fingerprint locally.
- [x] Start/stop a user-visible local TCP health endpoint.
- [ ] Add a reusable service lifecycle abstraction and authenticated API server.

## Phase 2 — Discovery and pairing
- [ ] LAN discovery.
- [ ] Pairing request and approval UI.
- [ ] Authenticated encrypted session.
- [ ] Trust and revocation management.

## Phase 3 — Federation
- [ ] Node registry.
- [ ] Service catalog exchange.
- [ ] Multiple servers visible to a client.
- [ ] Disconnect/reconnect handling.
- [ ] Federation acceptance tests.

## Phase 4 — Files
- [ ] User-selected shared locations.
- [ ] Browse files from each owner node.
- [ ] Unified virtual index.
- [ ] Resumable transfer.
- [ ] Preview and HTTP Range support.

## Phase 5 — Media and camera
- [ ] Remote media playback.
- [ ] Camera host controls.
- [ ] WebRTC session.
- [ ] Multiple authorized viewers.

## Phase 6 — Internet gateway
- [ ] Application-level proxy.
- [ ] DropLink Browser integration.
- [ ] Gateway authorization and bandwidth policy.
- [ ] Research full-device routing separately.

## Phase 7 — Other clients
- [ ] Browser client.
- [ ] Desktop client.
- [ ] Cross-platform protocol compatibility.

## Release gates
Do not call a phase complete until acceptance tests, security review and device compatibility checks pass.
