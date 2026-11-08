# System Architecture

## 1. Architectural style
Federated peer-to-peer nodes with a local control plane and service-oriented data plane. A coordinator may assist with membership, but no permanent central server is required.

## 2. Node roles
A node may be:
- Host: exposes services it owns.
- Client: consumes authorized services.
- Relay: forwards traffic only where routing, policy and platform capabilities explicitly allow it.

Roles are capabilities, not mutually exclusive device types.

## 3. Planes
### Control plane
Identity, pairing, discovery, membership, service registry, health and policy.

### Data plane
File transfer, range-based media delivery, WebRTC camera sessions and gateway traffic.

### Presentation plane
Android Compose, desktop client and browser client. UI must not own network business logic.

## 4. Proposed modules
- `core:identity`
- `core:security`
- `core:discovery`
- `core:federation`
- `core:transport`
- `core:service-api`
- `core:database`
- `services:files`
- `services:media`
- `services:camera`
- `services:gateway`
- `services:sync`
- `ui:design-system`
- `ui:feature-dashboard`
- `ui:feature-servers`
- `ui:feature-files`
- `ui:feature-services`

The first scaffold is intentionally smaller. Modules should be extracted as their boundaries become real and testable.

## 5. Logical federation vs physical topology
A logical network can contain multiple registered nodes. That does not imply every node has a direct physical route to every other node. The route manager must track reachability separately from membership.

Initial milestone: all nodes reachable on one shared Wi-Fi LAN or supported hotspot arrangement. Multi-hop relay is later research.

## 6. Data ownership
Every resource remains owned by its originating node. A unified directory is a virtual index, not a distributed filesystem and not an implicit replication mechanism.

## 7. Failure behavior
- One node disconnecting must not stop other nodes.
- Remote resources become unavailable, not deleted.
- Reconnection revalidates identity and service availability.
- Expired sessions are not silently trusted.
- Registry updates are idempotent and versioned.

## 8. Architecture decision records
Create an ADR for decisions that affect protocol compatibility, security, transport or persisted data. Do not silently change wire formats.
