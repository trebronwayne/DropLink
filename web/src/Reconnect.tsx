import { useEffect, useRef, useState } from 'react'
import { CloseIcon, QrIcon, RefreshIcon } from './icons'
import { Aubree } from './Aubree'

/** Turns what the user typed or scanned into a DropLink address, or null if it is not one. */
export function parseAddress(text: string): string | null {
  const raw = text.trim()
  if (!raw || /^WIFI:/i.test(raw)) return null
  try {
    const url = new URL(/^https?:\/\//i.test(raw) ? raw : `https://${raw}`)
    if (!/^https?:$/.test(url.protocol) || !url.hostname.includes('.')) return null
    return url.origin + '/'
  } catch { return null }
}

type Detector = { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> }

/** Reads QR codes with the camera. Needs HTTPS and a browser with BarcodeDetector (Chrome on Android has it). */
export function Scanner({ onClose }: { onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [message, setMessage] = useState('Starting the camera…')

  useEffect(() => {
    let stopped = false
    let stream: MediaStream | undefined
    const Detector = (window as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => Detector }).BarcodeDetector
    ;(async () => {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) { setMessage('The camera needs a secure (HTTPS) page. Use the phone’s camera app instead, or type the address.'); return }
      if (!Detector) { setMessage('This browser cannot read QR codes inside a page. Use the phone’s camera app instead, or type the address.'); return }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        if (stopped || !video.current) return
        video.current.srcObject = stream
        await video.current.play()
        const detector = new Detector({ formats: ['qr_code'] })
        setMessage('Point the camera at the QR code on the phone running DropLink.')
        const tick = async () => {
          if (stopped || !video.current) return
          try {
            const found = await detector.detect(video.current)
            const text = found[0]?.rawValue
            if (text) {
              const address = parseAddress(text)
              if (address) { stopped = true; location.href = address; return }
              setMessage(/^WIFI:/i.test(text) ? 'That is the Wi-Fi code. Join the network with the camera app, then scan the DropLink code.' : 'That code is not a DropLink address.')
            }
          } catch { /* keep trying */ }
          window.setTimeout(tick, 250)
        }
        tick()
      } catch { setMessage('Camera permission was denied. Allow it in the browser’s site settings, or type the address.') }
    })()
    return () => { stopped = true; stream?.getTracks().forEach(t => t.stop()) }
  }, [])

  return (
    <div className="overlay" onClick={onClose}>
      <section className="viewer nm-raised connect" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Scan QR code">
        <header className="viewer-bar"><QrIcon /><strong>Scan the DropLink QR code</strong><span className="spacer" />
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>
        <div className="connect-body">
          <video ref={video} className="scan-video nm-inset" playsInline muted />
          <p className="muted" role="status">{message}</p>
        </div>
      </section>
    </div>
  )
}

/** Shown when the phone cannot be reached, most often because its address changed or hosting stopped. */
export function Reconnect({ onRetry, full }: { onRetry: () => void; full?: boolean }) {
  const [scanning, setScanning] = useState(false)
  const [typed, setTyped] = useState('')
  const [bad, setBad] = useState(false)
  const go = (e: React.FormEvent) => {
    e.preventDefault()
    const a = parseAddress(typed)
    if (a) location.href = a; else setBad(true)
  }
  return (
    <div className={full ? 'center' : ''}>
      <div className={'nm-raised card reconnect' + (full ? ' pair' : '')} role="alert">
        <Aubree state="offline" size={full ? 140 : 88} />
        <h2>Can’t reach the phone</h2>
        <p className="muted">Hosting may have stopped, the phone may be on another network, or its address may have changed. DropLink keeps trying in the background.</p>
        <div className="reconnect-actions">
          <button className="nm-btn" onClick={onRetry}><RefreshIcon /> Try again</button>
          <button className="nm-btn primary" onClick={() => setScanning(true)}><QrIcon /> Scan new QR code</button>
        </div>
        <form onSubmit={go} className="stack">
          <label className="field-label" htmlFor="addr">Or type the new address</label>
          <input id="addr" className="nm-input" placeholder="192.168.43.1:47831" inputMode="url" autoCapitalize="none" autoCorrect="off" value={typed}
            onChange={e => { setTyped(e.target.value); setBad(false) }} />
          {bad && <p className="error" role="alert">That does not look like an address.</p>}
          <button className="nm-btn sm" disabled={!typed.trim()}>Open</button>
        </form>
      </div>
      {scanning && <Scanner onClose={() => setScanning(false)} />}
    </div>
  )
}
