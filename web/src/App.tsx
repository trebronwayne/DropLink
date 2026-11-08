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

function Login({ onDone }: { onDone: () => void }) {
  const [pin, setPin] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault()
    setBusy(true); setError('')
    try { await api.login(pin.trim()); onDone() }
    catch (e) {
      setError(e instanceof LockedError ? 'Too many attempts. Wait a minute and try again.'
        : e instanceof AuthError ? 'That access code is not correct.' : 'Could not reach the phone.')
    } finally { setBusy(false) }
  }
  return (
    <main className="center">
      <form className="card login" onSubmit={submit}>
        <h1>DropLink</h1>
        <p className="muted">Enter the access code shown on the phone to browse its shared folders.</p>
        <input className="pin" inputMode="numeric" autoComplete="one-time-code" maxLength={6} autoFocus
          placeholder="000000" value={pin} onChange={e => setPin(e.target.value.replace(/\D/g, ''))} aria-label="Access code" />
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy || pin.length < 6}>{busy ? 'Checking…' : 'Connect'}</button>
      </form>
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
  if (!authed) return <Login onDone={load} />

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
      </header>
      <main className="content">
        {!current ? (
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
