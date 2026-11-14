# Host a page

DropLink can publish a small website straight from a folder on your phone. Visitors open it in their browser. The
page can **sign people in** with accounts you create and **send information back** to your phone, all without any
cloud service.

## Set it up

1. Put your site in a folder on the phone. It needs an `index.html`. CSS, JavaScript, images, audio and video work too.
2. In DropLink open **Files, Host a page**, choose the folder and switch **Publish the page** on.
3. Start hosting. The page address is shown, for example `https://192.168.43.1:47831/site/`. Share it with the QR code from the Home screen or type it.
4. Choose who can open it:
   - **Anyone on this network can open it** (default): no approval needed.
   - Off: only devices you have approved in DropLink.

Use relative links (`style.css`, `private/members.html`) or absolute ones that start with `/site/`.

## Sign-in

Create accounts in **Host a page, Sign-in accounts**. Passwords are stored only as salted hashes, and sign-ins are
remembered only in memory, so stopping hosting signs everyone out.

Anything inside a folder named **`private`** is served only to signed-in visitors, for example
`private/members.html`. Everyone else gets "Please sign in".

## The page API

Pages talk to DropLink with `fetch`. All paths start at the site's root.

| Request | What it does | Result |
|---|---|---|
| `POST /site-api/login` with `{"username": "...", "password": "..."}` | Signs in (JSON or form-encoded body) | `200 {"ok":true,"user":"name"}`, `401` wrong details, `429` too many attempts |
| `GET /site-api/me` | Who is signed in | `200 {"user":"name"}` or `{"user":null}` |
| `POST /site-api/logout` | Signs out | `200 {"ok":true}` |
| `POST /site-api/submit/<form>` with a JSON or form body | Sends information to the phone | `200 {"ok":true}`; `503` if busy or storage is full |

```js
// sign in
const r = await fetch('/site-api/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username, password })
})
if (r.ok) location.href = 'private/members.html'

// send information to the host
await fetch('/site-api/submit/contact', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ name, message })
})
```

`<form>` is a name you choose (letters, digits, `-` and `_`, up to 40 characters). Received items are listed in
**Host a page, Information sent to you**, where you can copy them as JSON or clear them. Each item records the time,
the form name, the fields and, if the visitor was signed in, their account name.

## Limits

- Login body up to 8 KB, submissions up to 64 KB, at most 50 fields of 4,000 characters each.
- Forms must be sent as JSON or `application/x-www-form-urlencoded` (file uploads are not supported yet).
- 5 wrong passwords for an account lock it for 1 minute. At most 120 submissions per minute; up to about 5 MB stored.
- Sign-ins last up to 12 hours.

## Good to know

- The page is served from the **same address as DropLink**, so only publish pages you trust.
- The connection uses DropLink's self-signed certificate, so visitors see a browser warning the first time (see the fingerprint shown in the app). Use **Testing mode (HTTP)** only on networks you control.
- If you collect information from visitors, you decide what to collect and you are responsible for it; tell your visitors. See the [Privacy Policy](../PRIVACY.md).

## Try the sample

`samples/login-site/` is a complete example with a sign-in form, a contact form and a members-only page. Copy the
folder to your phone, choose it as the website folder, and add an account.
