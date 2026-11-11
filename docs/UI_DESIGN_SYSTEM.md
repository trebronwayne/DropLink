# DropLink Fluent Design System

## Direction
A premium, Windows 11-inspired interface implemented natively in Jetpack Compose. Use Fluent 2 as a design reference, not as a mandatory runtime dependency.

## Principles
- Clear hierarchy and generous spacing.
- Rounded surfaces with restrained depth.
- Fluent-inspired blue accent.
- Light and dark themes.
- Motion that communicates state.
- Accessible contrast and touch targets.
- Responsive layouts for phone, tablet and desktop.

## Tokens
Centralize colors, typography, spacing, corner radii, elevation and motion durations. Avoid hard-coded values in feature screens.

## Navigation
Phone: bottom navigation or compact navigation drawer.
Tablet: navigation rail.
Desktop: persistent sidebar and multi-pane workspace.

## Main destinations
- Home: network status and activity.
- Servers: all known nodes and their services.
- Files: unified virtual library, grouped/filterable by owner.
- Services: camera, media, gateway and sync.
- Transfers: active, queued and completed jobs.
- Network: topology, reachability and diagnostics.
- Settings: identity, security, appearance and storage.

## Component library
Build reusable Compose components such as `DropCard`, `DropButton`, `DropDeviceCard`, `DropServiceCard`, `DropStatusIndicator`, `DropDialog`, `DropFileRow`, `DropTransferProgress` and `DropAcrylicSurface`.

## Fluent UI framework decision
Decided (v0.3.0): pure Jetpack Compose with a DropLink-owned Fluent 2 style token and component system, plus Material icons. No Fluent runtime library is used, which avoids Compose version conflicts and keeps the design portable to the future desktop client.

## Where things live
- `ui/theme/DropTokens.kt`: colors (light/dark), radii, spacing, type ramp.
- `ui/theme/DropLinkTheme.kt`: `DropLinkTheme` and the `DropTheme.colors` / `DropTheme.type` accessors. Material3 sits underneath only for stock behavior.
- `ui/components/DropComponents.kt`: DropCard, DropButton, DropIconTile, DropStatusIndicator, DropInfoCard, DropEmptyState, DropDeviceCard.
- `ui/DropLinkApp.kt`: adaptive navigation (bottom bar below 600dp, navigation pane above) and screens.
