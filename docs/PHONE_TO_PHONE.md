# Phone-to-phone sending

Two phones that both run DropLink can send files directly to each other.

## Pair once
1. Both phones host and share a Wi-Fi network or hotspot.
2. On phone A open **Servers**. Phone B is listed. Tap **Pair with this phone**.
3. Phone B shows a pairing request with a code. Compare it with the code on phone A, then approve.
4. To send from B to A as well, repeat from phone B.

Pairing stores a session token (the same kind a browser gets) and pins the other phone's certificate fingerprint.
Unpairing, or the other phone revoking you under Devices, removes the access.

## Send
**Transfers > Send files**: choose files, tick the paired phone(s) under *Other DropLink phones* and send. The other
phone's owner is asked, unless they set this phone to **Always** under Devices. A phone set to **Block** is shown as
blocked and cannot be chosen.

## How it works
It reuses the browser upload API (`/api/upload/request`, `/status`, `/offset`, `/file`), so all limits, approval and
resume rules are the same. Interrupted files continue from the stored offset; two files are sent at once.

## Limits and notes
- Phones must be reachable on the local network (some hotspots isolate clients).
- Keep DropLink open while sending. A wake lock and high-performance Wi-Fi lock are held during a transfer.
- Plain-HTTP hosting is supported; HTTPS hosting is verified by the pinned fingerprint.
