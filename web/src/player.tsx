import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, ReactNode } from 'react'
import { fileUrl } from './api'
import { FileTypeIcon, MinimizeIcon, ExpandIcon, NextIcon, PauseIcon, PipIcon, PlayIcon, PrevIcon, QueueIcon, RepeatIcon, ShuffleIcon, VolumeIcon, MuteIcon, CloseIcon, DownloadIcon, TrashIcon, MoonIcon } from './icons'
import { formatTime, stripExt } from './util'
import { getResume, load, save, setResume } from './prefs'
import { useToast } from './toast'

export type Track = { share: string; path: string; name: string; kind: 'audio' | 'video'; size: number }
type Repeat = 'off' | 'all' | 'one'
type Mode = 'closed' | 'mini' | 'full'
type Sleep = { kind: 'off' } | { kind: 'time'; at: number; choice: string } | { kind: 'end' }

type Ctx = {
  current: Track | null
  queue: Track[]
  playing: boolean
  mode: Mode
  play: (tracks: Track[], start?: number) => void
  enqueue: (t: Track[]) => void
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

const trackKey = (t: Track) => t.share + '|' + t.path
const SLEEP_OPTIONS: { value: string; label: string }[] = [
  { value: 'off', label: 'Off' }, { value: '15', label: '15 minutes' }, { value: '30', label: '30 minutes' },
  { value: '45', label: '45 minutes' }, { value: '60', label: '1 hour' }, { value: 'end', label: 'End of track' }
]
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2]

export function PlayerProvider({ children }: { children: ReactNode }) {
  const toast = useToast()
  const media = useRef<HTMLVideoElement>(null)
  const saved = useMemo(() => load<{ queue: Track[]; index: number; time: number } | null>('player', null), [])
  const [queue, setQueue] = useState<Track[]>(saved?.queue ?? [])
  const [index, setIndex] = useState(saved?.index ?? 0)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(() => load('volume', 1))
  const [rate, setRate] = useState(() => load('rate', 1))
  const [shuffle, setShuffle] = useState(() => load('shuffle', false))
  const [repeat, setRepeat] = useState<Repeat>(() => load('repeat', 'off'))
  const [mode, setMode] = useState<Mode>(saved?.queue?.length ? 'mini' : 'closed')
  const [showQueue, setShowQueue] = useState(false)
  const [sleep, setSleep] = useState<Sleep>({ kind: 'off' })
  const [, tick] = useState(0)
  const resumeAt = useRef<number>(saved?.time ?? 0)
  const failures = useRef(0)
  const lastVolume = useRef(1)
  const autoplay = useRef(false)
  const sleepRef = useRef<Sleep>(sleep)
  sleepRef.current = sleep

  const current = queue[index] ?? null

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
  const fullscreen = useCallback(() => { media.current?.requestFullscreen?.().catch(() => undefined) }, [])

  const play = useCallback((tracks: Track[], start = 0) => {
    if (!tracks.length) return
    autoplay.current = true
    setQueue(tracks)
    setIndex(start)
    setMode(m => (m === 'closed' ? (tracks[start]?.kind === 'video' ? 'full' : 'mini') : m))
    if (queue.length && trackKey(queue[index] ?? tracks[start]) === trackKey(tracks[start]) && media.current) {
      media.current.currentTime = 0; media.current.play().catch(() => undefined)
    }
  }, [queue, index])

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
    setQueue([]); setIndex(0); setPlaying(false); setMode('closed'); setSleep({ kind: 'off' }); save('player', null)
  }

  const move = (i: number, d: -1 | 1) => {
    const j = i + d
    if (j < 0 || j >= queue.length) return
    setQueue(q => { const n = q.slice(); const t = n[i]; n[i] = n[j]; n[j] = t; return n })
    setIndex(cur => (cur === i ? j : cur === j ? i : cur))
  }
  const clearUpcoming = () => { setQueue(q => q.slice(index, index + 1)); setIndex(0) }

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

  // Escape folds the full player back into the mini player.
  useEffect(() => {
    if (mode !== 'full') return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !document.fullscreenElement) setMode('mini') }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [mode])

  const isVideo = current?.kind === 'video'
  const ctx: Ctx = { current, queue, playing, mode, play, enqueue, toggle, next, prev, seekBy, volumeBy, toggleMute, toggleShuffle, cycleRepeat, cycleRate, fullscreen, setMode }
  const pct = duration ? (time / duration) * 100 : 0
  const onSeek = (v: number) => { if (media.current) media.current.currentTime = v }
  const pip = () => { const el = media.current; if (el && document.pictureInPictureEnabled) (document.pictureInPictureElement ? document.exitPictureInPicture() : el.requestPictureInPicture()).catch(() => undefined) }
  const upNext = queue[index + 1]
  const ended = () => {
    if (current) setResume(current.share, current.path, 0, 0)
    if (sleepRef.current.kind === 'end') { setSleep({ kind: 'off' }); setPlaying(false); toast.show('Sleep timer finished. Playback stopped.'); return }
    if (repeat === 'one' && media.current) { media.current.currentTime = 0; media.current.play(); return }
    next()
  }

  return (
    <PlayerCtx.Provider value={ctx}>
      {children}
      <div className={`dock ${mode} ${isVideo ? 'is-video' : 'is-audio'}`} hidden={mode === 'closed' || !current} role="region" aria-label="Media player">
        {mode === 'full' && <div className="dock-scrim" onClick={() => setMode('mini')} />}
        <div className="dock-panel nm-raised">
          <div className="dock-stage">
            <video ref={media} playsInline preload="auto" aria-label={current ? stripExt(current.name) : 'Player'}
              onPlay={() => { setPlaying(true); autoplay.current = true }}
              onPause={() => setPlaying(false)}
              onTimeUpdate={e => setTime(e.currentTarget.currentTime)}
              onLoadedMetadata={e => { setDuration(e.currentTarget.duration); e.currentTarget.playbackRate = rate }}
              onDurationChange={e => setDuration(e.currentTarget.duration)}
              onEnded={ended}
              onError={recover} onStalled={() => { if (media.current && media.current.readyState < 2) recover() }}
              onClick={() => isVideo && toggle()} onDoubleClick={() => isVideo && fullscreen()} />
            {!isVideo && <div className="art nm-inset"><FileTypeIcon kind="audio" size={mode === 'full' ? 112 : 36} /></div>}
          </div>

          <div className="dock-main">
            <div className="dock-info" onClick={() => mode === 'mini' && setMode('full')}>
              <strong className="ellipsis">{current ? stripExt(current.name) : ''}</strong>
              <span className="muted small ellipsis">{queue.length > 1 ? `${index + 1} of ${queue.length}` : 'Now playing'} · {formatTime(time)} / {formatTime(duration)}{sleepLeft > 0 ? ` · Sleep in ${sleepLeft} min` : sleep.kind === 'end' ? ' · Sleep at end' : ''}</span>
              {mode === 'full' && upNext && <span className="muted small ellipsis">Up next: {stripExt(upNext.name)}</span>}
            </div>

            <input className="seek" type="range" min={0} max={duration || 0} step={1} value={Math.min(time, duration || 0)}
              style={{ ['--pct' as string]: pct + '%' }} onChange={e => onSeek(Number(e.target.value))} aria-label="Seek"
              aria-valuetext={`${formatTime(time)} of ${formatTime(duration)}`} />

            <div className="dock-controls">
              <button className={'nm-btn round sm hide-mini' + (shuffle ? ' on' : '')} onClick={toggleShuffle} aria-label="Shuffle" aria-pressed={shuffle}><ShuffleIcon /></button>
              <button className="nm-btn round skip" onClick={() => seekBy(-10)} aria-label="Back 10 seconds" title="Back 10 s (J)"><span className="skip-label">−10</span></button>
              <button className="nm-btn round" onClick={prev} aria-label="Previous track" title="Previous (P)"><PrevIcon /></button>
              <button className="nm-btn round lg accent" onClick={toggle} aria-label={playing ? 'Pause' : 'Play'} title="Play / pause (Space)">{playing ? <PauseIcon /> : <PlayIcon />}</button>
              <button className="nm-btn round" onClick={next} aria-label="Next track" title="Next (N)"><NextIcon /></button>
              <button className="nm-btn round skip" onClick={() => seekBy(10)} aria-label="Forward 10 seconds" title="Forward 10 s (L)"><span className="skip-label">+10</span></button>
              <button className={'nm-btn round sm hide-mini' + (repeat !== 'off' ? ' on' : '')} onClick={cycleRepeat} aria-label={`Repeat: ${repeat}`}>
                <RepeatIcon />{repeat === 'one' && <b className="badge">1</b>}</button>
            </div>
          </div>

          <div className="dock-side">
            {mode === 'full' && (
              <>
                <label className="vol hide-narrow"><button className="icon-btn" onClick={toggleMute} aria-label={volume === 0 ? 'Unmute' : 'Mute'}>{volume === 0 ? <MuteIcon /> : <VolumeIcon />}</button>
                  <input className="seek short" type="range" min={0} max={1} step={0.02} value={volume} style={{ ['--pct' as string]: volume * 100 + '%' }} onChange={e => setVolume(Number(e.target.value))} aria-label="Volume" /></label>
                <select className="nm-select" value={rate} onChange={e => setRate(Number(e.target.value))} aria-label="Playback speed">
                  {RATES.map(r => <option key={r} value={r}>{r}x</option>)}</select>
                <label className="sleep hide-narrow" title="Sleep timer"><MoonIcon />
                  <select className="nm-select" value={sleepValue} onChange={e => chooseSleep(e.target.value)} aria-label="Sleep timer">
                    {SLEEP_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select></label>
                <button className={'icon-btn' + (showQueue ? ' on' : '')} onClick={() => setShowQueue(s => !s)} aria-label="Queue" aria-expanded={showQueue}><QueueIcon /></button>
                {isVideo && <button className="icon-btn" onClick={pip} aria-label="Picture in picture"><PipIcon /></button>}
                {isVideo && <button className="icon-btn" onClick={fullscreen} aria-label="Fullscreen" title="Fullscreen (F)"><ExpandIcon /></button>}
                {current && <a className="icon-btn" href={fileUrl(current.share, current.path, true)} aria-label="Download this file"><DownloadIcon /></a>}
              </>
            )}
            <button className="icon-btn" onClick={() => setMode(mode === 'full' ? 'mini' : 'full')} aria-label={mode === 'full' ? 'Minimize player' : 'Expand player'}>{mode === 'full' ? <MinimizeIcon /> : <ExpandIcon />}</button>
            <button className="icon-btn" onClick={stop} aria-label="Close player"><CloseIcon /></button>
          </div>

          {mode === 'full' && showQueue && (
            <div className="queue-wrap">
              <div className="queue-head"><strong>Queue</strong><span className="muted small">{queue.length} item{queue.length === 1 ? '' : 's'}</span><span className="spacer" />
                {queue.length > 1 && <button className="nm-btn sm" onClick={clearUpcoming}>Clear others</button>}</div>
              <ol className="queue">
                {queue.map((t, i) => (
                  <li key={trackKey(t)} className={i === index ? 'now' : ''} aria-current={i === index ? 'true' : undefined}>
                    <button onClick={() => go(i)}><FileTypeIcon kind={t.kind} size={22} /><span className="ellipsis">{stripExt(t.name)}</span></button>
                    <button className="icon-btn sm" aria-label={`Move ${stripExt(t.name)} up`} disabled={i === 0} onClick={() => move(i, -1)}><MinimizeIcon className="flip-y" width={18} height={18} /></button>
                    <button className="icon-btn sm" aria-label={`Move ${stripExt(t.name)} down`} disabled={i === queue.length - 1} onClick={() => move(i, 1)}><MinimizeIcon width={18} height={18} /></button>
                    <button className="icon-btn sm" aria-label={`Remove ${stripExt(t.name)} from queue`} onClick={() => {
                      setQueue(q => q.filter((_, j) => j !== i))
                      if (i < index) setIndex(index - 1)
                      else if (i === index) { autoplay.current = playing; setIndex(Math.min(index, queue.length - 2)) }
                    }}><TrashIcon width={18} height={18} /></button>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </div>
      </div>
      <span className="sr-only" aria-live="polite">{playing && current ? `Now playing ${stripExt(current.name)}` : ''}</span>
    </PlayerCtx.Provider>
  )
}
