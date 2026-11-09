import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, Entry, fileUrl, LockedError, Share } from './api'
import {
  ArrowLeftIcon, ArrowRightIcon, ChevronRightIcon, CloseIcon, DownloadIcon, FileTypeIcon, GaugeIcon, GridIcon,
  Kind, ListIcon, Logo, RefreshIcon, SearchIcon, ShieldIcon, SortIcon
} from './icons'

type Route = { share: string; segments: string[] }
type SortKey = 'name' | 'size' | 'modified'
type ViewMode = 'list' | 'grid'

function parseHash(): Route {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent)
  return { share: parts[0] ?? '', segments: parts.slice(1) }
}
const toHash = (share: string, segments: string[]) =>
  '#/' + [share, ...segments].filter(Boolean).map(encodeURIComponent).join('/')

function loadPref<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const value = localStorage.getItem('droplink.' + key)
    return allowed.includes(value as T) ? (value as T) : fallback
  } catch { return fallback }
}
function savePref(key: string, value: string) {
  try { localStorage.setItem('droplink.' + key, value) } catch { /* private mode: ignore */ }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[i]}`
}
const formatDate = (ms: number) => (ms > 0 ? new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '')

const TEXT_EXT = ['txt', 'md', 'log', 'csv', 'json', 'xml', 'html', 'css', 'js', 'ts', 'kt', 'java', 'py', 'c', 'cpp', 'h', 'sh', 'yml', 'yaml', 'ini', 'srt']
const ARCHIVE_EXT = ['zip', 'rar', '7z', 'tar', 'gz', 'tgz']
function kindOf(e: Entry): Kind {
  if (e.isDir) return 'folder'
  const m = e.mime.toLowerCase()
  const ext = e.name.includes('.') ? e.name.split('.').pop()!.toLowerCase() : ''
  if (m.startsWith('image/')) return 'image'
  if (m.startsWith('video/')) return 'video'
  if (m.startsWith('audio/')) return 'audio'
  if (m === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (ARCHIVE_EXT.includes(ext) || m.includes('zip') || m.includes('compressed')) return 'archive'
  if (m.startsWith('text/') || m === 'application/json' || TEXT_EXT.includes(ext)) return 'text'
  return 'file'
}

function guessDeviceName(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : 'device'
  return `${browser} on ${os}`
}

/* ---------- Pairing ---------- */

function Pair({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState(guessDeviceName())
  const [request, setRequest] = useState<{ id: string; code: string } | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  const start = async (ev: React.FormEvent) => {
    ev.preventDefault()
    setBusy(true); setMessage('')
    try { setRequest(await api.pairRequest(name.trim())) }
    catch (e) { setMessage(e instanceof LockedError ? 'Too many requests. Wait a minute, then try again.' : 'Could not reach the phone.') }
    finally { setBusy(false) }
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
      <div className="card pair">
        <div className="pair-logo"><Logo size={72} /></div>
        <h1>DropLink</h1>
        {!request ? (
          <form onSubmit={start} className="stack">
            <p className="muted">Ask this phone for access. You approve the request on the phone, so nobody else on the network can get in.</p>
            <label className="field-label" htmlFor="device">Device name</label>
            <input id="device" className="text" value={name} maxLength={40} onChange={e => setName(e.target.value)} />
            {message && <p className="error" role="alert">{message}</p>}
            <button className="primary" disabled={busy || !name.trim()}>{busy ? 'Sending…' : 'Request access'}</button>
          </form>
        ) : (
          <div className="stack">
            <p className="muted">On the phone, check that this code matches, then tap Approve.</p>
            <div className="code" aria-live="polite">{request.code}</div>
            <p className="muted waiting"><span className="pulse" /> Waiting for approval…</p>
          </div>
        )}
        <p className="secure"><ShieldIcon width={16} height={16} /> Encrypted, local connection</p>
      </div>
    </main>
  )
}

/* ---------- Viewer ---------- */

function Viewer(props: { share: string; dir: string[]; files: Entry[]; index: number; onIndex: (i: number) => void; onClose: () => void }) {
  const { share, dir, files, index, onIndex, onClose } = props
  const entry = files[index]
  const path = [...dir, entry.name].join('/')
  const kind = kindOf(entry)
  const [text, setText] = useState('')

  useEffect(() => {
    if (kind !== 'text') return
    setText('')
    api.preview(share, path).then(setText).catch(() => setText('Could not load preview.'))
  }, [kind, share, path])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowLeft' && index > 0) onIndex(index - 1)
      else if (e.key === 'ArrowRight' && index < files.length - 1) onIndex(index + 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [index, files.length, onIndex, onClose])

  const src = fileUrl(share, path)
  return (
    <div className="overlay" onClick={onClose}>
      <section className="viewer" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={entry.name}>
        <header className="viewer-bar">
          <FileTypeIcon kind={kind} size={28} />
          <div className="viewer-title">
            <strong className="ellipsis">{entry.name}</strong>
            <span className="muted small">{formatSize(entry.size)}{entry.modified > 0 ? ` · ${formatDate(entry.modified)}` : ''} · {index + 1} of {files.length}</span>
          </div>
          <span className="spacer" />
          <button className="icon-btn" onClick={() => onIndex(index - 1)} disabled={index === 0} aria-label="Previous file"><ArrowLeftIcon /></button>
          <button className="icon-btn" onClick={() => onIndex(index + 1)} disabled={index === files.length - 1} aria-label="Next file"><ArrowRightIcon /></button>
          <a className="button" href={fileUrl(share, path, true)}><DownloadIcon /> <span className="hide-narrow">Download</span></a>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button>
        </header>
        <div className="stage">
          {kind === 'image' && <img key={path} src={src} alt={entry.name} />}
          {kind === 'video' && <video key={path} src={src} controls autoPlay playsInline />}
          {kind === 'audio' && (
            <div className="audio-card"><FileTypeIcon kind="audio" size={96} /><audio key={path} src={src} controls autoPlay /></div>
          )}
          {kind === 'pdf' && <iframe key={path} src={src} title={entry.name} />}
          {kind === 'text' && <pre>{text}{entry.size > 204800 ? '\n\n… (preview truncated)' : ''}</pre>}
          {(kind === 'archive' || kind === 'file') && (
            <div className="no-preview"><FileTypeIcon kind={kind} size={96} /><p className="muted">No preview for this file type.</p>
              <a className="button primary-link" href={fileUrl(share, path, true)}><DownloadIcon /> Download to open</a></div>
          )}
        </div>
      </section>
    </div>
  )
}

/* ---------- Folder browser ---------- */

function Breadcrumbs({ share, shareName, segments }: { share: string; shareName: string; segments: string[] }) {
  return (
    <nav className="crumbs" aria-label="Location">
      <a href={toHash(share, [])}>{shareName}</a>
      {segments.map((seg, i) => (
        <span className="crumb" key={i}><ChevronRightIcon />
          {i === segments.length - 1 ? <strong>{seg}</strong> : <a href={toHash(share, segments.slice(0, i + 1))}>{seg}</a>}</span>
      ))}
    </nav>
  )
}

function Browser({ route, shareName }: { route: Route; shareName: string }) {
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [view, setView] = useState<ViewMode>(() => loadPref('view', ['list', 'grid'] as const, 'list'))
  const [sortKey, setSortKey] = useState<SortKey>(() => loadPref('sort', ['name', 'size', 'modified'] as const, 'name'))
  const [desc, setDesc] = useState(() => loadPref('desc', ['0', '1'] as const, '0') === '1')
  const [openName, setOpenName] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const path = route.segments.join('/')

  useEffect(() => {
    setEntries(null); setError(''); setQuery(''); setOpenName(null)
    api.browse(route.share, path).then(setEntries).catch(() => setError('This folder is no longer available.'))
  }, [route.share, path, reload])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = (entries ?? []).filter(e => !q || e.name.toLowerCase().includes(q))
    return list.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      let r = 0
      if (sortKey === 'name') r = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' })
      else if (sortKey === 'size') r = a.size - b.size
      else r = a.modified - b.modified
      return desc ? -r : r
    })
  }, [entries, query, sortKey, desc])

  const files = useMemo(() => visible.filter(e => !e.isDir), [visible])
  const openIndex = openName === null ? -1 : files.findIndex(f => f.name === openName)
  const totalSize = files.reduce((sum, f) => sum + f.size, 0)

  const changeView = (v: ViewMode) => { setView(v); savePref('view', v) }
  const changeSort = (k: SortKey) => { setSortKey(k); savePref('sort', k) }
  const toggleDesc = () => { setDesc(d => { savePref('desc', d ? '0' : '1'); return !d }) }
  const join = (name: string) => [...route.segments, name].join('/')
  const activate = (e: Entry) => { if (e.isDir) location.hash = toHash(route.share, [...route.segments, e.name]); else setOpenName(e.name) }

  return (
    <>
      <Breadcrumbs share={route.share} shareName={shareName} segments={route.segments} />
      <div className="command-bar" role="toolbar" aria-label="Folder tools">
        <label className="search">
          <SearchIcon />
          <input type="search" placeholder="Filter this folder" value={query} onChange={e => setQuery(e.target.value)} aria-label="Filter this folder" />
        </label>
        <span className="spacer" />
        <label className="sort">
          <span className="hide-narrow muted small">Sort by</span>
          <select value={sortKey} onChange={e => changeSort(e.target.value as SortKey)} aria-label="Sort by">
            <option value="name">Name</option><option value="modified">Date</option><option value="size">Size</option>
          </select>
        </label>
        <button className={'icon-btn' + (desc ? ' flipped' : '')} onClick={toggleDesc} aria-label={desc ? 'Descending' : 'Ascending'} title={desc ? 'Descending' : 'Ascending'}><SortIcon /></button>
        <div className="segmented" role="group" aria-label="View">
          <button className={view === 'list' ? 'on' : ''} onClick={() => changeView('list')} aria-label="List view" aria-pressed={view === 'list'}><ListIcon /></button>
          <button className={view === 'grid' ? 'on' : ''} onClick={() => changeView('grid')} aria-label="Grid view" aria-pressed={view === 'grid'}><GridIcon /></button>
        </div>
        <button className="icon-btn" onClick={() => setReload(n => n + 1)} aria-label="Refresh"><RefreshIcon /></button>
      </div>

      {error && <p className="error banner" role="alert">{error}</p>}
      {!entries && !error && (
        <div className="card list skeleton" aria-busy="true">{[0, 1, 2, 3, 4].map(i => <div className="row" key={i}><span className="sk sk-icon" /><span className="sk sk-name" /></div>)}</div>
      )}
      {entries && visible.length === 0 && (
        <div className="empty"><FileTypeIcon kind="folder" size={72} />
          <h3>{query ? 'No matches' : 'This folder is empty'}</h3>
          <p className="muted">{query ? 'Try a different name.' : 'Files added on the phone will show up here.'}</p></div>
      )}

      {entries && visible.length > 0 && view === 'list' && (
        <div className="card list" role="list">
          <div className="row head" aria-hidden="true"><span className="name">Name</span><span className="date">Modified</span><span className="size">Size</span></div>
          {visible.map(e => (
            <div className="row-wrap" role="listitem" key={e.name}>
              <button className="row" onClick={() => activate(e)}>
                <span className="name"><FileTypeIcon kind={kindOf(e)} size={28} /><span className="ellipsis">{e.name}</span></span>
                <span className="date muted">{formatDate(e.modified)}</span>
                <span className="size muted">{e.isDir ? '' : formatSize(e.size)}</span>
              </button>
              {!e.isDir && <a className="icon-btn dl" href={fileUrl(route.share, join(e.name), true)} aria-label={`Download ${e.name}`}><DownloadIcon /></a>}
            </div>
          ))}
        </div>
      )}

      {entries && visible.length > 0 && view === 'grid' && (
        <div className="grid-view">
          {visible.map(e => {
            const kind = kindOf(e)
            const thumb = kind === 'image' && e.size > 0 && e.size <= 3 * 1024 * 1024
            return (
              <button className="tile card" key={e.name} onClick={() => activate(e)} title={e.name}>
                <span className="thumb">
                  {thumb
                    ? <img src={fileUrl(route.share, join(e.name))} alt="" loading="lazy" decoding="async" />
                    : <FileTypeIcon kind={kind} size={56} />}
                </span>
                <span className="tile-name ellipsis">{e.name}</span>
                <span className="tile-meta muted small">{e.isDir ? 'Folder' : formatSize(e.size)}</span>
              </button>
            )
          })}
        </div>
      )}

      {entries && visible.length > 0 && (
        <p className="summary muted small">{visible.length} item{visible.length === 1 ? '' : 's'}{files.length > 0 ? ` · ${formatSize(totalSize)}` : ''}</p>
      )}
      {openIndex >= 0 && (
        <Viewer share={route.share} dir={route.segments} files={files} index={openIndex}
          onIndex={i => setOpenName(files[i]?.name ?? null)} onClose={() => setOpenName(null)} />
      )}
    </>
  )
}

/* ---------- Speed test ---------- */

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
    } finally { setPhase('idle') }
  }

  return (
    <>
      <h2 className="page-title">Speed test</h2>
      <div className="card speed">
        <p className="muted">Measures the network path between this device and the phone, without reading the phone&apos;s storage. Use several streams to see what the phone can deliver to many clients at once.</p>
        <div className="controls">
          <label>Data per stream
            <select value={megabytes} onChange={e => setMegabytes(Number(e.target.value))} disabled={phase !== 'idle'}>
              {[10, 25, 50, 100].map(n => <option key={n} value={n}>{n} MB</option>)}
            </select></label>
          <label>Simultaneous streams
            <select value={streams} onChange={e => setStreams(Number(e.target.value))} disabled={phase !== 'idle'}>
              {[1, 2, 4, 8].map(n => <option key={n} value={n}>{n}</option>)}
            </select></label>
        </div>
        <button className="primary" onClick={run} disabled={phase !== 'idle'}>
          {phase === 'down' ? 'Testing download…' : phase === 'up' ? 'Testing upload…' : 'Run speed test'}
        </button>
        {result.error && <p className="error" role="alert">{result.error}</p>}
        {result.downMbps !== undefined && (
          <div className="result"><span className="big">{result.downMbps.toFixed(1)}</span><span className="muted"> Mbps download · {result.downMBps?.toFixed(1)} MB/s · {streams} stream{streams > 1 ? 's' : ''}</span></div>
        )}
        {result.upMbps !== undefined && <div className="result"><span className="big">{result.upMbps.toFixed(1)}</span><span className="muted"> Mbps upload · single stream</span></div>}
      </div>
    </>
  )
}

/* ---------- App ---------- */

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
  useEffect(() => {
    if (authed && shares.length === 1 && !route.share) location.hash = toHash(shares[0].id, [])
  }, [authed, shares, route.share])

  if (authed === null) return <main className="center"><div className="splash"><Logo size={64} /><p className="muted">Connecting…</p></div></main>
  if (!authed) return <Pair onDone={load} />

  const current = shares.find(s => s.id === route.share)
  return (
    <div className="shell">
      <header className="appbar">
        <a className="brand" href="#/"><Logo size={30} /><span>DropLink</span></a>
        <span className="spacer" />
        <span className="secure small"><ShieldIcon width={16} height={16} /> <span className="hide-narrow">Connected securely</span></span>
      </header>
      <div className="body">
        <nav className="nav" aria-label="Shared folders">
          <div className="nav-title">Shared folders</div>
          {shares.map(s => (
            <a key={s.id} className={'nav-item' + (s.id === route.share ? ' active' : '')} href={toHash(s.id, [])}>
              <FileTypeIcon kind="folder" size={22} /><span className="ellipsis">{s.name}</span>
            </a>
          ))}
          <div className="nav-sep" />
          <a className={'nav-item' + (route.share === 'speed' ? ' active' : '')} href="#/speed"><GaugeIcon /><span>Speed test</span></a>
        </nav>
        <main className="content">
          {route.share === 'speed' ? <SpeedTest /> : current ? <Browser route={route} shareName={current.name} /> : (
            <>
              <h2 className="page-title">Shared folders</h2>
              {shares.length === 0 && (
                <div className="empty"><Logo size={72} /><h3>Nothing is shared yet</h3>
                  <p className="muted">On the phone, open Files and add a folder to share.</p></div>
              )}
              <div className="grid-view">
                {shares.map(s => (
                  <a key={s.id} className="tile card" href={toHash(s.id, [])}>
                    <span className="thumb"><FileTypeIcon kind="folder" size={56} /></span>
                    <span className="tile-name ellipsis">{s.name}</span>
                  </a>
                ))}
              </div>
            </>
          )}
        </main>
      </div>
    </div>
  )
}
