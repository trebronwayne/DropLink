# HTTPS, certificate warnings and "no response"

DropLink hosts over HTTPS by default, so nobody on the Wi-Fi can read the files, and so browsers allow the camera, the
microphone and installing the web app. There is no internet certificate authority on a home network, so the phone makes
its own certificate. That has three consequences, and DropLink handles each one.

## What a person sees now
- **They type the address without `https://`** (for example `192.168.1.20:8080`). Browsers choose plain http for an
  address like that. Before 0.9.0 the phone could not answer and the browser showed a blank error or "no response".
  Now the phone looks at the first bytes of every connection. A real TLS connection carries on as before. A plain http
  request gets a short page with a **Continue to the secure page** button and the exact clicks for Chrome, Edge,
  Safari, Firefox and Samsung Internet. Scripts and downloads get a temporary redirect to https.
- **The browser warns that the certificate is not trusted.** This cannot be removed without a certificate authority, so
  the help page explains it in plain words, and the host screen shows the fingerprint to compare.
- **The warning mentions a name mismatch.** Browsers reject a certificate that does not name the address they used.
  The certificate now lists the phone's own addresses, `localhost` and the usual hotspot addresses. When the phone joins a
  network whose address is not listed, the certificate is issued again (same key, new address list, 397 days) and used
  immediately. Nothing needs restarting.

## Staying alive
The watchdog used to test only the server behind the HTTPS front end. It now also makes a real TLS connection to the
front end every few seconds, and the front end recovers its listener after socket errors. If HTTPS stops answering, the
server restarts by itself instead of leaving browsers with "no response".

## Phones that pair with each other
Paired phones pin the other phone's **public key**, not the certificate, so renewing the certificate for a new network
does not break a pairing.

## If a browser still cannot connect
1. Same Wi-Fi or hotspot as the phone? Some guest networks and hotspots isolate devices from each other.
2. Does DropLink on the phone say it is hosting?
3. Last resort: on the phone, Settings, **Skip the browser warning (plain HTTP)**. Files transfer, but without
   encryption, and camera sharing and installing the web app stop working.
