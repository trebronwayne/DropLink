# DropLink

**Your phone. Your server. Your network.**

DropLink is an open-source, Wi-Fi-first platform for turning phones and computers into interconnected personal servers. Nodes can provide services such as file access, media streaming, camera streaming and (in later phases) authorized internet gateway access.

> Status: early Android prototype. Persistent node identity and a minimal local health endpoint are implemented; discovery, pairing and federation are still future milestones.

## Project identity

- Android application ID: `io.github.trebronwayne.droplink`
- Primary language: Kotlin
- UI: Jetpack Compose
- Design direction: Fluent-inspired, implemented with a project-owned Compose design system
- Proposed license: Apache-2.0

## Current scope

The current node-foundation milestone establishes:
- Android application structure and Fluent-inspired Compose dashboard
- Persistent node identity derived from a P-256 Android Keystore public key
- User-controlled foreground hosting service with a local TCP `GET /health` endpoint on port `47831`
- Manual signed-release workflow with automatic semantic version progression
- Architecture, protocol, security and contribution documentation

It does **not** yet implement Wi-Fi discovery, secure pairing, server federation, file sharing, CCTV or internet routing. The health endpoint is only a connectivity proof and must not be treated as a secure service API.

## Build

Open this folder in a recent Android Studio version with Android SDK 36 installed. Android Studio can generate the Gradle wrapper if one is not present.

The project uses Android Gradle Plugin 8.10.1, Kotlin 2.0.21, Gradle 8.11.1 and Java 17. The GitHub workflow installs Gradle directly, so a Gradle wrapper is not currently required for the automated release job. This source package has not yet been built on a physical Android device or verified by a successful Gradle build; run Gradle sync/build in Android Studio and report any errors before treating it as release-ready. If you have a local Gradle installation, you can generate a wrapper with `gradle wrapper --gradle-version 8.11.1`.

## Documentation

Start with:
1. [`docs/PRODUCT_VISION.md`](docs/PRODUCT_VISION.md)
2. [`docs/SYSTEM_ARCHITECTURE.md`](docs/SYSTEM_ARCHITECTURE.md)
3. [`docs/NETWORK_PROTOCOL.md`](docs/NETWORK_PROTOCOL.md)
4. [`docs/SECURITY_MODEL.md`](docs/SECURITY_MODEL.md)
5. [`docs/SERVICE_API.md`](docs/SERVICE_API.md)
6. [`docs/ANDROID_IMPLEMENTATION.md`](docs/ANDROID_IMPLEMENTATION.md)
7. [`docs/UI_DESIGN_SYSTEM.md`](docs/UI_DESIGN_SYSTEM.md)
8. [`docs/ROADMAP.md`](docs/ROADMAP.md)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).


