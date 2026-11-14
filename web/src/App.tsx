import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, AuthError, qrUrl, cachedBrowse, Entry, fileUrl, LockedError, OfflineError, Share, thumbUrl } from './api'
import {
  ArrowLeftIcon, ArrowRightIcon, CheckIcon, ChevronRightIcon, ClockIcon, CloseIcon, DownloadIcon, FileTypeIcon, GaugeIcon, GridIcon,
  HeartIcon, HelpIcon, HomeIcon, KeyboardIcon, ListIcon, Logo, OfflineIcon, PlayIcon, PlusIcon, QrIcon, RefreshIcon, SearchIcon, SettingsIcon, ShieldIcon, SortIcon, StarFillIcon, StarIcon,
  UploadIcon, ZoomInIcon, ZoomOutIcon
} from './icons'
import { Reconnect } from './Reconnect'
import { Aubree, AubreeState } from './Aubree'
import { PlayerProvider, Track, usePlayer } from './player'
import { ACCENTS, Display, getResume, load, Ref, save, ThemeMode, useDisplay, useFavorites, useRecents, useSetting, useTheme } from './prefs'
import { ToastProvider, useToast } from './toast'
import { useDialog } from './a11y'
import { UploadDialog } from './upload'
import { IdentifyDialog, InboxPage, useInbox } from './inbox'
import { browserName, detectModel, guessDeviceName, osName, saveWho, savedWho } from './device'
import { formatDate, formatSize, formatTime, Kind, kindOf, stripExt } from './util'

type Route = { share: string; segments: string[] }
type SortKey = 'name' | 'size' | 'modified'
type ViewMode = 'list' | 'grid'
type Filter = 'all' | 'image' | 'video' | 'audio' | 'doc'
const PAGES = ['speed', 'favorites', 'recent', 'settings', 'inbox']

function parseHash(): Route {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent)
  return { share: parts[0] ?? '', segments: parts.slice(1) }
}
const toHash = (share: string, segments: string[]) => '#/' + [share, ...segments].filter(Boolean).map(encodeURIComponent).join('/')

const isMedia = (k: Kind) => k === 'audio' || k === 'video'
const matchesFilter = (k: Kind, f: Filter) => f === 'all' || (f === 'doc' ? k === 'pdf' || k === 'text' || k === 'file' || k === 'archive' : k === f)

/** Thin progress line for videos and long tracks the visitor has started. */
function Progress({ share, path }: { share: string; path: string }) {
  const rs = getResume(share, path)
  if (!rs || !rs.d) return null
  return <span className="bar row-bar" aria-hidden="true"><span style={{ width: Math.min(100, Math.round((rs.t / rs.d) * 100)) + '%' }} /></span>
}

const SHORTCUTS: [string, string][] = [
  ['Space', 'Play or pause'], ['J  /  L', 'Back or forward 10 seconds'], ['←  /  →', 'Back or forward 5 seconds'],
  ['N  /  P', 'Next or previous track'], ['M', 'Mute or unmute'], ['↑  /  ↓', 'Volume (full player)'],
  ['F', 'Fullscreen video'], ['S  /  R', 'Shuffle / repeat'], ['>', 'Change playback speed'],
  ['/', 'Filter this folder'], ['U', 'Send files to this phone'], ['?', 'Show this list'], ['Esc', 'Close a window or the full player']
]

function ShortcutsDialog({ onClose }: { onClose: () => void }) {
  const ref = useDialog<HTMLElement>(onClose)
  return (
    <div className="overlay" onClick={onClose}>
      <section ref={ref} className="viewer nm-raised shortcuts" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Keyboard shortcuts" tabIndex={-1}>
        <header className="viewer-bar"><KeyboardIcon /><strong>Keyboard shortcuts</strong><span className="spacer" />
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>
        <dl className="shortcut-list">
          {SHORTCUTS.map(([keys, action]) => (
            <div key={keys}><dt>{keys.split('  /  ').map((k, i) => <span key={i} className="keyset">{i > 0 && <span className="muted">or</span>}<kbd>{k}</kbd></span>)}</dt><dd>{action}</dd></div>
          ))}
        </dl>
      </section>
    </div>
  )
}

/* ---------- Pairing ---------- */

function Pair({ onDone }: { onDone: () => void }) {
  const [person, setPerson] = useState(savedWho().person)
  const [model, setModel] = useState(savedWho().model)
  const [request, setRequest] = useState<{ id: string; code: string } | null>(null)
  const [message, setMessage] = useState('')
  const [mood, setMood] = useState<AubreeState>('welcome')
  const [approved, setApproved] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => { detectModel().then(m => setModel(cur => cur || m)) }, [])

  const start = async (ev: React.FormEvent) => {
    ev.preventDefault()
    setBusy(true); setMessage('')
    try {
      saveWho({ person: person.trim(), model: model.trim() })
      setRequest(await api.pairRequest({ name: guessDeviceName(), person: person.trim(), model: model.trim(), platform: `${browserName()} on ${osName()}` })); setMood('wait')
    }
    catch (e) {
      if (e instanceof LockedError) { setMessage('Too many requests. Wait a minute, then try again.'); setMood('sleepy') }
      else { setMessage('Could not reach the phone.'); setMood('offline') }
    }
    finally { setBusy(false) }
  }

  useEffect(() => {
    if (!request) return
    const timer = window.setInterval(async () => {
      try {
        const status = await api.pairStatus(request.id)
        if (status === 'approved') {
          window.clearInterval(timer)
          setApproved(true); setMood('celebrate')
          window.setTimeout(onDone, 1500) // a moment for Aubree to celebrate
        } else if (status !== 'pending') {
          window.clearInterval(timer); setRequest(null)
          setMessage(status === 'denied' ? 'The request was denied on the phone.' : 'The request expired. Try again.')
          setMood(status === 'denied' ? 'sad' : 'sleepy')
        }
      } catch { /* keep polling */ }
    }, 1500)
    return () => window.clearInterval(timer)
  }, [request, onDone])

  return (
    <main className="center">
      <div className="nm-raised card pair">
        <Aubree state={mood} size={144} />
        <h1>DropLink</h1>
        {approved ? (
          <div className="stack">
            <p className="muted" role="status">Approved. Opening your files…</p>
          </div>
        ) : !request ? (
          <form onSubmit={start} className="stack">
            <p className="muted">Ask this phone for access. You approve the request on the phone, so nobody else on the network can get in.</p>
            <label className="field-label" htmlFor="who-name">Your name</label>
            <input id="who-name" className="nm-input" value={person} maxLength={40} placeholder="For example Sarah" autoComplete="name" onChange={e => setPerson(e.target.value)} />
            <label className="field-label" htmlFor="who-model">Device model <span className="muted">(optional)</span></label>
            <input id="who-model" className="nm-input" value={model} maxLength={60} placeholder="For example Samsung Galaxy A14" onChange={e => setModel(e.target.value)} />
            <p className="muted small">Browsers hide your device’s name and model, so the phone’s owner relies on what you type to know who is asking. Detected: {guessDeviceName()}.</p>
            {message && <p className="error" role="alert">{message}</p>}
            <button className="nm-btn primary" disabled={busy || !person.trim()}>{busy ? 'Sending…' : 'Request access'}</button>
          </form>
        ) : (
          <div className="stack">
            <p className="muted">On the phone, check that this code matches, then tap Approve.</p>
            <div className="code nm-inset" aria-live="polite">{request.code}</div>
            <p className="muted waiting"><span className="pulse" /> Waiting for approval…</p>
          </div>
        )}
        <p className="secure"><ShieldIcon width={16} height={16} /> Encrypted, local connection · <a href="/privacy.html">Privacy</a></p>
      </div>
    </main>
  )
}

/* ---------- Viewer (images, PDF, text, other). Audio and video go to the persistent player. ---------- */

function Viewer(props: { share: string; dir: string[]; files: Entry[]; index: number; onIndex: (i: number) => void; onClose: () => void }) {
  const { share, dir, files, index, onIndex, onClose } = props
  const entry = files[index]
  const path = [...dir, entry.name].join('/')
  const kind = kindOf(entry)
  const [text, setText] = useState('')
  const [zoom, setZoom] = useState(1)
  const touch = useRef<number | null>(null)
  const dialog = useDialog<HTMLElement>(onClose)
  useEffect(() => { setZoom(1) }, [path])

  useEffect(() => {
    if (kind !== 'text') return
    setText('')
    api.preview(share, path).then(setText).catch(() => setText('Could not load preview.'))
  }, [kind, share, path])

  // Preload the neighbouring images so paging through photos is instant.
  useEffect(() => {
    for (const d of [1, -1, 2]) {
      const n = files[index + d]
      if (n && kindOf(n) === 'image') new Image().src = fileUrl(share, [...dir, n.name].join('/'))
    }
  }, [index, files, share, dir])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (/INPUT|SELECT|TEXTAREA/.test(t.tagName)) return
      if (e.key === 'ArrowLeft' && index > 0 && zoom === 1) onIndex(index - 1)
      else if (e.key === 'ArrowRight' && index < files.length - 1 && zoom === 1) onIndex(index + 1)
      else if (e.key === '+' || e.key === '=') setZoom(z => Math.min(4, z + 0.5))
      else if (e.key === '-') setZoom(z => Math.max(1, z - 0.5))
      else if (e.key === '0') setZoom(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [index, files.length, onIndex, zoom])

  const src = fileUrl(share, path)
  return (
    <div className="overlay" onClick={onClose}>
      <section ref={dialog} tabIndex={-1} className="viewer nm-raised" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={entry.name}
        onTouchStart={e => { touch.current = e.touches[0].clientX }}
        onTouchEnd={e => {
          if (touch.current === null) return
          const dx = e.changedTouches[0].clientX - touch.current
          touch.current = null
          if (dx > 70 && index > 0) onIndex(index - 1)
          else if (dx < -70 && index < files.length - 1) onIndex(index + 1)
        }}>
        <header className="viewer-bar">
          <FileTypeIcon kind={kind} size={28} />
          <div className="viewer-title">
            <strong className="ellipsis">{entry.name}</strong>
            <span className="muted small">{formatSize(entry.size)}{entry.modified > 0 ? ` · ${formatDate(entry.modified)}` : ''} · {index + 1} of {files.length}</span>
          </div>
          <span className="spacer" />
          {kind === 'image' && <>
            <button className="icon-btn" onClick={() => setZoom(z => Math.max(1, z - 0.5))} disabled={zoom <= 1} aria-label="Zoom out"><ZoomOutIcon /></button>
            <button className="icon-btn" onClick={() => setZoom(z => Math.min(4, z + 0.5))} disabled={zoom >= 4} aria-label="Zoom in"><ZoomInIcon /></button>
          </>}
          <button className="icon-btn" onClick={() => onIndex(index - 1)} disabled={index === 0} aria-label="Previous file"><ArrowLeftIcon /></button>
          <button className="icon-btn" onClick={() => onIndex(index + 1)} disabled={index === files.length - 1} aria-label="Next file"><ArrowRightIcon /></button>
          <a className="nm-btn" href={fileUrl(share, path, true)}><DownloadIcon /> <span className="hide-narrow">Download</span></a>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button>
        </header>
        <div className={'stage' + (zoom > 1 ? ' zoomed' : '')}>
          {kind === 'image' && <img key={path} src={src} alt={entry.name} style={zoom > 1 ? { width: `${zoom * 100}%`, maxWidth: 'none', maxHeight: 'none' } : undefined}
            onDoubleClick={() => setZoom(z => (z > 1 ? 1 : 2))} />}
          {kind === 'pdf' && <iframe key={path} src={src} title={entry.name} />}
          {kind === 'text' && <pre>{text}{entry.size > 204800 ? '\n\n… (preview truncated)' : ''}</pre>}
          {(kind === 'archive' || kind === 'file') && (
            <div className="no-preview"><FileTypeIcon kind={kind} size={96} /><p className="muted">No preview for this file type.</p>
              <a className="nm-btn primary" href={fileUrl(share, path, true)}><DownloadIcon /> Download to open</a></div>
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

type RowProps = {
  e: Entry; share: string; dir: string[]; view: ViewMode; selecting: boolean; selected: boolean; fav: boolean; playingNow: boolean
  onOpen: (e: Entry) => void; onSelect: (e: Entry) => void; onFav: (e: Entry) => void; onQueue: (e: Entry) => void
}
const Item = memo(function Item({ e, share, dir, view, selecting, selected, fav, playingNow, onOpen, onSelect, onFav, onQueue }: RowProps) {
  const kind = kindOf(e)
  const path = [...dir, e.name].join('/')
  const prefetch = () => { if (e.isDir) api.prefetch(share, path) }
  const media = isMedia(kind)
  const click = () => (selecting ? onSelect(e) : onOpen(e))
  const check = selecting && <span className={'check' + (selected ? ' on' : '')} aria-hidden="true">{selected && <CheckIcon width={14} height={14} />}</span>

  if (view === 'grid') {
    const thumb = (kind === 'image' || kind === 'video' || kind === 'pdf') && e.size > 0
    return (
      <div className={'tile nm-raised' + (selected ? ' selected' : '') + (playingNow ? ' playing' : '')} onPointerEnter={prefetch} onTouchStart={prefetch}>
        <button className="tile-hit" onClick={click} title={e.name}>
          <span className="thumb nm-inset">
            {thumb ? <img src={thumbUrl(share, path)} alt="" loading="lazy" decoding="async" onError={ev => { ev.currentTarget.style.display = 'none' }} /> : null}
            <span className="thumb-icon"><FileTypeIcon kind={kind} size={52} /></span>
            {media && !selecting && <span className="thumb-play"><PlayIcon width={18} height={18} /></span>}
          </span>
          <span className="tile-name ellipsis">{e.name}</span>
          <span className="tile-meta muted small">{e.isDir ? 'Folder' : formatSize(e.size)}</span>
          {check}
        </button>
        {!selecting && <button className={'icon-btn fav' + (fav ? ' on' : '')} onClick={() => onFav(e)} aria-pressed={fav} aria-label={`Favorite ${e.name}`}>{fav ? <StarFillIcon /> : <StarIcon />}</button>}
        {media && <Progress share={share} path={path} />}
      </div>
    )
  }
  return (
    <div className={'row-wrap' + (selected ? ' selected' : '') + (playingNow ? ' playing' : '')} role="listitem" onPointerEnter={prefetch} onTouchStart={prefetch}>
      <button className="row" onClick={click}>
        {check}
        <span className="name"><FileTypeIcon kind={kind} size={28} /><span className="ellipsis">{e.name}</span></span>
        <span className="date muted">{formatDate(e.modified)}</span>
        <span className="size muted">{e.isDir ? '' : formatSize(e.size)}</span>
      </button>
      {!selecting && (
        <span className="row-actions">
          {media && <button className="icon-btn" onClick={() => onQueue(e)} aria-label={`Add ${e.name} to queue`} title="Add to queue"><PlusIcon /></button>}
          <button className={'icon-btn' + (fav ? ' on' : '')} onClick={() => onFav(e)} aria-pressed={fav} aria-label={`Favorite ${e.name}`} title="Favorite">{fav ? <StarFillIcon /> : <StarIcon />}</button>
          {!e.isDir && <a className="icon-btn" href={fileUrl(share, path, true)} aria-label={`Download ${e.name}`} title="Download"><DownloadIcon /></a>}
        </span>
      )}
      {media && <Progress share={share} path={path} />}
    </div>
  )
})

const CHUNK = 120

function Browser({ route, shareName, fav, recents }: { route: Route; shareName: string; fav: ReturnType<typeof useFavorites>; recents: ReturnType<typeof useRecents> }) {
  const path = route.segments.join('/')
  const player = usePlayer()
  const toast = useToast()
  const [entries, setEntries] = useState<Entry[] | null>(() => cachedBrowse(route.share, path) ?? null)
  const [error, setError] = useState('')
  const [offline, setOffline] = useState(false)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [view, setView] = useState<ViewMode>(() => load('view', 'list'))
  const [sortKey, setSortKey] = useState<SortKey>(() => load('sort', 'name'))
  const [desc, setDesc] = useState(() => load('desc', false))
  const [openName, setOpenName] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [selecting, setSelecting] = useState(false)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [limit, setLimit] = useState(CHUNK)
  const sentinel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Paint the cached listing instantly, then refresh it in the background.
    const cached = cachedBrowse(route.share, path)
    setEntries(cached ?? null); setError(''); setQuery(''); setOpenName(null); setSelecting(false); setPicked(new Set()); setLimit(CHUNK); setFilter('all')
    let alive = true
    api.browse(route.share, path, reload > 0).then(d => { if (alive) { setEntries(d); setOffline(false) } })
      .catch(e => { if (!alive) return; if (e instanceof OfflineError) setOffline(true); else if (!cached) setError('This folder is no longer available.') })
    return () => { alive = false }
  }, [route.share, path, reload])

  // Reconnect automatically when the network or the phone comes back, so you never have to babysit the tab.
  useEffect(() => {
    if (!offline) return
    const id = window.setInterval(async () => { if (await api.ping()) setReload(n => n + 1) }, 3000)
    return () => window.clearInterval(id)
  }, [offline])
  useEffect(() => {
    const onVis = () => { if (document.visibilityState === 'visible') api.browse(route.share, path).then(setEntries).catch(() => undefined) }
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  }, [route.share, path])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = (entries ?? []).filter(e => (!q || e.name.toLowerCase().includes(q)) && (e.isDir || matchesFilter(kindOf(e), filter)) && (filter === 'all' || !e.isDir || !!q))
    return list.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      let r = 0
      if (sortKey === 'name') r = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' })
      else if (sortKey === 'size') r = a.size - b.size
      else r = a.modified - b.modified
      return desc ? -r : r
    })
  }, [entries, query, sortKey, desc, filter])

  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver(es => { if (es[0].isIntersecting) setLimit(l => l + CHUNK) }, { rootMargin: '600px' })
    io.observe(el)
    return () => io.disconnect()
  }, [visible.length, limit])

  const files = useMemo(() => visible.filter(e => !e.isDir), [visible])
  const viewable = useMemo(() => files.filter(f => !isMedia(kindOf(f))), [files])
  const mediaFiles = useMemo(() => files.filter(f => isMedia(kindOf(f))), [files])
  const openIndex = openName === null ? -1 : viewable.findIndex(f => f.name === openName)
  const totalSize = files.reduce((sum, f) => sum + f.size, 0)
  const join = useCallback((name: string) => [...route.segments, name].join('/'), [route.segments])

  const toTrack = useCallback((f: Entry): Track => ({ share: route.share, path: join(f.name), name: f.name, kind: kindOf(f) as 'audio' | 'video', size: f.size }), [route.share, join])
  const ref = useCallback((e: Entry): Ref => ({ share: route.share, path: join(e.name), name: e.name, kind: kindOf(e), size: e.size }), [route.share, join])

  const onOpen = useCallback((e: Entry) => {
    if (e.isDir) { location.hash = toHash(route.share, [...route.segments, e.name]); return }
    recents.add(ref(e))
    if (isMedia(kindOf(e))) {
      const queue = mediaFiles.map(toTrack)
      player.play(queue, Math.max(0, mediaFiles.findIndex(f => f.name === e.name)))
    } else setOpenName(e.name)
  }, [route.share, route.segments, mediaFiles, toTrack, player, recents, ref])
  const onSelect = useCallback((e: Entry) => setPicked(p => { const n = new Set(p); n.has(e.name) ? n.delete(e.name) : n.add(e.name); return n }), [])
  const onFav = useCallback((e: Entry) => { const r = ref(e); toast.show(fav.has(r) ? `Removed “${e.name}” from favorites` : `Added “${e.name}” to favorites`); fav.toggle(r) }, [fav, ref, toast])
  const onQueue = useCallback((e: Entry) => { player.enqueue([toTrack(e)]); toast.show(`Added “${stripExt(e.name)}” to the queue`) }, [player, toTrack, toast])

  const downloadPicked = async () => {
    for (const name of picked) {
      const f = files.find(x => x.name === name)
      if (!f) continue
      const a = document.createElement('a'); a.href = fileUrl(route.share, join(name), true); a.download = name
      document.body.appendChild(a); a.click(); a.remove()
      await new Promise(r => setTimeout(r, 450))
    }
  }
  const playPicked = () => {
    const t = mediaFiles.filter(f => picked.has(f.name)).map(toTrack)
    if (t.length) { player.play(t, 0); setSelecting(false); setPicked(new Set()) }
  }

  const changeView = (v: ViewMode) => { setView(v); save('view', v) }
  const changeSort = (k: SortKey) => { setSortKey(k); save('sort', k) }
  const toggleDesc = () => setDesc(d => { save('desc', !d); return !d })
  const shown = visible.slice(0, limit)
  const currentKey = player.current ? player.current.share + '|' + player.current.path : ''
  const hasMedia = mediaFiles.length > 0

  return (
    <>
      <Breadcrumbs share={route.share} shareName={shareName} segments={route.segments} />
      {offline && <div className="banner offline" role="status"><OfflineIcon /> Can’t reach the phone. Reconnecting automatically…</div>}
      <div className="command-bar nm-raised" role="toolbar" aria-label="Folder tools">
        <label className="search nm-inset">
          <SearchIcon />
          <input type="search" placeholder="Filter this folder  ( / )" value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Escape') { if (query) setQuery(''); else e.currentTarget.blur() } }} aria-label="Filter this folder" />
        </label>
        <span className="spacer" />
        <select className="nm-select" value={sortKey} onChange={e => changeSort(e.target.value as SortKey)} aria-label="Sort by">
          <option value="name">Name</option><option value="modified">Date</option><option value="size">Size</option>
        </select>
        <button className={'icon-btn' + (desc ? ' flipped' : '')} onClick={toggleDesc} aria-label={desc ? 'Descending' : 'Ascending'}><SortIcon /></button>
        <div className="segmented nm-inset" role="group" aria-label="View">
          <button className={view === 'list' ? 'on' : ''} onClick={() => changeView('list')} aria-label="List view" aria-pressed={view === 'list'}><ListIcon /></button>
          <button className={view === 'grid' ? 'on' : ''} onClick={() => changeView('grid')} aria-label="Grid view" aria-pressed={view === 'grid'}><GridIcon /></button>
        </div>
        <button className="icon-btn" onClick={() => setReload(n => n + 1)} aria-label="Refresh"><RefreshIcon /></button>
      </div>

      <div className="chips" role="group" aria-label="Filter by type">
        {(['all', 'image', 'video', 'audio', 'doc'] as Filter[]).map(f => (
          <button key={f} className={'chip' + (filter === f ? ' on' : '')} onClick={() => setFilter(f)}>{{ all: 'All', image: 'Photos', video: 'Videos', audio: 'Music', doc: 'Documents' }[f]}</button>
        ))}
        <span className="spacer" />
        {hasMedia && !selecting && <button className="nm-btn sm accent" onClick={() => player.play(mediaFiles.map(toTrack), 0)}><PlayIcon width={16} height={16} /> Play all</button>}
        <button className={'nm-btn sm' + (selecting ? ' on' : '')} aria-pressed={selecting} onClick={() => { setSelecting(s => !s); setPicked(new Set()) }}>{selecting ? 'Cancel' : 'Select'}</button>
      </div>

      {selecting && (
        <div className="selbar nm-raised">
          <strong aria-live="polite">{picked.size} selected</strong>
          <button className="nm-btn sm" onClick={() => setPicked(picked.size === files.length ? new Set() : new Set(files.map(f => f.name)))}>{picked.size === files.length && files.length > 0 ? 'Clear' : 'All files'}</button>
          <span className="spacer" />
          <button className="nm-btn sm" disabled={!picked.size} onClick={playPicked}><PlayIcon width={16} height={16} /> Play</button>
          <button className="nm-btn sm primary" disabled={!picked.size} onClick={downloadPicked}><DownloadIcon width={16} height={16} /> Download</button>
        </div>
      )}

      {error && (
        <div className="empty" role="alert"><Aubree state="sad" size={120} />
          <h3>Something isn’t going well</h3>
          <p className="muted">{error}</p>
          <button className="nm-btn sm" onClick={() => setReload(n => n + 1)}><RefreshIcon /> Try again</button></div>
      )}
      {!entries && !error && (
        <div className={view === 'grid' ? 'grid-view' : 'nm-raised list skeleton'} aria-busy="true">
          {[0, 1, 2, 3, 4, 5].map(i => view === 'grid'
            ? <div className="tile nm-raised" key={i}><span className="thumb nm-inset sk" /><span className="sk sk-name" /></div>
            : <div className="row" key={i}><span className="sk sk-icon" /><span className="sk sk-name" /></div>)}
        </div>
      )}
      {entries && visible.length === 0 && (
        <div className="empty"><Aubree state="empty" size={132} />
          <h3>{query || filter !== 'all' ? 'No matches' : 'This folder is empty'}</h3>
          <p className="muted">{query || filter !== 'all' ? 'Try a different name or type.' : 'Files added on the phone will show up here.'}</p></div>
      )}

      {entries && visible.length > 0 && view === 'list' && (
        <div className="nm-raised list" role="list">
          <div className="row head" aria-hidden="true"><span className="name">Name</span><span className="date">Modified</span><span className="size">Size</span></div>
          {shown.map(e => <Item key={e.name} e={e} share={route.share} dir={route.segments} view="list" selecting={selecting} selected={picked.has(e.name)}
            fav={fav.has(ref(e))} playingNow={currentKey === route.share + '|' + join(e.name)} onOpen={onOpen} onSelect={onSelect} onFav={onFav} onQueue={onQueue} />)}
        </div>
      )}
      {entries && visible.length > 0 && view === 'grid' && (
        <div className="grid-view">
          {shown.map(e => <Item key={e.name} e={e} share={route.share} dir={route.segments} view="grid" selecting={selecting} selected={picked.has(e.name)}
            fav={fav.has(ref(e))} playingNow={currentKey === route.share + '|' + join(e.name)} onOpen={onOpen} onSelect={onSelect} onFav={onFav} onQueue={onQueue} />)}
        </div>
      )}
      {shown.length < visible.length && <div ref={sentinel} className="sentinel" />}
      {entries && visible.length > 0 && (
        <p className="summary muted small">{visible.length} item{visible.length === 1 ? '' : 's'}{files.length > 0 ? ` · ${formatSize(totalSize)}` : ''}</p>
      )}
      {openIndex >= 0 && (
        <Viewer share={route.share} dir={route.segments} files={viewable} index={openIndex}
          onIndex={i => setOpenName(viewable[i]?.name ?? null)} onClose={() => setOpenName(null)} />
      )}
    </>
  )
}

/* ---------- Home, favorites, recents, settings ---------- */

function RefList({ items, shares, empty, onClear }: { items: Ref[]; shares: Share[]; empty: string; onClear?: () => void }) {
  const player = usePlayer()
  const open = (r: Ref) => {
    const parent = r.path.split('/').slice(0, -1)
    if (r.kind === 'folder') location.hash = toHash(r.share, r.path.split('/'))
    else if (isMedia(r.kind)) {
      const tracks = items.filter(i => isMedia(i.kind)).map(i => ({ share: i.share, path: i.path, name: i.name, kind: i.kind as 'audio' | 'video', size: i.size }))
      player.play(tracks, Math.max(0, tracks.findIndex(t => t.path === r.path && t.share === r.share)))
    } else location.hash = toHash(r.share, parent)
  }
  if (!items.length) return <div className="empty"><Aubree state="empty" size={112} /><p className="muted">{empty}</p></div>
  return (
    <>
      {onClear && <div className="chips"><span className="spacer" /><button className="nm-btn sm" onClick={onClear}>Clear</button></div>}
      <div className="nm-raised list" role="list">
        {items.map(r => (
          <div className="row-wrap" key={r.share + r.path} role="listitem">
            <button className="row" onClick={() => open(r)}>
              <span className="name"><FileTypeIcon kind={r.kind} size={28} /><span className="ellipsis">{r.name}</span></span>
              <span className="date muted ellipsis">{shares.find(s => s.id === r.share)?.name}</span>
              <span className="size muted">{r.kind === 'folder' ? '' : formatSize(r.size)}</span>
            </button>
            {r.kind !== 'folder' && <span className="row-actions"><a className="icon-btn" href={fileUrl(r.share, r.path, true)} aria-label={`Download ${r.name}`}><DownloadIcon /></a></span>}
            {isMedia(r.kind) && <Progress share={r.share} path={r.path} />}
          </div>
        ))}
      </div>
    </>
  )
}

function ContinueCards({ items }: { items: Ref[] }) {
  const player = usePlayer()
  const entries = items.filter(r => isMedia(r.kind)).map(r => ({ r, rs: getResume(r.share, r.path) })).filter(x => x.rs && x.rs.d > 0).slice(0, 3)
  if (!entries.length) return null
  return (
    <>
      <h3 className="section">Continue where you left off</h3>
      <div className="continue-grid">
        {entries.map(({ r, rs }) => (
          <button key={r.share + r.path} className="continue nm-raised" aria-label={`Resume ${stripExt(r.name)} at ${formatTime(rs!.t)}`}
            onClick={() => player.play([{ share: r.share, path: r.path, name: r.name, kind: r.kind as 'audio' | 'video', size: r.size }], 0)}>
            <FileTypeIcon kind={r.kind} size={40} />
            <span className="continue-text">
              <strong className="ellipsis">{stripExt(r.name)}</strong>
              <span className="muted small">{formatTime(rs!.t)} of {formatTime(rs!.d)} · {formatTime(rs!.d - rs!.t)} left</span>
              <span className="bar" aria-hidden="true"><span style={{ width: Math.round((rs!.t / rs!.d) * 100) + '%' }} /></span>
            </span>
            <span className="continue-play"><PlayIcon width={16} height={16} /></span>
          </button>
        ))}
      </div>
    </>
  )
}

function Home({ shares, fav, recents, onSend, waiting }: { shares: Share[]; fav: ReturnType<typeof useFavorites>; recents: ReturnType<typeof useRecents>; onSend: () => void; waiting: number }) {
  return (
    <>
      <ContinueCards items={recents.list} />
      {waiting > 0 && (
        <a className="send-card nm-raised inbox-banner" href="#/inbox">
          <Aubree state="celebrate" size={72} />
          <div className="send-card-text"><strong>{waiting} file{waiting === 1 ? '' : 's'} from the phone</strong><span className="muted small">The phone’s owner sent you something. Open it to download.</span></div>
          <span className="nm-btn primary"><DownloadIcon width={18} height={18} /> Open</span>
        </a>
      )}
      <div className="send-card nm-raised">
        <Aubree state="welcome" size={72} />
        <div className="send-card-text"><strong>Send files to this phone</strong><span className="muted small">Choose files, or drop them anywhere on this page. The phone asks its owner to approve first.</span></div>
        <button className="nm-btn primary" onClick={onSend}><UploadIcon width={18} height={18} /> Send files</button>
      </div>
      <h2 className="page-title">Shared folders</h2>
      {shares.length === 0 && (
        <div className="empty"><Aubree state="empty" size={140} /><h3>Nothing is shared yet</h3><p className="muted">On the phone, open Files and add a folder to share.</p></div>
      )}
      <div className="grid-view folders">
        {shares.map(s => (
          <a key={s.id} className="tile nm-raised" href={toHash(s.id, [])} onPointerEnter={() => api.prefetch(s.id, '')} onTouchStart={() => api.prefetch(s.id, '')}>
            <span className="thumb nm-inset"><span className="thumb-icon"><FileTypeIcon kind="folder" size={56} /></span></span>
            <span className="tile-name ellipsis">{s.name}</span>
          </a>
        ))}
      </div>
      {recents.list.length > 0 && <><h3 className="section">Recent files</h3><RefList items={recents.list.slice(0, 6)} shares={shares} empty="" /></>}
      {fav.list.length > 0 && <><h3 className="section">Favorites</h3><RefList items={fav.list.slice(0, 6)} shares={shares} empty="" /></>}
    </>
  )
}

type InstallEvent = Event & { prompt: () => Promise<void> }

function Choice<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className="setting-row">
      <span className="setting-label" id={'lbl-' + label}>{label}</span>
      <div className="segmented nm-inset" role="radiogroup" aria-labelledby={'lbl-' + label}>
        {options.map(([v, text]) => <button key={v} role="radio" aria-checked={value === v} className={value === v ? 'on' : ''} onClick={() => onChange(v)}>{text}</button>)}
      </div>
    </div>
  )
}

function Switch({ label, hint, checked, onChange }: { label: string; hint?: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="switch">
      <span className="switch-text"><span>{label}</span>{hint && <small className="muted">{hint}</small>}</span>
      <input type="checkbox" role="switch" checked={checked} onChange={e => onChange(e.target.checked)} />
      <span className="switch-track" aria-hidden="true"><span /></span>
    </label>
  )
}

function Settings({ theme, display, setDisplay, install, standalone, onShortcuts, onIdentify }: {
  theme: ReturnType<typeof useTheme>; display: Display; setDisplay: <K extends keyof Display>(k: K, v: Display[K]) => void
  install: InstallEvent | null; standalone: boolean; onShortcuts: () => void; onIdentify: () => void
}) {
  const [resume, setResume] = useSetting('resume', true)
  return (
    <>
      <h2 className="page-title">Settings</h2>
      <div className="settings-grid">
        <section className="nm-raised card stack settings" aria-labelledby="set-appearance">
          <h3 id="set-appearance">Appearance</h3>
          <Choice label="Theme" value={theme.mode} options={[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']] as [ThemeMode, string][]} onChange={theme.setMode} />
          <div className="setting-row">
            <span className="setting-label" id="lbl-accent">Accent color</span>
            <div className="swatches" role="radiogroup" aria-labelledby="lbl-accent">
              {Object.entries(ACCENTS).map(([k, a]) => (
                <button key={k} role="radio" aria-checked={theme.accent === k} className={'swatch nm-btn round' + (theme.accent === k ? ' on' : '')} style={{ ['--sw' as string]: a.light }} onClick={() => theme.setAccent(k)} aria-label={a.name} title={a.name}>
                  {theme.accent === k && <CheckIcon />}</button>
              ))}
            </div>
          </div>
          <Choice label="Density" value={display.density} options={[['comfortable', 'Comfortable'], ['compact', 'Compact']]} onChange={v => setDisplay('density', v)} />
        </section>

        <section className="nm-raised card stack settings" aria-labelledby="set-access">
          <h3 id="set-access">Accessibility</h3>
          <Choice label="Text size" value={display.text} options={[['normal', 'Normal'], ['large', 'Large'], ['xlarge', 'Extra large']]} onChange={v => setDisplay('text', v)} />
          <Switch label="Reduce motion" hint="Turns off animations and smooth scrolling." checked={display.motion === 'reduce'} onChange={v => setDisplay('motion', v ? 'reduce' : 'auto')} />
          <Switch label="High contrast" hint="Stronger text and borders." checked={display.contrast === 'high'} onChange={v => setDisplay('contrast', v ? 'high' : 'normal')} />
          <button className="nm-btn" onClick={onShortcuts}><KeyboardIcon /> Keyboard shortcuts</button>
        </section>

        <section className="nm-raised card stack settings" aria-labelledby="set-device">
          <h3 id="set-device">This device</h3>
          <p className="muted small">The phone’s owner sees your name and device model, so they know who is who and can send you files.</p>
          <button className="nm-btn" onClick={onIdentify}>Edit name and device model</button>
        </section>

        <section className="nm-raised card stack settings" aria-labelledby="set-play">
          <h3 id="set-play">Playback</h3>
          <Switch label="Resume where I left off" hint="Videos and long tracks continue from their last position." checked={resume} onChange={setResume} />
        </section>

        <section className="nm-raised card stack settings" aria-labelledby="set-support">
          <h3 id="set-support">Support DropLink</h3>
          <p className="muted small">DropLink is free and open source. If it is useful to you, you can support its development.</p>
          <a className="nm-btn primary" href="https://ko-fi.com/nobertlamn" target="_blank" rel="noreferrer"><HeartIcon /> Support on Ko-fi</a>
        </section>

        <section className="nm-raised card stack settings" aria-labelledby="set-app">
          <h3 id="set-app">Install as an app</h3>
          {standalone ? <p className="muted">DropLink is running as an installed app.</p>
            : install ? <button className="nm-btn primary" onClick={() => install.prompt()}><DownloadIcon /> Install DropLink</button>
            : <p className="muted small">In Chrome open the ⋮ menu and choose Install app or Add to Home screen. DropLink uses a self-signed certificate, so some browsers only create a shortcut instead of a full app; a shortcut still opens it in one tap.</p>}
          <p className="muted small"><a href="/privacy.html">Privacy Policy</a> · <a href="https://github.com/trebronwayne/DropLink/issues" target="_blank" rel="noreferrer">Report an issue</a> · <a href="mailto:trebronwayne@gmail.com">Send feedback</a></p>
        </section>
      </div>
    </>
  )
}

/* ---------- Speed test ---------- */

async function downloadBytes(megabytes: number): Promise<number> {
  const response = await fetch(`/api/speedtest/down?mb=${megabytes}`, { cache: 'no-store' })
  if (!response.ok || !response.body) throw new Error('download failed')
  const reader = response.body.getReader()
  let total = 0
  for (;;) { const { done, value } = await reader.read(); if (done) break; total += value.length }
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
      setResult(r => ({ ...r, upMbps: (body.size * 8) / ((performance.now() - u0) / 1000) / 1e6 }))
    } catch { setResult({ error: 'The test failed. Check the connection to the phone and try again.' }) }
    finally { setPhase('idle') }
  }
  return (
    <>
      <h2 className="page-title">Speed test</h2>
      <div className="nm-raised card stack">
        <p className="muted">Measures the network path between this device and the phone, without reading the phone&apos;s storage.</p>
        <div className="controls">
          <label>Data per stream<select className="nm-select" value={megabytes} onChange={e => setMegabytes(Number(e.target.value))} disabled={phase !== 'idle'}>{[10, 25, 50, 100].map(n => <option key={n} value={n}>{n} MB</option>)}</select></label>
          <label>Streams<select className="nm-select" value={streams} onChange={e => setStreams(Number(e.target.value))} disabled={phase !== 'idle'}>{[1, 2, 4, 8].map(n => <option key={n} value={n}>{n}</option>)}</select></label>
        </div>
        <button className="nm-btn primary" onClick={run} disabled={phase !== 'idle'}>{phase === 'down' ? 'Testing download…' : phase === 'up' ? 'Testing upload…' : 'Run speed test'}</button>
        {result.error && <p className="error" role="alert">{result.error}</p>}
        {result.downMbps !== undefined && <div className="result nm-inset"><span className="big">{result.downMbps.toFixed(1)}</span><span className="muted"> Mbps down · {result.downMBps?.toFixed(1)} MB/s · {streams} stream{streams > 1 ? 's' : ''}</span></div>}
        {result.upMbps !== undefined && <div className="result nm-inset"><span className="big">{result.upMbps.toFixed(1)}</span><span className="muted"> Mbps up</span></div>}
      </div>
    </>
  )
}

/* ---------- Connect another device (QR codes) ---------- */

function ConnectDialog({ onClose }: { onClose: () => void }) {
  const [list, setList] = useState<{ url: string; label: string }[] | null>(null)
  const [copied, setCopied] = useState('')
  const dialog = useDialog<HTMLElement>(onClose)
  useEffect(() => { api.addresses().then(setList).catch(() => setList([])) }, [])
  const share = async (url: string) => {
    try {
      if (navigator.share) { await navigator.share({ title: 'DropLink', url }); return }
      await navigator.clipboard.writeText(url); setCopied(url); setTimeout(() => setCopied(''), 1500)
    } catch { /* cancelled */ }
  }
  return (
    <div className="overlay" onClick={onClose}>
      <section ref={dialog} tabIndex={-1} className="viewer nm-raised connect" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Connect another device">
        <header className="viewer-bar"><QrIcon /><strong>Connect another device</strong><span className="spacer" />
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>
        <div className="connect-body">
          <p className="muted">Point the other device&apos;s camera at a code. It opens DropLink, with no address to type.</p>
          {list === null && <p className="muted">Loading…</p>}
          {list?.length === 0 && <p className="muted">No shareable address found. Turn on the hotspot or join Wi-Fi on the phone.</p>}
          <div className="qr-grid">
            {list?.map(a => (
              <div className="qr-card nm-inset" key={a.url}>
                <span className="muted small">{a.label}</span>
                <img src={qrUrl(a.url)} alt={`QR code for ${a.url}`} width={200} height={200} />
                <code>{a.url}</code>
                <button className="nm-btn sm" onClick={() => share(a.url)}>{copied === a.url ? 'Copied' : 'Share link'}</button>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}

/* ---------- App shell ---------- */

function Shell() {
  const theme = useTheme()
  const fav = useFavorites()
  const recents = useRecents()
  const player = usePlayer()
  const disp = useDisplay()
  const [help, setHelp] = useState(false)
  const [identify, setIdentify] = useState(false)
  const [uploadFiles, setUploadFiles] = useState<File[] | null>(null)
  const [dragging, setDragging] = useState(false)
  const [authed, setAuthed] = useState<boolean | null>(() => (load<Share[] | null>('shares', null) ? true : null))
  const [shares, setShares] = useState<Share[]>(() => load<Share[]>('shares', []))
  const [route, setRoute] = useState<Route>(parseHash())
  const [connect, setConnect] = useState(false)
  const [down, setDown] = useState(false)
  const [installEvent, setInstallEvent] = useState<InstallEvent | null>(null)
  const standalone = matchMedia('(display-mode: standalone)').matches
  useEffect(() => {
    const on = (e: Event) => { e.preventDefault(); setInstallEvent(e as InstallEvent) }
    window.addEventListener('beforeinstallprompt', on)
    return () => window.removeEventListener('beforeinstallprompt', on)
  }, [])

  const loadShares = useCallback(async () => {
    try { const s = await api.shares(); setShares(s); save('shares', s); setAuthed(true); setDown(false) }
    catch (e) {
      if (e instanceof AuthError) { save('shares', null); setAuthed(false); setDown(false) }
      else if (e instanceof OfflineError) setDown(true)
      else setAuthed(a => a ?? false)
    }
  }, [])
  useEffect(() => { loadShares() }, [loadShares])
  const inbox = useInbox(authed === true, () => setAuthed(false))
  // Ask once for a name and model if the phone does not have them yet (devices paired before this existed).
  useEffect(() => {
    if (authed !== true) return
    if (Date.now() < Number(localStorage.getItem('dl.identify.skip') ?? 0)) return
    api.device().then(d => {
      if (d.person) return
      const who = savedWho()
      if (who.person) { api.setDevice(who.person, who.model).catch(() => undefined); return }
      setIdentify(true)
      localStorage.setItem('dl.identify.skip', String(Date.now() + 7 * 86_400_000))
    }).catch(() => undefined)
  }, [authed])
  // While the phone is unreachable keep checking, and recover on its own as soon as it answers.
  useEffect(() => {
    if (!down) return
    const id = window.setInterval(async () => { if (await api.ping()) loadShares() }, 3000)
    return () => window.clearInterval(id)
  }, [down, loadShares])
  useEffect(() => {
    const onHash = () => { setRoute(parseHash()); window.scrollTo({ top: 0 }) }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  // With a single shared folder, open it once when the page first loads. After that Home really is Home.
  const autoOpened = useRef(false)
  useEffect(() => {
    if (!authed || shares.length !== 1 || autoOpened.current) return
    autoOpened.current = true
    if (!route.share) location.hash = toHash(shares[0].id, [])
  }, [authed, shares, route.share])

  // Keyboard shortcuts. They never hijack typing, focused buttons or open dialogs.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const t = e.target as HTMLElement
      if (/INPUT|SELECT|TEXTAREA/.test(t.tagName) || t.isContentEditable) return
      if (document.querySelector('[aria-modal="true"]')) return
      if (e.key === '?') { e.preventDefault(); setHelp(true); return }
      if (e.key === 'u' || e.key === 'U') { e.preventDefault(); setUploadFiles([]); return }
      if (e.key === '/') { const box = document.querySelector<HTMLInputElement>('input[type="search"]'); if (box) { e.preventDefault(); box.focus() } return }
      if (!player.current) return
      const onControl = t.tagName === 'BUTTON' || t.tagName === 'A'
      switch (e.key) {
        case ' ': if (onControl) return; e.preventDefault(); player.toggle(); break
        case 'j': player.seekBy(-10); break
        case 'l': player.seekBy(10); break
        case 'ArrowLeft': if (!onControl) player.seekBy(-5); break
        case 'ArrowRight': if (!onControl) player.seekBy(5); break
        case 'ArrowUp': if (player.mode === 'full') { e.preventDefault(); player.volumeBy(0.05) } break
        case 'ArrowDown': if (player.mode === 'full') { e.preventDefault(); player.volumeBy(-0.05) } break
        case 'm': player.toggleMute(); break
        case 'f': if (player.current.kind === 'video') player.fullscreen(); break
        case 's': player.toggleShuffle(); break
        case 'r': player.cycleRepeat(); break
        case '>': player.cycleRate(); break
        case 'n': player.next(); break
        case 'p': player.prev(); break
        default: break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [player])

  // Drop files anywhere on the page to send them to the phone.
  useEffect(() => {
    if (!authed) return
    let depth = 0
    const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files')
    const enter = (e: DragEvent) => { if (hasFiles(e)) { depth++; setDragging(true) } }
    const over = (e: DragEvent) => { if (hasFiles(e)) e.preventDefault() }
    const leave = (e: DragEvent) => { if (hasFiles(e)) { depth = Math.max(0, depth - 1); if (!depth) setDragging(false) } }
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return
      e.preventDefault()
      depth = 0
      setDragging(false)
      const files = Array.from(e.dataTransfer?.files ?? [])
      if (files.length && !document.querySelector('[aria-modal="true"]')) setUploadFiles(files)
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragover', over)
    window.addEventListener('dragleave', leave)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragover', over)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('drop', drop)
    }
  }, [authed])

  // Page title and focus follow the route, so screen readers announce where you are.
  const firstRoute = useRef(true)
  useEffect(() => {
    const names: Record<string, string> = { favorites: 'Favorites', recent: 'Recent', settings: 'Settings', speed: 'Speed test', inbox: 'From the phone' }
    const place = names[route.share] ?? (route.share ? (route.segments[route.segments.length - 1] ?? shares.find(x => x.id === route.share)?.name ?? '') : 'Home')
    const badge = inbox.waiting > 0 ? `(${inbox.waiting}) ` : ''
    document.title = badge + (place ? `${place} · DropLink` : 'DropLink')
    if (firstRoute.current) { firstRoute.current = false; return }
    document.getElementById('content')?.focus({ preventScroll: true })
  }, [route, shares, inbox.waiting])

  if (down && !authed) return <Reconnect full onRetry={loadShares} />
  if (authed === null) return <main className="center"><div className="splash"><Aubree state="wait" size={104} /><p className="muted">Connecting…</p></div></main>
  if (!authed) return <Pair onDone={loadShares} />

  const page = PAGES.includes(route.share) ? route.share : ''
  const current = shares.find(s => s.id === route.share)
  const nav = (href: string, label: string, icon: React.ReactElement, active: boolean, count = 0) => (
    <a className={'nav-item' + (active ? ' active' : '')} href={href} aria-current={active ? 'page' : undefined}>{icon}<span className="ellipsis">{label}</span>{count > 0 && <span className="count" aria-label={`${count} waiting`}>{count > 99 ? '99+' : count}</span>}</a>
  )

  return (
    <div className={'shell' + (player.current ? ' has-player' : '')}>
      <a className="skip-link" href="#content" onClick={e => { e.preventDefault(); document.getElementById('content')?.focus() }}>Skip to content</a>
      <header className="appbar">
        <a className="brand" href="#/"><Logo size={28} /><span>DropLink</span></a>
        <span className="spacer" />
        {installEvent && !standalone && <button className="nm-btn sm" onClick={() => installEvent.prompt()}><DownloadIcon width={18} height={18} /> <span className="hide-narrow">Install app</span></button>}
        <button className="nm-btn sm" onClick={() => setConnect(true)}><QrIcon width={18} height={18} /> <span className="hide-narrow">Connect a device</span></button>
        <button className="nm-btn sm" onClick={() => setUploadFiles([])} aria-label="Send files to this phone" title="Send files to this phone ( U )"><UploadIcon width={18} height={18} /> <span className="hide-narrow">Send files</span></button>
        <button className="icon-btn hide-narrow" onClick={() => setHelp(true)} aria-label="Keyboard shortcuts" title="Keyboard shortcuts ( ? )"><HelpIcon /></button>
        <span className="secure small"><ShieldIcon width={16} height={16} /> <span className="hide-narrow">Connected securely</span></span>
      </header>
      {inbox.waiting > 0 && page !== 'inbox' && (
        <a className="inbox-bar" href="#/inbox" role="status">
          <DownloadIcon width={18} height={18} /><span><strong>{inbox.waiting} file{inbox.waiting === 1 ? '' : 's'} from the phone</strong> are ready to download</span><span className="inbox-bar-go">Open</span>
        </a>
      )}
      <div className="body">
        <nav className="nav nm-raised" aria-label="Main">
          {nav('#/', 'Home', <HomeIcon />, !route.share)}
          {nav('#/favorites', 'Favorites', <StarIcon />, page === 'favorites')}
          {nav('#/recent', 'Recent', <ClockIcon />, page === 'recent')}
          {nav('#/inbox', 'From the phone', <DownloadIcon />, page === 'inbox', inbox.waiting)}
          <div className="nav-title">Shared folders</div>
          {shares.map(s => (
            <a key={s.id} className={'nav-item' + (s.id === route.share ? ' active' : '')} href={toHash(s.id, [])} aria-current={s.id === route.share ? 'page' : undefined} onPointerEnter={() => api.prefetch(s.id, '')}>
              <FileTypeIcon kind="folder" size={22} /><span className="ellipsis">{s.name}</span>
            </a>
          ))}
          <div className="nav-sep" />
          {nav('#/speed', 'Speed test', <GaugeIcon />, page === 'speed')}
          {nav('#/settings', 'Settings', <SettingsIcon />, page === 'settings')}
        </nav>
        <main className="content" id="content" tabIndex={-1}>
          {down && <Reconnect onRetry={loadShares} />}
          {page === 'speed' ? <SpeedTest />
            : page === 'inbox' ? <InboxPage items={inbox.items} onRefresh={inbox.refresh} error={inbox.error} checkedAt={inbox.checkedAt} />
            : page === 'settings' ? <Settings theme={theme} display={disp.display} setDisplay={disp.set} install={installEvent} standalone={standalone} onShortcuts={() => setHelp(true)} onIdentify={() => setIdentify(true)} />
            : page === 'favorites' ? <><h2 className="page-title">Favorites</h2><RefList items={fav.list} shares={shares} empty="Tap the star on any file or folder to keep it here." /></>
            : page === 'recent' ? <><h2 className="page-title">Recent</h2><RefList items={recents.list} shares={shares} empty="Files you open will appear here." onClear={recents.clear} /></>
            : current ? <Browser key={current.id} route={route} shareName={current.name} fav={fav} recents={recents} />
            : <Home shares={shares} fav={fav} recents={recents} onSend={() => setUploadFiles([])} waiting={inbox.waiting} />}
        </main>
      </div>
      {connect && <ConnectDialog onClose={() => setConnect(false)} />}
      {help && <ShortcutsDialog onClose={() => setHelp(false)} />}
      {identify && <IdentifyDialog onClose={() => setIdentify(false)} />}
      {uploadFiles && <UploadDialog initialFiles={uploadFiles} onClose={() => setUploadFiles(null)} />}
      {dragging && !uploadFiles && (
        <div className="drop-overlay" aria-hidden="true"><Aubree state="welcome" size={110} /><strong>Drop files to send them to this phone</strong></div>
      )}
      <nav className="tabbar nm-raised" aria-label="Quick navigation">
        {nav('#/', 'Home', <HomeIcon />, !route.share)}
        {nav('#/favorites', 'Favorites', <StarIcon />, page === 'favorites')}
        {nav('#/inbox', 'Inbox', <DownloadIcon />, page === 'inbox', inbox.waiting)}
        {nav('#/settings', 'Settings', <SettingsIcon />, page === 'settings')}
      </nav>
    </div>
  )
}

export default function App() {
  return <ToastProvider><PlayerProvider><Shell /></PlayerProvider></ToastProvider>
}
