# DropLink Privacy Policy

**Effective date: 10 November 2026**

DropLink turns your phone into a small server for your own network. It is designed so that **your files and your
activity stay on your devices**. This policy explains, in plain language, what DropLink does and does not do with
information.

## The short version

- DropLink has **no cloud service, no accounts with us, no ads, no analytics and no trackers**.
- Your files are **not uploaded to us or to anyone else**. They are read from the phone only when a device you approved asks for them.
- Information travels **only between devices on your local network** (your hotspot or Wi-Fi).
- Logs and crash reports stay **on your phone** unless **you** choose to email them.

## Who is responsible

DropLink is open-source software by Trebron Wayne (contact: **trebronwayne@gmail.com**). Because the developer
runs no servers for DropLink, the developer does not receive or hold your data.

## What DropLink handles, and where it stays

### On the phone that hosts (the server)

| Information | Why | Where it is kept |
|---|---|---|
| Folders you choose to share (access is read-only) | So approved devices can browse and play them | Android remembers the folder permission you granted; no copies are made. Small preview images are held in memory only. |
| Approved devices: the name the person typed, the time, and a hash of its access token | So a device stays approved until you revoke it | Private app storage on the phone |
| A self-signed security certificate | Encrypts the connection | Private app storage on the phone |
| Hosting activity log and crash report (times, app events, technical errors, Android version, phone make) | So problems can be found and fixed | Private app storage on the phone. You can clear it any time. |
| Hotspot name and password you optionally type to draw the "join Wi-Fi" QR code | Only to draw that code | Private app storage on the phone. Never sent over the network. |
| **Host a page** (optional): the website folder you choose, accounts you create (passwords stored only as salted hashes), and information that visitors submit through your page | To run your page | Private app storage on the phone |

### In the browser of a connected device (the web client)

- **Cookies:** a session cookie that keeps the device approved, and, if you use a hosted page with sign-in, a session cookie for that sign-in. They are needed for the service to work and are not used for tracking.
- **Local storage:** theme and accent choice, favourites, recently opened items, the music queue and position, and cached folder lists. These stay in that browser and can be cleared in its settings.
- **Camera:** only if you tap **Scan QR code**, and only to read the code. Nothing is recorded or sent anywhere.
- **Offline copy:** where the browser allows it, a saved copy of the app screen so it opens quickly.

### Information you choose to send

If you tap **Email report**, **Email log** or **Send feedback**, DropLink opens your mail app with a prepared message
addressed to trebronwayne@gmail.com. A report contains the end of the log, the DropLink version, the Android
version and the phone model. **Nothing is sent unless you press send in your mail app.** It is used only to fix
problems and to reply to you.

## Sharing with others

DropLink does not sell or share your information. The only recipients are:
- **People you approve** on your network, who can see what you share and what you publish.
- **The developer**, only if you email a report or feedback.
- **GitHub**, only if you open the project page or report an issue there, under GitHub's own policy.

If you host the optional **DropLink Connect** launcher on a web host (for example GitHub Pages), that host may keep
normal visit logs. The launcher itself saves the last address only in your browser.

## If you host a page and collect information

If you publish a page and visitors send you information (for example a contact form or sign-in), **you** decide what
to collect and you are responsible for handling it lawfully and fairly, including telling your visitors what you
collect. DropLink stores that information only on your phone and does not read it, copy it or send it anywhere.

## Security

- Connections between devices are encrypted with HTTPS (a self-signed certificate; compare the fingerprint shown in the app).
- New devices must be approved on the phone, and approval can be revoked at any time.
- Shared folders are read-only. Hosted-page passwords are stored only as salted hashes.
- Testing mode (HTTP) turns encryption off. Use it only on a network you control.

No system is perfectly secure. Keep your phone updated and share only what you are comfortable sharing.

## Your choices

- Revoke any device in **Settings, Trusted devices**.
- Remove shared folders, or stop hosting, at any time.
- Clear the problem report and hosting log in the app.
- Remove hosted-page accounts and received information in **Files, Host a page**.
- Uninstalling DropLink or clearing its storage deletes everything it keeps on the phone.

## Children

DropLink is a general-purpose tool and is not directed at children under 13. It does not knowingly collect
information from children.

## Changes

If this policy changes, the new version and date will appear here in the project repository
(https://github.com/trebronwayne/DropLink). Continued use means you accept the updated policy.

## Contact

Questions, feedback or privacy requests: **trebronwayne@gmail.com**.
Bugs and feature requests: https://github.com/trebronwayne/DropLink/issues
