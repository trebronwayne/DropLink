import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, AuthError, qrUrl, cachedBrowse, Entry, fileUrl, LockedError, OfflineError, Share, thumbUrl } from './api'
import {
  ArrowLeftIcon, ArrowRightIcon, CheckIcon, ChevronRightIcon, ClockIcon, CloseIcon, DownloadIcon, FileTypeIcon, GaugeIcon, GridIcon,
  HomeIcon, ListIcon, Logo, OfflineIcon, PlayIcon, PlusIcon, QrIcon, RefreshIcon, SearchIcon, SettingsIcon, ShieldIcon, SortIcon, StarFillIcon, StarIcon
} from './icons'
import { Reconnect } from './Reconnect'
import { PlayerProvider, Track, usePlayer } from './player'
import { ACCENTS, load, Ref, save, ThemeMode, useFavorites, useRecents, useTheme } from './prefs'
import { formatDate, formatSize, Kind, kindOf } from './util'

type Route = { share: string; segments: string[] }
type SortKey = 'name' | 'size' | 'modified'
type ViewMode = 'list' | 'grid'
type Filter = 'all' | 'image' | 'video' | 'audio' | 'doc'
const PAGES = ['speed', 'favorites', 'recent', 'settings']

function parseHash(): Route {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent)
  return { share: parts[0] ?? '', segments: parts.slice(1) }
}
const toHash = (share: string, segments: string[]) => '#/' + [share, ...segments].filter(Boolean).map(encodeURIComponent).join('/')

function guessDeviceName(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : 'device'
  return `${browser} on ${os}`
}

const isMedia = (k: Kind) => k === 'audio' || k === 'video'
const matchesFilter = (k: Kind, f: Filter) => f === 'all' || (f === 'doc' ? k === 'pdf' || k === 'text' || k === 'file' || k === 'archive' : k === f)

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
      <div className="nm-raised card pair">
        <div className="pair-logo nm-inset"><Logo size={64} /></div>
        <h1>DropLink</h1>
        {!request ? (
          <form onSubmit={start} className="stack">
            <p className="muted">Ask this phone for access. You approve the request on the phone, so nobody else on the network can get in.</p>
            <label className="field-label" htmlFor="device">Device name</label>
            <input id="device" className="nm-input" value={name} maxLength={40} onChange={e => setName(e.target.value)} />
            {message && <p className="error" role="alert">{message}</p>}
            <button className="nm-btn primary" disabled={busy || !name.trim()}>{busy ? 'Sending…' : 'Request access'}</button>
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
  const touch = useRef<number | null>(null)

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
      <section className="viewer nm-raised" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={entry.name}
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
          <button className="icon-btn" onClick={() => onIndex(index - 1)} disabled={index === 0} aria-label="Previous file"><ArrowLeftIcon /></button>
          <button className="icon-btn" onClick={() => onIndex(index + 1)} disabled={index === files.length - 1} aria-label="Next file"><ArrowRightIcon /></button>
          <a className="nm-btn" href={fileUrl(share, path, true)}><DownloadIcon /> <span className="hide-narrow">Download</span></a>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button>
        </header>
        <div className="stage">
          {kind === 'image' && <img key={path} src={src} alt={entry.name} />}
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
        {!selecting && <button className={'icon-btn fav' + (fav ? ' on' : '')} onClick={() => onFav(e)} aria-label="Favorite">{fav ? <StarFillIcon /> : <StarIcon />}</button>}
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
          <button className={'icon-btn' + (fav ? ' on' : '')} onClick={() => onFav(e)} aria-label="Favorite">{fav ? <StarFillIcon /> : <StarIcon />}</button>
          {!e.isDir && <a className="icon-btn" href={fileUrl(share, path, true)} aria-label={`Download ${e.name}`}><DownloadIcon /></a>}
        </span>
      )}
    </div>
  )
})

const CHUNK = 120

function Browser({ route, shareName, fav, recents }: { route: Route; shareName: string; fav: ReturnType<typeof useFavorites>; recents: ReturnType<typeof useRecents> }) {
  const path = route.segments.join('/')
  const player = usePlayer()
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
  const onFav = useCallback((e: Entry) => fav.toggle(ref(e)), [fav, ref])
  const onQueue = useCallback((e: Entry) => player.enqueue([toTrack(e)]), [player, toTrack])

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
          <input type="search" placeholder="Filter this folder" value={query} onChange={e => setQuery(e.target.value)} aria-label="Filter this folder" />
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
        <button className={'nm-btn sm' + (selecting ? ' on' : '')} onClick={() => { setSelecting(s => !s); setPicked(new Set()) }}>{selecting ? 'Cancel' : 'Select'}</button>
      </div>

      {selecting && (
        <div className="selbar nm-raised">
          <strong>{picked.size} selected</strong>
          <button className="nm-btn sm" onClick={() => setPicked(new Set(files.map(f => f.name)))}>All files</button>
          <span className="spacer" />
          <button className="nm-btn sm" disabled={!picked.size} onClick={playPicked}><PlayIcon width={16} height={16} /> Play</button>
          <button className="nm-btn sm primary" disabled={!picked.size} onClick={downloadPicked}><DownloadIcon width={16} height={16} /> Download</button>
        </div>
      )}

      {error && <p className="error banner" role="alert">{error}</p>}
      {!entries && !error && (
        <div className={view === 'grid' ? 'grid-view' : 'nm-raised list skeleton'} aria-busy="true">
          {[0, 1, 2, 3, 4, 5].map(i => view === 'grid'
            ? <div className="tile nm-raised" key={i}><span className="thumb nm-inset sk" /><span className="sk sk-name" /></div>
            : <div className="row" key={i}><span className="sk sk-icon" /><span className="sk sk-name" /></div>)}
        </div>
      )}
      {entries && visible.length === 0 && (
        <div className="empty"><FileTypeIcon kind="folder" size={72} />
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
  if (!items.length) return <div className="empty"><StarIcon width={48} height={48} /><p className="muted">{empty}</p></div>
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
            {r.kind !== 'folder' && <span className="row-actions"><a className="icon-btn" href={fileUrl(r.share, r.path, true)} aria-label="Download"><DownloadIcon /></a></span>}
          </div>
        ))}
      </div>
    </>
  )
}

function Home({ shares, fav, recents }: { shares: Share[]; fav: ReturnType<typeof useFavorites>; recents: ReturnType<typeof useRecents> }) {
  return (
    <>
      <h2 className="page-title">Shared folders</h2>
      {shares.length === 0 && (
        <div className="empty"><Logo size={72} /><h3>Nothing is shared yet</h3><p className="muted">On the phone, open Files and add a folder to share.</p></div>
      )}
      <div className="grid-view folders">
        {shares.map(s => (
          <a key={s.id} className="tile nm-raised" href={toHash(s.id, [])} onPointerEnter={() => api.prefetch(s.id, '')} onTouchStart={() => api.prefetch(s.id, '')}>
            <span className="thumb nm-inset"><span className="thumb-icon"><FileTypeIcon kind="folder" size={56} /></span></span>
            <span className="tile-name ellipsis">{s.name}</span>
          </a>
        ))}
      </div>
      {recents.list.length > 0 && <><h3 className="section">Continue</h3><RefList items={recents.list.slice(0, 6)} shares={shares} empty="" /></>}
      {fav.list.length > 0 && <><h3 className="section">Favorites</h3><RefList items={fav.list.slice(0, 6)} shares={shares} empty="" /></>}
    </>
  )
}

type InstallEvent = Event & { prompt: () => Promise<void> }

function Settings({ theme, install, standalone }: { theme: ReturnType<typeof useTheme>; install: InstallEvent | null; standalone: boolean }) {
  return (
    <>
      <h2 className="page-title">Settings</h2>
      <div className="nm-raised card stack settings">
        <h3>Appearance</h3>
        <div className="segmented nm-inset wide" role="group" aria-label="Theme">
          {(['system', 'light', 'dark'] as ThemeMode[]).map(m => <button key={m} className={theme.mode === m ? 'on' : ''} onClick={() => theme.setMode(m)}>{m[0].toUpperCase() + m.slice(1)}</button>)}
        </div>
        <h3>Accent color</h3>
        <div className="swatches">
          {Object.entries(ACCENTS).map(([k, a]) => (
            <button key={k} className={'swatch nm-btn round' + (theme.accent === k ? ' on' : '')} style={{ ['--sw' as string]: a.light }} onClick={() => theme.setAccent(k)} aria-label={a.name} aria-pressed={theme.accent === k}>
              {theme.accent === k && <CheckIcon />}</button>
          ))}
        </div>
        <h3>Install as an app</h3>
        {standalone ? <p className="muted">DropLink is running as an installed app.</p>
          : install ? <button className="nm-btn primary" onClick={() => install.prompt()}><DownloadIcon /> Install DropLink</button>
          : <p className="muted small">In Chrome open the ⋮ menu and choose Install app or Add to Home screen. DropLink uses a self-signed certificate, so some browsers only create a shortcut instead of a full app; a shortcut still opens it in one tap.</p>}
        <p className="muted small"><a href="/privacy.html">Privacy Policy</a> · <a href="https://github.com/trebronwayne/DropLink/issues" target="_blank" rel="noreferrer">Report an issue</a> · <a href="mailto:trebronwayne@gmail.com">Send feedback</a></p>
        <p className="muted small">Keyboard: Space play/pause · J / L back/forward 10 s · N / P next/previous track.</p>
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
  useEffect(() => { api.addresses().then(setList).catch(() => setList([])) }, [])
  const share = async (url: string) => {
    try {
      if (navigator.share) { await navigator.share({ title: 'DropLink', url }); return }
      await navigator.clipboard.writeText(url); setCopied(url); setTimeout(() => setCopied(''), 1500)
    } catch { /* cancelled */ }
  }
  return (
    <div className="overlay" onClick={onClose}>
      <section className="viewer nm-raised connect" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Connect another device">
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
  useEffect(() => { if (authed && shares.length === 1 && !route.share) location.hash = toHash(shares[0].id, []) }, [authed, shares, route.share])

  // Keyboard shortcuts for the player.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (/INPUT|SELECT|TEXTAREA/.test(t.tagName) || e.ctrlKey || e.metaKey || e.altKey || !player.current) return
      if (e.key === ' ') { e.preventDefault(); player.toggle() }
      else if (e.key === 'j') player.seekBy(-10)
      else if (e.key === 'l') player.seekBy(10)
      else if (e.key === 'n') player.next()
      else if (e.key === 'p') player.prev()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [player])

  if (down && !authed) return <Reconnect full onRetry={loadShares} />
  if (authed === null) return <main className="center"><div className="splash"><Logo size={64} /><p className="muted">Connecting…</p></div></main>
  if (!authed) return <Pair onDone={loadShares} />

  const page = PAGES.includes(route.share) ? route.share : ''
  const current = shares.find(s => s.id === route.share)
  const nav = (href: string, label: string, icon: JSX.Element, active: boolean) => (
    <a className={'nav-item' + (active ? ' active' : '')} href={href}>{icon}<span className="ellipsis">{label}</span></a>
  )

  return (
    <div className={'shell' + (player.current ? ' has-player' : '')}>
      <header className="appbar">
        <a className="brand" href="#/"><Logo size={28} /><span>DropLink</span></a>
        <span className="spacer" />
        {installEvent && !standalone && <button className="nm-btn sm" onClick={() => installEvent.prompt()}><DownloadIcon width={18} height={18} /> <span className="hide-narrow">Install app</span></button>}
        <button className="nm-btn sm" onClick={() => setConnect(true)}><QrIcon width={18} height={18} /> <span className="hide-narrow">Connect a device</span></button>
        <span className="secure small"><ShieldIcon width={16} height={16} /> <span className="hide-narrow">Connected securely</span></span>
      </header>
      <div className="body">
        <nav className="nav nm-raised" aria-label="Navigation">
          {nav('#/', 'Home', <HomeIcon />, !route.share)}
          {nav('#/favorites', 'Favorites', <StarIcon />, page === 'favorites')}
          {nav('#/recent', 'Recent', <ClockIcon />, page === 'recent')}
          <div className="nav-title">Shared folders</div>
          {shares.map(s => (
            <a key={s.id} className={'nav-item' + (s.id === route.share ? ' active' : '')} href={toHash(s.id, [])} onPointerEnter={() => api.prefetch(s.id, '')}>
              <FileTypeIcon kind="folder" size={22} /><span className="ellipsis">{s.name}</span>
            </a>
          ))}
          <div className="nav-sep" />
          {nav('#/speed', 'Speed test', <GaugeIcon />, page === 'speed')}
          {nav('#/settings', 'Settings', <SettingsIcon />, page === 'settings')}
        </nav>
        <main className="content">
          {down && <Reconnect onRetry={loadShares} />}
          {page === 'speed' ? <SpeedTest />
            : page === 'settings' ? <Settings theme={theme} install={installEvent} standalone={standalone} />
            : page === 'favorites' ? <><h2 className="page-title">Favorites</h2><RefList items={fav.list} shares={shares} empty="Tap the star on any file or folder to keep it here." /></>
            : page === 'recent' ? <><h2 className="page-title">Recent</h2><RefList items={recents.list} shares={shares} empty="Files you open will appear here." onClear={recents.clear} /></>
            : current ? <Browser key={current.id} route={route} shareName={current.name} fav={fav} recents={recents} />
            : <Home shares={shares} fav={fav} recents={recents} />}
        </main>
      </div>
      {connect && <ConnectDialog onClose={() => setConnect(false)} />}
      <nav className="tabbar nm-raised" aria-label="Navigation">
        {nav('#/', 'Home', <HomeIcon />, !route.share)}
        {nav('#/favorites', 'Favorites', <StarIcon />, page === 'favorites')}
        {nav('#/recent', 'Recent', <ClockIcon />, page === 'recent')}
        {nav('#/settings', 'Settings', <SettingsIcon />, page === 'settings')}
      </nav>
    </div>
  )
}

export default function App() {
  return <PlayerProvider><Shell /></PlayerProvider>
}
