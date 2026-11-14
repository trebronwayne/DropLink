# Web client

React + TypeScript (Vite), in `web/`. The production build is written to `app/src/main/assets/web` and served by the phone, so any browser on the network can open `http://<phone-ip>:47831/`.

## Build
```
cd web
npm install
npm run build
```
Commit the generated `app/src/main/assets/web` so the Android build does not need Node.

## Develop against a phone
Set the proxy target in `vite.config.ts` to the phone's address, run `npm run dev`, and open the dev URL.

## Features
Pairing request with on-phone approval, shared folder list, folder browsing with breadcrumbs (hash routing), preview for images (with zoom), video, audio, PDF and text, and download for everything.

Playback: persistent player with queue (reorder, clear), shuffle, repeat, speed, sleep timer, lock-screen controls, and resume for videos and long tracks. Press `?` in the web client for the keyboard shortcuts.

Sending files to the phone: the **Send files** button (or the `U` key, or dropping files anywhere on the page) opens a dialog that asks the phone for approval and then uploads each file with progress. If the phone is set to "always allow" this device, the files go straight through.

The Settings page also has a **Support DropLink** card with a link to the project's Ko-fi page.

Accessibility and display (Settings): theme, accent colour, density, text size, reduce motion and high contrast. Dialogs trap focus, controls are labelled, and the page title and focus follow the route.

Aubree, the mascot, lives in `web/src/Aubree.tsx`. Her pictures are in `web/src/aubree/` (animation plus a still for reduced motion) and are built into hashed files, so browsers cache them for good.
