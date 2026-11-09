# Troubleshooting

## Is it the server or the network?
1. On the server phone, start hosting, then tap **Open on this phone**. If the page loads, the server is working.
2. Tap **Test connection**. `OK` for 127.0.0.1 but `FAIL` (or no address listed) for the hotspot or Wi-Fi address means the problem is the network link, not DropLink. The interface list shows which networks the phone currently has.
3. On the client, make sure it is joined to the same Wi-Fi or hotspot as the server phone and open the address shown on the server's Home screen.

## "It stops when the app is not on screen"
Android can pause background apps to save battery. DropLink runs as a foreground service and holds a wake lock, but phone makers add their own limits:
- Settings, Keep hosting reliable: set DropLink to unrestricted battery use.
- Turn off Battery Saver and Data Saver while hosting.
- On Tecno, Infinix, Xiaomi, Oppo, Vivo and similar phones, also allow "Auto-start" and lock the app in the recent apps list.
- If Android stops the service anyway, the Problem report on Home says "Hosting service was stopped by Android".
