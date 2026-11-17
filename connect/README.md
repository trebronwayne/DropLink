# DropLink Connect (optional launcher PWA)

A tiny installable app that remembers the phone's DropLink address, opens it in one tap, and lets you scan the QR code
again when the address changes.

It must be hosted on **trusted HTTPS** (for example GitHub Pages: publish this folder). That is what makes it
installable and lets it use the camera; the phone itself uses a self-signed certificate, which browsers will not
install or cache as an app.

1. Publish this folder (for example `https://<user>.github.io/droplink-connect/`).
2. Open that address once on each client, then use the browser menu: Install app / Add to Home screen.
3. Scan the DropLink QR code. From then on the app opens the saved address; if it stops working, scan again.

QR scanning uses the browser's BarcodeDetector (Chrome on Android). Other browsers can type the address.
