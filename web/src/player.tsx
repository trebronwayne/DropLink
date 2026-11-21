import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, ReactNode } from 'react'
import { api, cachedBrowse, Entry, fileUrl } from './api'
import {
  ChevronDownIcon, CloseIcon, DownloadIcon, ExpandIcon, FileTypeIcon, NextIcon, PauseIcon, PlayIcon, PrevIcon, RepeatIcon, ShuffleIcon, StarFillIcon, StarIcon
} from './icons'
import { formatSize, formatTime, kindOf, stripExt } from './util'
import { getResume, load, Ref, save, setResume, useFavorites, useRecents } from './prefs'
import { useToast } from './toast'
import { PlayerLists, ListTab } from './playerlists'
import { StageControls } from './playerchrome'

export type Track = { share: string; path: string; name: string; kind: 'audio' | 'video'; size: number }
type Repeat = 'off' | 'all' | 'one'
/** page: the full media player page. mini: the bar shown on other pages. preview: the small popup of Quick preview. */
type Mode = 'closed' | 'mini' | 'page' | 'preview'
type Sleep = { kind: 'off' } | { kind: 'time'; at: number; choice: string } | { kind: 'end' }

type Ctx = {
  current: Track | null
  queue: Track[]
  playing: boolean
  mode: Mode
  /** Plays these tracks in the media player page. */
  play: (tracks: Track[], start?: number) => void
  /** Plays one track in the small popup, without leaving the page. */
  preview: (track: Track) => void
  enqueue: (t: Track[]) => void
  openPlayer: () => void
  toggle: () => void
  next: () => void
  prev: () => void
  seekBy: (d: number) => void
  volumeBy: (d: number) => void
  toggleMute: () => void
  toggleShuffle: () => void
  cycleRepeat: () => void
  cycleRate: () => void
  fullscreen: () => void
  setMode: (m: Mode) => void
}
const PlayerCtx = createContext<Ctx | null>(null)
export const usePlayer = () => useContext(PlayerCtx)!

export const trackKey = (t: Track) => t.share + '|' + t.path
const SLEEP_OPTIONS: { value: string; label: string }[] = [
  { value: 'off', label: 'Off' }, { value: '15', label: '15 minutes' }, { value: '30', label: '30 minutes' },
  { value: '45', label: '45 minutes' }, { value: '60', label: '1 hour' }, { value: 'end', label: 'End of track' }
]
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2]
const isPlayerHash = (h: string) => /^#\/player\/?$/.test(h)
const dirOf = (path: string) => path.split('/').slice(0, -1).join('/')
const natural = (a: string, b: string) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })

export function PlayerProvider({ children, recents, fav }: { children: ReactNode; recents: ReturnType<typeof useRecents>; fav: ReturnType<typeof useFavorites> }) {
  const toast = useToast()
  const media = useRef<HTMLVideoElement>(null)
  const saved = useMemo(() => load<{ queue: Track[]; index: number; time: number } | null>('player', null), [])
  const [queue, setQueue] = useState<Track[]>(saved?.queue ?? [])
  const [index, setIndex] = useState(saved?.index ?? 0)
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(() => load('volume', 1))
  const [rate, setRate] = useState(() => load('rate', 1))
  const [shuffle, setShuffle] = useState(() => load('shuffle', false))
  const [repeat, setRepeat] = useState<Repeat>(() => load('repeat', 'off'))
  const [mode, setMode] = useState<Mode>(() => (isPlayerHash(location.hash) ? 'page' : saved?.queue?.length ? 'mini' : 'closed'))
  const [tab, setTabState] = useState<ListTab>(() => load<ListTab>('player-tab', 'all'))
  const [folder, setFolder] = useState<Entry[] | null>(null)
  const [sleep, setSleep] = useState<Sleep>({ kind: 'off' })
  const [, tick] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [chromeActive, setChromeActive] = useState(true)
  const [menuOpen, setMenuOpen] = useState(false)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [flash, setFlash] = useState<{ side: 'l' | 'r'; n: number } | null>(null)
  const stage = useRef<HTMLDivElement>(null)
  const idleTimer = useRef(0)
  const clickTimer = useRef(0)
  const touch = useRef(false)
  const resumeAt = useRef<number>(saved?.time ?? 0)
  const failures = useRef(0)
  const lastVolume = useRef(1)
  const autoplay = useRef(false)
  const autoFollow = useRef(false)
  const prevHash = useRef('#/')
  const sleepRef = useRef<Sleep>(sleep)
  sleepRef.current = sleep

  const current = queue[index] ?? null
  const setTab = (t: ListTab) => { setTabState(t); save('player-tab', t) }

  // The address and the player agree: #/player is the page, any other address folds it into the mini bar.
  useEffect(() => {
    const sync = () => setMode(m => (isPlayerHash(location.hash) ? 'page' : m === 'page' ? 'mini' : m))
    const onHash = (e: HashChangeEvent) => {
      try {
        const old = new URL(e.oldURL).hash
        if (old && !isPlayerHash(old)) prevHash.current = old
      } catch { /* keep the last one */ }
      sync()
    }
    sync()
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const openPlayer = useCallback(() => {
    if (!isPlayerHash(location.hash)) location.hash = '#/player'
    else setMode('page')
  }, [])
  const leavePage = useCallback(() => { location.hash = prevHash.current && !isPlayerHash(prevHash.current) ? prevHash.current : '#/' }, [])

  // Load the track whenever it changes. The element is never remounted, so playback survives all navigation.
  const src = current ? fileUrl(current.share, current.path) : ''
  useEffect(() => {
    const el = media.current
    if (!el) return
    failures.current = 0
    if (!src) { el.removeAttribute('src'); el.load(); return }
    el.src = src
    let start = resumeAt.current
    resumeAt.current = 0
    let resumed = false
    if (!start && current && load('resume', true)) {
      const r = getResume(current.share, current.path)
      if (r && r.t > 5) { start = r.t; resumed = true }
    }
    if (start > 1) {
      el.addEventListener('loadedmetadata', () => {
        el.currentTime = start
        if (resumed) toast.show(`Resumed at ${formatTime(start)}`)
      }, { once: true })
    }
    if (autoplay.current) el.play().catch(() => setPlaying(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src])

  // Whatever starts playing counts as recent.
  const addRecent = recents.add
  useEffect(() => {
    if (current) addRecent({ share: current.share, path: current.path, name: current.name, kind: current.kind, size: current.size })
  }, [current?.share, current?.path]) // eslint-disable-line react-hooks/exhaustive-deps

  // The folder of the playing file is listed once, only while the player page is open. Nothing else is ever scanned.
  const dir = current ? dirOf(current.path) : ''
  const share = current?.share ?? ''
  const onPage = mode === 'page'
  useEffect(() => {
    if (!onPage || !share) return
    const cached = cachedBrowse(share, dir)
    setFolder(cached ?? null)
    let alive = true
    api.browse(share, dir).then(d => { if (alive) setFolder(d) }).catch(() => { if (alive && !cached) setFolder([]) })
    return () => { alive = false }
  }, [onPage, share, dir])

  const folderTracks = useMemo<Track[]>(() => {
    if (!folder || !share) return []
    return folder
      .filter(e => !e.isDir && (kindOf(e) === 'audio' || kindOf(e) === 'video'))
      .sort((a, b) => natural(a.name, b.name))
      .map(e => ({ share, path: dir ? `${dir}/${e.name}` : e.name, name: e.name, kind: kindOf(e) as 'audio' | 'video', size: e.size }))
  }, [folder, share, dir])

  // A single file opened from Recent or Favorites carries on with the rest of its folder, like autoplay.
  useEffect(() => {
    if (!autoFollow.current || !current || folderTracks.length === 0) return
    const pos = folderTracks.findIndex(t => trackKey(t) === trackKey(current))
    if (pos < 0) return
    autoFollow.current = false
    if (queue.length === 1) { setQueue(folderTracks); setIndex(pos) }
  }, [folderTracks, current]) // eslint-disable-line react-hooks/exhaustive-deps

  const recentTracks = useMemo<Track[]>(
    () => recents.list.filter(r => r.kind === 'audio' || r.kind === 'video').map(r => ({ share: r.share, path: r.path, name: r.name, kind: r.kind as 'audio' | 'video', size: r.size })),
    [recents.list]
  )

  useEffect(() => { if (media.current) media.current.volume = volume; save('volume', volume); if (volume > 0) lastVolume.current = volume }, [volume])
  useEffect(() => { if (media.current) media.current.playbackRate = rate; save('rate', rate) }, [rate, src])
  useEffect(() => { save('shuffle', shuffle); save('repeat', repeat) }, [shuffle, repeat])

  // Remember queue and position so a refresh or a dropped connection resumes where you were,
  // and remember long tracks and videos individually so they can be picked up later.
  useEffect(() => {
    const id = window.setInterval(() => {
      const el = media.current
      const cur = queue[index]
      if (!queue.length) return
      save('player', { queue: queue.slice(0, 300), index, time: el?.currentTime ?? 0 })
      if (el && cur && !el.paused && isFinite(el.duration) && (cur.kind === 'video' || el.duration > 180)) setResume(cur.share, cur.path, el.currentTime, el.duration)
    }, 5000)
    return () => window.clearInterval(id)
  }, [queue, index])

  // Sleep timer.
  useEffect(() => {
    if (sleep.kind !== 'time') return
    const wait = Math.max(0, sleep.at - Date.now())
    const id = window.setTimeout(() => { media.current?.pause(); setSleep({ kind: 'off' }); toast.show('Sleep timer finished. Playback paused.') }, wait)
    const refresh = window.setInterval(() => tick(n => n + 1), 20000)
    return () => { window.clearTimeout(id); window.clearInterval(refresh) }
  }, [sleep, toast])
  const chooseSleep = (value: string) => {
    if (value === 'off') setSleep({ kind: 'off' })
    else if (value === 'end') { setSleep({ kind: 'end' }); toast.show('Playback will stop at the end of this track.') }
    else { setSleep({ kind: 'time', at: Date.now() + Number(value) * 60000, choice: value }); toast.show(`Sleep timer set for ${value} minutes.`) }
  }
  const sleepValue = sleep.kind === 'off' ? 'off' : sleep.kind === 'end' ? 'end' : sleep.choice
  const sleepLeft = sleep.kind === 'time' ? Math.max(1, Math.round((sleep.at - Date.now()) / 60000)) : 0

  const go = useCallback((i: number, auto = true) => {
    autoplay.current = auto
    setIndex(i)
    const el = media.current
    if (el && i === index && auto) { el.currentTime = 0; el.play().catch(() => undefined) }
  }, [index])

  const next = useCallback(() => {
    if (!queue.length) return
    if (shuffle && queue.length > 1) {
      let r = index
      while (r === index) r = Math.floor(Math.random() * queue.length)
      go(r); return
    }
    if (index < queue.length - 1) go(index + 1)
    else if (repeat === 'all') go(0)
    else { setPlaying(false) }
  }, [queue, index, shuffle, repeat, go])

  const prev = useCallback(() => {
    const el = media.current
    if (el && el.currentTime > 4) { el.currentTime = 0; return }
    if (index > 0) go(index - 1); else if (queue.length) go(queue.length - 1)
  }, [index, queue.length, go])

  const toggle = useCallback(() => {
    const el = media.current
    if (!el || !current) return
    if (el.paused) el.play().catch(() => undefined); else el.pause()
  }, [current])

  const seekBy = useCallback((d: number) => {
    const el = media.current
    if (el) el.currentTime = Math.max(0, Math.min(el.duration || 1e9, el.currentTime + d))
  }, [])
  const volumeBy = useCallback((d: number) => setVolume(v => Math.max(0, Math.min(1, Math.round((v + d) * 100) / 100))), [])
  const toggleMute = useCallback(() => setVolume(v => (v > 0 ? 0 : lastVolume.current || 1)), [])
  const toggleShuffle = useCallback(() => { setShuffle(!shuffle); toast.show(shuffle ? 'Shuffle off' : 'Shuffle on') }, [shuffle, toast])
  const cycleRepeat = useCallback(() => { const n: Repeat = repeat === 'off' ? 'all' : repeat === 'all' ? 'one' : 'off'; setRepeat(n); toast.show(n === 'off' ? 'Repeat off' : n === 'all' ? 'Repeat all' : 'Repeat one') }, [repeat, toast])
  const cycleRate = useCallback(() => setRate(r => RATES[(RATES.indexOf(r) + 1) % RATES.length] ?? 1), [])
  /** The whole stage goes full screen, not just the picture, so the controls laid over it come along. */
  const fullscreen = useCallback(() => {
    const el = stage.current
    if (!el) return
    if (document.fullscreenElement) { void document.exitFullscreen(); return }
    if (el.requestFullscreen) el.requestFullscreen().catch(() => undefined)
    else (media.current as unknown as { webkitEnterFullscreen?: () => void } | null)?.webkitEnterFullscreen?.()
  }, [])
  useEffect(() => {
    const on = () => setIsFullscreen(document.fullscreenElement === stage.current)
    document.addEventListener('fullscreenchange', on)
    return () => document.removeEventListener('fullscreenchange', on)
  }, [])

  const start = useCallback((tracks: Track[], at: number, follow: boolean) => {
    autoplay.current = true
    autoFollow.current = follow
    setQueue(tracks)
    setIndex(at)
    if (queue.length && trackKey(queue[index] ?? tracks[at]) === trackKey(tracks[at]) && media.current) {
      media.current.currentTime = 0; media.current.play().catch(() => undefined)
    }
  }, [queue, index])

  /** Every opened media goes to the media player page. */
  const play = useCallback((tracks: Track[], at = 0) => {
    if (!tracks.length) return
    start(tracks, at, tracks.length === 1)
    openPlayer()
  }, [start, openPlayer])

  /** A quick look in a popup. The page the person is on stays where it is. */
  const preview = useCallback((track: Track) => {
    start([track], 0, false)
    setMode('preview')
  }, [start])

  const enqueue = useCallback((t: Track[]) => {
    setQueue(q => {
      const have = new Set(q.map(trackKey))
      const add = t.filter(x => !have.has(trackKey(x)))
      if (!q.length) { autoplay.current = false }
      return [...q, ...add]
    })
    setMode(m => (m === 'closed' ? 'mini' : m))
  }, [])

  const stop = () => {
    const el = media.current
    el?.pause()
    setQueue([]); setIndex(0); setPlaying(false); setSleep({ kind: 'off' }); save('player', null)
    if (isPlayerHash(location.hash)) { setMode('closed'); leavePage() } else setMode('closed')
  }

  const move = (i: number, d: -1 | 1) => {
    const j = i + d
    if (j < 0 || j >= queue.length) return
    setQueue(q => { const n = q.slice(); const t = n[i]; n[i] = n[j]; n[j] = t; return n })
    setIndex(cur => (cur === i ? j : cur === j ? i : cur))
  }
  const removeAt = (i: number) => {
    setQueue(q => q.filter((_, j) => j !== i))
    if (i < index) setIndex(index - 1)
    else if (i === index) { autoplay.current = playing; setIndex(Math.min(index, queue.length - 2)) }
  }
  const clearUpcoming = () => { setQueue(q => q.slice(index, index + 1)); setIndex(0) }

  /** A list was clicked: play that list from that item. The one already playing just pauses or resumes. */
  const pick = (list: Track[], i: number) => {
    const t = list[i]
    if (!t) return
    if (current && trackKey(t) === trackKey(current)) { toggle(); return }
    start(list, i, false)
  }

  /** If the stream stalls or the connection drops (phone asleep, Wi-Fi switch), reload at the same position. */
  const recover = useCallback(() => {
    const el = media.current
    if (!el || !src || failures.current >= 6) return
    failures.current++
    const t = el.currentTime
    const wasPlaying = !el.paused || autoplay.current
    window.setTimeout(() => {
      el.src = src
      el.addEventListener('loadedmetadata', () => { el.currentTime = t; if (wasPlaying) el.play().catch(() => undefined) }, { once: true })
    }, 800 * failures.current)
  }, [src])

  // Media Session: lock-screen / notification controls, so music keeps going with the screen off.
  useEffect(() => {
    if (!('mediaSession' in navigator) || !current) return
    const ms = navigator.mediaSession
    ms.metadata = new MediaMetadata({ title: stripExt(current.name), artist: current.path.split('/').slice(-2, -1)[0] || 'DropLink', album: 'DropLink' })
    ms.setActionHandler('play', () => media.current?.play())
    ms.setActionHandler('pause', () => media.current?.pause())
    ms.setActionHandler('previoustrack', prev)
    ms.setActionHandler('nexttrack', next)
    ms.setActionHandler('seekbackward', () => seekBy(-10))
    ms.setActionHandler('seekforward', () => seekBy(10))
    try { ms.setActionHandler('seekto', d => { if (media.current && d.seekTime != null) media.current.currentTime = d.seekTime }) } catch { /* unsupported */ }
  }, [current, next, prev, seekBy])
  useEffect(() => { if ('mediaSession' in navigator) navigator.mediaSession.playbackState = playing ? 'playing' : 'paused' }, [playing])
  useEffect(() => {
    if (!('mediaSession' in navigator) || !duration || !isFinite(duration)) return
    try { navigator.mediaSession.setPositionState({ duration, position: Math.min(time, duration), playbackRate: rate }) } catch { /* ignore */ }
  }, [time, duration, rate])

  // Come back to the tab and the stream is dead? Revive it.
  useEffect(() => {
    const onVis = () => { const el = media.current; if (document.visibilityState === 'visible' && el && playing && el.paused && el.readyState < 3) recover() }
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  }, [playing, recover])

  // Escape closes the quick preview popup.
  useEffect(() => {
    if (mode !== 'preview') return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !document.fullscreenElement) stop() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode])

  const isVideo = current?.kind === 'video'
  const ctx: Ctx = { current, queue, playing, mode, play, preview, enqueue, openPlayer, toggle, next, prev, seekBy, volumeBy, toggleMute, toggleShuffle, cycleRepeat, cycleRate, fullscreen, setMode }
  const pct = duration ? (time / duration) * 100 : 0
  const onSeek = (v: number) => { if (media.current) media.current.currentTime = v }
  const pip = () => { const el = media.current; if (el && document.pictureInPictureEnabled) (document.pictureInPictureElement ? document.exitPictureInPicture() : el.requestPictureInPicture()).catch(() => undefined) }
  const ended = () => {
    if (current) setResume(current.share, current.path, 0, 0)
    if (sleepRef.current.kind === 'end') { setSleep({ kind: 'off' }); setPlaying(false); toast.show('Sleep timer finished. Playback stopped.'); return }
    if (repeat === 'one' && media.current) { media.current.currentTime = 0; media.current.play(); return }
    next()
  }
  const ref: Ref | null = current ? { share: current.share, path: current.path, name: current.name, kind: current.kind, size: current.size } : null
  const isFav = !!ref && fav.has(ref)
  const folderName = dir ? dir.split('/').pop() : 'Shared folder'
  const hidden = mode === 'closed' || (!current && mode !== 'page')
  const page = mode === 'page'
  const overlay = (page || mode === 'preview') && !!current

  // Controls sit over the picture and fade away while a video plays and nobody is using it. Music keeps them in view.
  const reveal = () => {
    setChromeActive(true)
    window.clearTimeout(idleTimer.current)
    idleTimer.current = window.setTimeout(() => setChromeActive(false), 2800)
  }
  useEffect(() => () => { window.clearTimeout(idleTimer.current); window.clearTimeout(clickTimer.current) }, [])
  useEffect(() => { if (flash) { const t = window.setTimeout(() => setFlash(null), 650); return () => window.clearTimeout(t) } }, [flash])
  const showChrome = !isVideo || !playing || chromeActive || menuOpen

  // Mouse: click plays or pauses, double-click seeks on the sides and goes full screen in the middle.
  // Touch: a tap shows or hides the controls; double-tap seeks.
  const onStageClick = () => {
    if (!isVideo || !current) return
    if (touch.current) {
      if (chromeActive && playing) { window.clearTimeout(idleTimer.current); setChromeActive(false) } else reveal()
      return
    }
    window.clearTimeout(clickTimer.current)
    clickTimer.current = window.setTimeout(toggle, 220)
  }
  const onStageDouble = (e: React.MouseEvent<HTMLElement>) => {
    window.clearTimeout(clickTimer.current)
    if (!isVideo || !current) return
    const r = e.currentTarget.getBoundingClientRect()
    const x = (e.clientX - r.left) / Math.max(1, r.width)
    if (x < 0.33) { seekBy(-10); setFlash({ side: 'l', n: Date.now() }) }
    else if (x > 0.67) { seekBy(10); setFlash({ side: 'r', n: Date.now() }) }
    else fullscreen()
  }
  const sleepSubtitle = sleepLeft > 0 ? ` · Sleep in ${sleepLeft} min` : sleep.kind === 'end' ? ' · Sleep at end of track' : ''

  return (
    <PlayerCtx.Provider value={ctx}>
      {children}
      <div className={`dock ${mode} ${isVideo ? 'is-video' : 'is-audio'}${current ? '' : ' is-empty'}`} hidden={hidden} role="region" aria-label="Media player">
        {mode === 'preview' && <div className="dock-scrim" onClick={stop} />}
        <div className="dock-panel nm-raised">
          <div
            ref={stage} className={'dock-stage' + (showChrome ? '' : ' idle')}
            onPointerDown={e => { touch.current = e.pointerType !== 'mouse' }}
            onPointerMove={e => { if (e.pointerType === 'mouse' && overlay) reveal() }}
            onPointerLeave={e => { if (e.pointerType === 'mouse' && playing) { window.clearTimeout(idleTimer.current); idleTimer.current = window.setTimeout(() => setChromeActive(false), 900) } }}
            onFocus={() => { if (overlay) reveal() }}
          >
            <video ref={media} playsInline preload="auto" aria-label={current ? stripExt(current.name) : 'Player'}
              onPlay={() => { setPlaying(true); autoplay.current = true; reveal() }}
              onPause={() => setPlaying(false)}
              onWaiting={() => setBuffering(true)}
              onPlaying={() => setBuffering(false)}
              onCanPlay={() => setBuffering(false)}
              onProgress={e => { const b = e.currentTarget.buffered; setBuffered(b.length ? b.end(b.length - 1) : 0) }}
              onTimeUpdate={e => setTime(e.currentTarget.currentTime)}
              onLoadedMetadata={e => { setDuration(e.currentTarget.duration); e.currentTarget.playbackRate = rate }}
              onDurationChange={e => setDuration(e.currentTarget.duration)}
              onEnded={ended}
              onError={recover} onStalled={() => { if (media.current && media.current.readyState < 2) recover() }}
              onClick={onStageClick} onDoubleClick={onStageDouble} />
            {current && !isVideo && (
              <div className="art" onClick={onStageClick}>
                <span className="disc"><FileTypeIcon kind="audio" size={page ? 72 : mode === 'preview' ? 56 : 28} /></span>
                {overlay && <span className={'eq' + (playing ? ' on' : '')} aria-hidden="true"><i /><i /><i /><i /><i /></span>}
              </div>
            )}
            {!current && page && (
              <div className="art stage-empty"><PlayIcon width={52} height={52} /><strong>Nothing is playing</strong><span className="small">Open a song or video from a folder, or pick one from Recent.</span></div>
            )}
            {current && buffering && <span className="stage-spin" role="status" aria-label="Loading" />}
            {overlay && isVideo && !buffering && (!playing || (touch.current && chromeActive)) && (
              <button className="stage-play" onClick={toggle} aria-label={playing ? 'Pause' : 'Play'}>
                {playing ? <PauseIcon width={40} height={40} /> : <PlayIcon width={40} height={40} />}
              </button>
            )}
            {flash && <span key={flash.n} className={'seek-flash ' + flash.side} aria-hidden="true">{flash.side === 'l' ? '−10 s' : '+10 s'}</span>}
            {overlay && (
              <StageControls
                visible={showChrome} isVideo={!!isVideo} playing={playing} time={time} duration={duration} buffered={buffered} volume={volume}
                onSeek={onSeek} onToggle={toggle} onNext={next} onPrev={prev} onMute={toggleMute} onVolume={setVolume}
                rate={rate} rates={RATES} onRate={setRate} sleepValue={sleepValue} sleepOptions={SLEEP_OPTIONS} onSleep={chooseSleep}
                shuffle={shuffle} onShuffle={toggleShuffle} repeat={repeat} onRepeat={cycleRepeat}
                canPip={!!isVideo && !!document.pictureInPictureEnabled} onPip={pip} fullscreen={isFullscreen} onFullscreen={fullscreen}
                onMenuChange={setMenuOpen}
              />
            )}
          </div>

          {current && (
            <div className="dock-main">
              <div className="dock-info" onClick={() => mode === 'mini' && openPlayer()}>
                <strong className="ellipsis" role={page ? 'heading' : undefined} aria-level={page ? 1 : undefined}>{stripExt(current.name)}</strong>
                <span className="muted small ellipsis">
                  {page
                    ? `${folderName} · ${current.kind === 'video' ? 'Video' : 'Music'} · ${formatSize(current.size)}${queue.length > 1 ? ` · ${index + 1} of ${queue.length}` : ''}`
                    : `${queue.length > 1 ? `${index + 1} of ${queue.length}` : 'Now playing'} · ${formatTime(time)} / ${formatTime(duration)}`}
                  {sleepSubtitle}
                </span>
              </div>
              {mode === 'mini' && (
                <>
                  <div className="seek-row">
                    <input className="seek" type="range" min={0} max={duration || 0} step={1} value={Math.min(time, duration || 0)}
                      style={{ ['--pct' as string]: pct + '%' }} onChange={e => onSeek(Number(e.target.value))} aria-label="Seek"
                      aria-valuetext={`${formatTime(time)} of ${formatTime(duration)}`} />
                  </div>
                  <div className="dock-controls">
                    <button className="nm-btn round" onClick={prev} aria-label="Previous track" title="Previous (P)"><PrevIcon /></button>
                    <button className="nm-btn round lg accent" onClick={toggle} aria-label={playing ? 'Pause' : 'Play'} title="Play / pause (Space)">{playing ? <PauseIcon /> : <PlayIcon />}</button>
                    <button className="nm-btn round" onClick={next} aria-label="Next track" title="Next (N)"><NextIcon /></button>
                    <button className={'nm-btn round sm hide-mini' + (shuffle ? ' on' : '')} onClick={toggleShuffle} aria-label="Shuffle" aria-pressed={shuffle}><ShuffleIcon /></button>
                    <button className={'nm-btn round sm hide-mini' + (repeat !== 'off' ? ' on' : '')} onClick={cycleRepeat} aria-label={`Repeat: ${repeat}`}><RepeatIcon />{repeat === 'one' && <b className="badge">1</b>}</button>
                  </div>
                </>
              )}
            </div>
          )}

          {current && (
            <div className="dock-side">
              {page && (
                <>
                  <button className={'pill' + (isFav ? ' on' : '')} onClick={() => { if (ref) { fav.toggle(ref); toast.show(isFav ? 'Removed from favorites' : 'Added to favorites') } }} aria-pressed={isFav}>
                    {isFav ? <StarFillIcon width={18} height={18} /> : <StarIcon width={18} height={18} />}<span>{isFav ? 'Favorited' : 'Favorite'}</span></button>
                  <a className="pill" href={fileUrl(current.share, current.path, true)}><DownloadIcon width={18} height={18} /><span>Download</span></a>
                  <button className="pill" onClick={leavePage}><ChevronDownIcon width={18} height={18} /><span>Mini player</span></button>
                  <span className="spacer" />
                  <button className="icon-btn" onClick={stop} aria-label="Stop and close the player" title="Stop and close"><CloseIcon /></button>
                </>
              )}
              {mode === 'preview' && (
                <>
                  <button className="pill" onClick={openPlayer}><ExpandIcon width={18} height={18} /><span>Open in player</span></button>
                  <span className="spacer" />
                  <button className="icon-btn" onClick={stop} aria-label="Close preview"><CloseIcon /></button>
                </>
              )}
              {mode === 'mini' && (
                <>
                  <button className="icon-btn" onClick={openPlayer} aria-label="Open the media player"><ExpandIcon /></button>
                  <button className="icon-btn" onClick={stop} aria-label="Close player"><CloseIcon /></button>
                </>
              )}
            </div>
          )}

          {page && (
            <PlayerLists
              tab={tab} setTab={setTab} current={current} loading={folder === null && !!current}
              folderName={folderName ?? ''} all={folderTracks} recent={recentTracks} queue={queue} index={index}
              onPick={pick} onGo={go} onMove={move} onRemove={removeAt} onClear={clearUpcoming}
            />
          )}
        </div>
      </div>
      <span className="sr-only" aria-live="polite">{playing && current ? `Now playing ${stripExt(current.name)}` : ''}</span>
    </PlayerCtx.Provider>
  )
}
