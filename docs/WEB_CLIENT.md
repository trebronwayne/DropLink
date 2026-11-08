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
Pairing request with on-phone approval, shared folder list, folder browsing with breadcrumbs (hash routing), preview for images, video, audio, PDF and text, and download for everything.
