# Android Implementation Plan

## Application identity
- Namespace: `io.github.trebronwayne.droplink`
- Application ID: `io.github.trebronwayne.droplink`
- Minimum SDK: 26 (proposed)
- Target SDK: 36 (initial configuration; verify current Play requirements before release)
- JVM target: 17

## Platform responsibilities
- Compose UI and adaptive navigation.
- Wi-Fi state and connectivity observation.
- NSD discovery on compatible local networks.
- Wi-Fi Direct integration where supported.
- Foreground service for user-visible, long-running hosting/transfer tasks.
- Storage Access Framework for user-selected files.
- CameraX or platform camera APIs for host-controlled camera service.
- Android Keystore for key protection.
- Room for local node, trust, service and transfer metadata.

## Constraints
Android versions and manufacturers differ in hotspot, Wi-Fi Direct, concurrent interface, background execution and foreground-service support. Detect capabilities at runtime and explain unsupported modes.

A local-only hotspot is not automatically an internet gateway. Internet sharing must be separately implemented and tested.

## First implementation sequence
1. Build and launch the Compose shell.
2. Add node identity generation and persistence.
3. Add local service process lifecycle.
4. Implement discovery on a shared LAN.
5. Add secure pairing.
6. Add service registry.
7. Add a basic file-listing service using user-selected directories.
8. Test with at least two physical Android devices.

## Testing
Use unit tests for identity, registry and permission decisions. Use instrumented tests for Android networking and storage APIs. Maintain a hardware compatibility matrix for Wi-Fi Direct and hotspot behavior.


## Implemented in node foundation (v0.2.0 development baseline)
- A P-256 EC signing key is generated in Android Keystore under a stable alias. The private key is non-exportable.
- The node ID is derived from the SHA-256 fingerprint of the encoded public key and stored in app preferences. Clearing app data or uninstalling the app removes the local identity; a later install creates a new node identity.
- A user-started foreground service opens a TCP listener on port `47831`. `GET /health` returns a small JSON status response. The listener currently exposes no file, camera, or administrative APIs.
- Hosting is explicit and can be stopped from the app or its persistent notification.

This is a transport/lifecycle proof of foundation, not yet the production DropLink protocol server. The health endpoint is unauthenticated and intentionally contains no private data or privileged operations. Do not add sensitive endpoints until pairing, authentication, authorization and encrypted sessions are implemented and tested.
