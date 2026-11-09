import { useCallback, useEffect, useState } from 'react'
import { api, AuthError, Entry, fileUrl, LockedError, Share } from './api'

type Route = { share: string; segments: string[] }

function parseHash(): Route {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent)
  return { share: parts[0] ?? '', segments: parts.slice(1) }
}
const toHash = (share: string, segments: string[]) =>
  '#/' + [share, ...segments].filter(Boolean).map(encodeURIComponent).join('/')

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[i]}`
}

type Kind = 'folder' | 'image' | 'video' | 'audio' | 'pdf' | 'text' | 'file'
function kindOf(e: Entry): Kind {
  if (e.isDir) return 'folder'
  const m = e.mime.toLowerCase()
  if (m.startsWith('image/')) return 'image'
  if (m.startsWith('video/')) return 'video'
  if (m.startsWith('audio/')) return 'audio'
  if (m === 'application/pdf') return 'pdf'
  if (m.startsWith('text/') || m === 'application/json') return 'text'
  return 'file'
}
const BADGE: Record<Kind, string> = { folder: '📁', image: '🖼️', video: '🎬', audio: '🎵', pdf: '📕', text: '📄', file: '📦' }

function guessDeviceName(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : 'device'
  return `${browser} on ${os}`
}

function Pair({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState(guessDeviceName())
  const [request, setRequest] = useState<{ id: string; code: string } | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  const start = async (ev: React.FormEvent) => {
    ev.preventDefault()
    setBusy(true); setMessage('')
    try { setRequest(await api.pairRequest(name.trim())) }
    catch (e) {
      setMessage(e instanceof LockedError ? 'Too many requests. Wait a minute, then try again.' : 'Could not reach the phone.')
    } finally { setBusy(false) }
  }

  useEffect(() => {
    if (!request) return
    const timer = window.setInterval(async () => {
      try {
        const status = await api.pairStatus(request.id)
        if (status === 'approved') { window.clearInterval(timer); onDone() }
        else if (status !== 'pending') {
          window.clearInterval(timer); setRequest(null)
          setMessage(status === 'denied' ? 'The request was denied on the phone.' : 'The request expired. Try again.')
        }
      } catch { /* keep polling */ }
    }, 1500)
    return () => window.clearInterval(timer)
  }, [request, onDone])

  return (
    <main className="center">
      <div className="card login">
        <h1>DropLink</h1>
        {!request ? (
          <form onSubmit={start} className="login">
            <p className="muted">Ask this phone for access. You will need to approve the request on the phone.</p>
            <label className="muted" htmlFor="device">Device name</label>
            <input id="device" className="text" value={name} maxLength={40} onChange={e => setName(e.target.value)} />
            {message && <p className="error" role="alert">{message}</p>}
            <button className="primary" disabled={busy || !name.trim()}>{busy ? 'Sending…' : 'Request access'}</button>
          </form>
        ) : (
          <>
            <p className="muted">On the phone, check that this code matches, then tap Approve.</p>
            <div className="pin" aria-live="polite">{request.code}</div>
            <p className="muted">Waiting for approval…</p>
          </>
        )}
      </div>
    </main>
  )
}

function Preview({ share, path, entry, onClose }: { share: string; path: string; entry: Entry; onClose: () => void }) {
  const kind = kindOf(entry)
  const src = fileUrl(share, path)
  const [text, setText] = useState('')
  useEffect(() => {
    if (kind === 'text') api.preview(share, path).then(setText).catch(() => setText('Could not load preview.'))
  }, [kind, share, path])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onClick={onClose}>
      <section className="viewer card" onClick={e => e.stopPropagation()} role="dialog" aria-label={entry.name}>
        <header>
          <strong className="ellipsis">{entry.name}</strong>
          <span className="spacer" />
          <a className="button" href={fileUrl(share, path, true)}>Download</a>
          <button onClick={onClose}>Close</button>
        </header>
        <div className="stage">
          {kind === 'image' && <img src={src} alt={entry.name} />}
          {kind === 'video' && <video src={src} controls autoPlay playsInline />}
          {kind === 'audio' && <audio src={src} controls autoPlay />}
          {kind === 'pdf' && <iframe src={src} title={entry.name} />}
          {kind === 'text' && <pre>{text}{entry.size > 204800 ? '\n\n… (preview truncated)' : ''}</pre>}
          {kind === 'file' && <p className="muted">No preview for this file type. Use Download to open it on this device.</p>}
        </div>
      </section>
    </div>
  )
}

async function downloadBytes(megabytes: number): Promise<number> {
  const response = await fetch(`/api/speedtest/down?mb=${megabytes}`, { cache: 'no-store' })
  if (!response.ok || !response.body) throw new Error('download failed')
  const reader = response.body.getReader()
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.length
  }
  return total
}

type SpeedResult = { downMbps?: number; downMBps?: number; upMbps?: number; error?: string }

function SpeedTest() {
  const [megabytes, setMegabytes] = useState(25)
  const [streams, setStreams] = useState(1)
  const [phase, setPhase] = useState<'idle' | 'down' | 'up'>('idle')
  const [result, setResult] = useState<SpeedResult>({})

  const run = async () => {
    setResult({})
    try {
      setPhase('down')
      const t0 = performance.now()
      const sizes = await Promise.all(Array.from({ length: streams }, () => downloadBytes(megabytes)))
      const seconds = (performance.now() - t0) / 1000
      const bytes = sizes.reduce((sum, n) => sum + n, 0)
      setResult(r => ({ ...r, downMbps: (bytes * 8) / seconds / 1e6, downMBps: bytes / seconds / 1048576 }))

      setPhase('up')
      const body = new Blob([new Uint8Array(Math.min(megabytes, 25) * 1024 * 1024)])
      const u0 = performance.now()
      const upload = await fetch('/api/speedtest/up', { method: 'POST', body })
      if (!upload.ok) throw new Error('upload failed')
      const useconds = (performance.now() - u0) / 1000
      setResult(r => ({ ...r, upMbps: (body.size * 8) / useconds / 1e6 }))
    } catch {
      setResult({ error: 'The test failed. Check the connection to the phone and try again.' })
    } finally {
      setPhase('idle')
    }
  }

  return (
    <>
      <h2>Speed test</h2>
      <div className="card speed">
        <p className="muted">
          Measures the network path between this device and the phone, without reading the phone&apos;s storage.
          Use several streams to see what the phone can deliver to many clients at once.
        </p>
        <div className="controls">
          <label>Data per stream
            <select value={megabytes} onChange={e => setMegabytes(Number(e.target.value))} disabled={phase !== 'idle'}>
              {[10, 25, 50, 100].map(n => <option key={n} value={n}>{n} MB</option>)}
            </select>
          </label>
          <label>Simultaneous streams
            <select value={streams} onChange={e => setStreams(Number(e.target.value))} disabled={phase !== 'idle'}>
              {[1, 2, 4, 8].map(n => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
        <button className="primary" onClick={run} disabled={phase !== 'idle'}>
          {phase === 'down' ? 'Testing download…' : phase === 'up' ? 'Testing upload…' : 'Run speed test'}
        </button>
        {result.error && <p className="error" role="alert">{result.error}</p>}
        {result.downMbps !== undefined && (
          <p><strong>Download:</strong> {result.downMbps.toFixed(1)} Mbps ({result.downMBps?.toFixed(1)} MB/s) with {streams} stream{streams > 1 ? 's' : ''}</p>
        )}
        {result.upMbps !== undefined && <p><strong>Upload:</strong> {result.upMbps.toFixed(1)} Mbps (single stream)</p>}
      </div>
    </>
  )
}

function Browser({ route }: { route: Route }) {
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [error, setError] = useState('')
  const [open, setOpen] = useState<Entry | null>(null)
  const path = route.segments.join('/')
  useEffect(() => {
    setEntries(null); setError('')
    api.browse(route.share, path).then(setEntries).catch(() => setError('This folder is no longer available.'))
  }, [route.share, path])
  const join = (name: string) => [...route.segments, name].join('/')
  return (
    <>
      {error && <p className="error">{error}</p>}
      {!entries && !error && <p className="muted">Loading…</p>}
      {entries && entries.length === 0 && <p className="muted">This folder is empty.</p>}
      {entries && entries.length > 0 && (
        <ul className="list card">
          {entries.map(e => (
            <li key={e.name}>
              <button className="row" onClick={() => e.isDir ? (location.hash = toHash(route.share, [...route.segments, e.name])) : setOpen(e)}>
                <span className="badge">{BADGE[kindOf(e)]}</span>
                <span className="name ellipsis">{e.name}</span>
                <span className="meta muted">{e.isDir ? '' : formatSize(e.size)}</span>
              </button>
              {!e.isDir && <a className="dl" href={fileUrl(route.share, join(e.name), true)} aria-label={`Download ${e.name}`}>⬇</a>}
            </li>
          ))}
        </ul>
      )}
      {open && <Preview share={route.share} path={join(open.name)} entry={open} onClose={() => setOpen(null)} />}
    </>
  )
}

export default function App() {
  const [authed, setAuthed] = useState<boolean | null>(null)
  const [shares, setShares] = useState<Share[]>([])
  const [route, setRoute] = useState<Route>(parseHash())

  const load = useCallback(async () => {
    try { await api.info(); setShares(await api.shares()); setAuthed(true) }
    catch { setAuthed(false) }
  }, [])
  useEffect(() => { load() }, [load])
  useEffect(() => {
    const onHash = () => setRoute(parseHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  if (authed === null) return <main className="center"><p className="muted">Connecting…</p></main>
  if (!authed) return <Pair onDone={load} />

  const current = shares.find(s => s.id === route.share)
  return (
    <div className="app">
      <header className="top">
        <a className="brand" href="#/">DropLink</a>
        <nav className="crumbs" aria-label="Location">
          {current && <>
            <a href="#/">Shared folders</a>
            <span>/</span>
            <a href={toHash(current.id, [])}>{current.name}</a>
            {route.segments.map((seg, i) => (
              <span key={i} className="crumb-part"><span>/</span>
                <a href={toHash(current.id, route.segments.slice(0, i + 1))}>{seg}</a></span>
            ))}
          </>}
        </nav>
        <a className="navlink" href="#/speed">Speed test</a>
      </header>
      <main className="content">
        {route.share === 'speed' ? <SpeedTest /> : !current ? (
          <>
            <h2>Shared folders</h2>
            {shares.length === 0 && <p className="muted">Nothing is shared yet. On the phone, open Files and add a folder.</p>}
            <div className="grid">
              {shares.map(s => (
                <a key={s.id} className="card tile" href={toHash(s.id, [])}>
                  <span className="badge big">📁</span><strong className="ellipsis">{s.name}</strong>
                </a>
              ))}
            </div>
          </>
        ) : <Browser route={route} />}
      </main>
    </div>
  )
}
