# Camera Nodes

A Camera Node is a browser that shares its camera and microphone with the other devices connected to your phone. Use an old phone or a tablet as a camera by the door, a baby monitor, or a quick look at another room.

The phone running DropLink does **not** need camera or microphone permission. Everything happens in the web client.

## Using it

**Share a camera** (on the device that has the camera)
1. Open DropLink in its browser and choose **Cameras**, then **Share this device**.
2. Name it ("Front door"), choose Back, Front or Both, the quality, and whether to share sound.
3. Tap **Start sharing** and allow the browser's camera prompt.
4. The phone's owner gets a notification and chooses **Allow this time**, **Always allow**, or **Deny**.
5. Keep the page open and the screen on. The page asks the browser to keep the screen awake.

**Watch** (on any other paired device)
1. Open **Cameras**. Every node is listed as Online or Offline.
2. Tap **Watch**. You can watch several at once.
3. **Listen** turns the sound on. **Snapshot** saves a picture. **Record** saves a clip (up to 10 minutes) to the device's downloads.

## Who decides

On the phone, open **Devices**. Each paired device has a **Camera Node** switch:

| Setting | Meaning |
|---|---|
| Ask me | The owner is asked each time the device starts a node. "Allow this time" lasts while the node is running. |
| Always | The device can start a node without asking. |
| Block | The device can never share a camera. Any node it had running stops. |

Every paired device can watch the nodes that are online. Revoking a device ends its node.

## How it works

```
Node browser  <--- video + sound (WebRTC, direct) --->  Viewer browser
      \                                                     /
       \---- small set-up messages (offer, answer, ICE) ---/
                          via the phone
```

- The phone keeps a list of nodes, approves them, and passes set-up messages between browsers in mailboxes read with long polling. It never sees the video or sound, so it stays cool and fast.
- Browsers connect directly over your local network. No STUN or TURN server is used, so nothing leaves your network.
- A node is **online** while it keeps checking in with the phone. If it stops for about 35 seconds, viewers see it as offline and reconnect by themselves when it returns.
- Up to 8 nodes can be online at once, 2 per device, and a node serves up to 6 viewers.

## Good to know

- **HTTPS is required.** Browsers only allow camera access on secure pages. DropLink's HTTPS mode provides that. If the browser warns about the certificate, trust it once for the node device.
- **Both cameras** work only on devices that can open two cameras at the same time. Many phones cannot, so they share one and offer **Switch camera**.
- A browser may pause a page in the background or when the screen locks. For a camera that runs all day, keep the page in front and the device plugged in.
- If a viewer cannot connect, the network may be blocking devices from reaching each other ("client isolation" on some routers and guest Wi-Fi). A phone hotspot works.
- Recordings are made in the viewer's browser and saved there, not on the phone.

## For developers

Routes (all need a paired session):

| Route | Purpose |
|---|---|
| `POST /api/camera/node/start` | Ask to become a node. Repeat until `approved`. |
| `POST /api/camera/node/update` | Report cameras, sound and viewer count. |
| `POST /api/camera/node/stop` | End the node. |
| `GET /api/camera/nodes` | List nodes with `online`, `feeds`, `mic`, `viewers`. |
| `GET /api/camera/signal` | Long poll a mailbox (`role=node\|view`, `client`, `since`, `wait`). |
| `POST /api/camera/signal` | Send `join`, `offer`, `answer`, `ice`, `bye` or `busy` to another mailbox. |

The phone stamps every message with its sender, limits queue length and message size, and rate-limits senders. See `CameraHub.kt` and `web/src/camera.tsx`.
