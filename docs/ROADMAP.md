# Roadmap

## Coming soon
- [x] **Host a page**: publish a website from a folder, with sign-in accounts and forms that send information to the phone.
- [ ] **Live streaming**: watch a video file as the phone plays it.
- [ ] **Live camera**: use the phone as a CCTV camera.
- [ ] **Remote presentation**: control slides on a big screen from the phone. Slides will be shown from PDF or image exports; native PowerPoint rendering is a much bigger job and is planned later.
- [ ] **Send and receive files**: the operator sends to one, two or all connected users; recipients accept each file or turn on auto-accept. Users add their name on first visit.
- [x] **Uploads to the phone**: paired devices send files only after the operator approves, once or always (per device). Files are saved in Downloads/DropLink.
- [ ] **People**: see who is connected, and send them messages, files and attachments.
- [ ] **Feedback**: visitors send feedback to the operator, who can reply.
- [ ] **Richer device list**: more detail about each connected device.

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
