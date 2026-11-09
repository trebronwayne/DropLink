# Performance

Goal: one phone acting as the main server delivers data to several clients at once, as fast as the hardware allows.

## What limits speed (fastest to slowest part of the chain)
1. **Wi-Fi link.** A phone hotspot shares one radio between all clients. Set the hotspot to the 5 GHz band if the phone allows it; 2.4 GHz is several times slower. Keep devices close and avoid walls.
2. **Encryption (HTTPS).** Costs CPU on a phone. Compare the speed test in normal mode and in Testing mode (HTTP) to see how much.
3. **Phone storage and the Storage Access Framework.** Reading through the system file provider is slower than raw file access. The speed test skips storage, so a gap between speed-test and real file speed points here.
4. **Battery saver and thermal limits.** Both throttle the radio and CPU. Turn battery saver off while hosting.

## How to measure
- Open `https://<phone-ip>:47831/#/speed` on a client. Run with 1 stream, then 4, then 8. The download total with several streams is what the phone can deliver to several clients together.
- Watch the "Active transfers / MB/s / Served" line on the phone's Home screen during real downloads.
- Run the same tests in Testing mode (HTTP) to measure the cost of encryption.

## What the server already does
- 256 KB read buffers for file streaming and the speed test (the library default is 4 KB).
- HTTP range requests, so video and audio can seek.
- Short-lived caching of folder paths, so repeat requests skip slow file-provider lookups.
- Large transfers stream from storage and are never loaded fully into memory.

## Possible next steps if the numbers are low
- If HTTPS is much slower than HTTP: switch the TLS/server engine or the crypto provider.
- If speed-test numbers are good but file downloads are slow: read shared folders through direct file access instead of the file provider.
- If Wi-Fi is the limit: Wi-Fi Direct or a router can give a faster link than a hotspot on some phones.
