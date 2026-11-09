import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, ReactNode } from 'react'
import { fileUrl } from './api'
import { FileTypeIcon, MinimizeIcon, ExpandIcon, NextIcon, PauseIcon, PipIcon, PlayIcon, PrevIcon, QueueIcon, RepeatIcon, ShuffleIcon, VolumeIcon, MuteIcon, CloseIcon, DownloadIcon, TrashIcon } from './icons'
import { formatTime, stripExt } from './util'
import { load, save } from './prefs'

export type Track = { share: string; path: string; name: string; kind: 'audio' | 'video'; size: number }
type Repeat = 'off' | 'all' | 'one'
type Mode = 'closed' | 'mini' | 'full'

type Ctx = {
  current: Track | null
  queue: Track[]
  playing: boolean
  play: (tracks: Track[], start?: number) => void
  enqueue: (t: Track[]) => void
  toggle: () => void
  next: () => void
  prev: () => void
  seekBy: (d: number) => void
  setMode: (m: Mode) => void
}
const PlayerCtx = createContext<Ctx | null>(null)
export const usePlayer = () => useContext(PlayerCtx)!

const trackKey = (t: Track) => t.share + '|' + t.path

export function PlayerProvider({ children }: { children: ReactNode }) {
  const media = useRef<HTMLVideoElement>(null)
  const saved = useMemo(() => load<{ queue: Track[]; index: number; time: number } | null>('player', null), [])
  const [queue, setQueue] = useState<Track[]>(saved?.queue ?? [])
  const [index, setIndex] = useState(saved?.index ?? 0)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(() => load('volume', 1))
  const [rate, setRate] = useState(1)
  const [shuffle, setShuffle] = useState(() => load('shuffle', false))
  const [repeat, setRepeat] = useState<Repeat>(() => load('repeat', 'off'))
  const [mode, setMode] = useState<Mode>(saved?.queue?.length ? 'mini' : 'closed')
  const [showQueue, setShowQueue] = useState(false)
  const resumeAt = useRef<number>(saved?.time ?? 0)
  const failures = useRef(0)

  const current = queue[index] ?? null

  // Load the track whenever it changes. The element is never remounted, so playback survives all navigation.
  const src = current ? fileUrl(current.share, current.path) : ''
  useEffect(() => {
    const el = media.current
    if (!el) return
    failures.current = 0
    if (!src) { el.removeAttribute('src'); el.load(); return }
    el.src = src
    const start = resumeAt.current
    resumeAt.current = 0
    if (start > 1) el.addEventListener('loadedmetadata', () => { el.currentTime = start }, { once: true })
    if (autoplay.current) el.play().catch(() => setPlaying(false))
  }, [src])
  const autoplay = useRef(false)

  useEffect(() => { if (media.current) media.current.volume = volume; save('volume', volume) }, [volume])
  useEffect(() => { if (media.current) media.current.playbackRate = rate }, [rate, src])
  useEffect(() => { save('shuffle', shuffle); save('repeat', repeat) }, [shuffle, repeat])

  // Remember queue and position so a refresh or a dropped connection resumes where you were.
  useEffect(() => {
    const id = window.setInterval(() => {
      const el = media.current
      if (queue.length) save('player', { queue: queue.slice(0, 300), index, time: el?.currentTime ?? 0 })
    }, 4000)
    return () => window.clearInterval(id)
  }, [queue, index])

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
    setQueue([]); setIndex(0); setPlaying(false); setMode('closed'); save('player', null)
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

  const isVideo = current?.kind === 'video'
  const ctx: Ctx = { current, queue, playing, play, enqueue, toggle, next, prev, seekBy, setMode }
  const pct = duration ? (time / duration) * 100 : 0
  const onSeek = (v: number) => { if (media.current) media.current.currentTime = v }

  const pip = () => { const el = media.current; if (el && document.pictureInPictureEnabled) (document.pictureInPictureElement ? document.exitPictureInPicture() : el.requestPictureInPicture()).catch(() => undefined) }
  const fullscreen = () => { media.current?.requestFullscreen?.().catch(() => undefined) }

  return (
    <PlayerCtx.Provider value={ctx}>
      {children}
      <div className={`dock ${mode} ${isVideo ? 'is-video' : 'is-audio'}`} hidden={mode === 'closed' || !current} aria-label="Player">
        {mode === 'full' && <div className="dock-scrim" onClick={() => setMode('mini')} />}
        <div className="dock-panel nm-raised">
          <div className="dock-stage">
            <video ref={media} playsInline preload="auto"
              onPlay={() => { setPlaying(true); autoplay.current = true }}
              onPause={() => setPlaying(false)}
              onTimeUpdate={e => setTime(e.currentTarget.currentTime)}
              onLoadedMetadata={e => { setDuration(e.currentTarget.duration); e.currentTarget.playbackRate = rate }}
              onDurationChange={e => setDuration(e.currentTarget.duration)}
              onEnded={() => { if (repeat === 'one' && media.current) { media.current.currentTime = 0; media.current.play() } else next() }}
              onError={recover} onStalled={() => { if (media.current && media.current.readyState < 2) recover() }}
              onClick={() => isVideo && toggle()} />
            {!isVideo && <div className="art nm-inset"><FileTypeIcon kind="audio" size={mode === 'full' ? 112 : 36} /></div>}
          </div>

          <div className="dock-main">
            <div className="dock-info" onClick={() => mode === 'mini' && setMode('full')}>
              <strong className="ellipsis">{current ? stripExt(current.name) : ''}</strong>
              <span className="muted small ellipsis">{queue.length > 1 ? `${index + 1} of ${queue.length}` : 'Now playing'} · {formatTime(time)} / {formatTime(duration)}</span>
            </div>

            <input className="seek" type="range" min={0} max={duration || 0} step={0.5} value={Math.min(time, duration || 0)}
              style={{ ['--pct' as string]: pct + '%' }} onChange={e => onSeek(Number(e.target.value))} aria-label="Seek" />

            <div className="dock-controls">
              <button className={'nm-btn round sm hide-mini' + (shuffle ? ' on' : '')} onClick={() => setShuffle(s => !s)} aria-label="Shuffle" aria-pressed={shuffle}><ShuffleIcon /></button>
              <button className="nm-btn round" onClick={prev} aria-label="Previous"><PrevIcon /></button>
              <button className="nm-btn round lg accent" onClick={toggle} aria-label={playing ? 'Pause' : 'Play'}>{playing ? <PauseIcon /> : <PlayIcon />}</button>
              <button className="nm-btn round" onClick={next} aria-label="Next"><NextIcon /></button>
              <button className={'nm-btn round sm hide-mini' + (repeat !== 'off' ? ' on' : '')} onClick={() => setRepeat(r => (r === 'off' ? 'all' : r === 'all' ? 'one' : 'off'))} aria-label={`Repeat ${repeat}`}>
                <RepeatIcon />{repeat === 'one' && <b className="badge">1</b>}</button>
            </div>
          </div>

          <div className="dock-side">
            {mode === 'full' && (
              <>
                <label className="vol hide-narrow"><button className="icon-btn" onClick={() => setVolume(v => (v > 0 ? 0 : 1))} aria-label="Mute">{volume === 0 ? <MuteIcon /> : <VolumeIcon />}</button>
                  <input className="seek short" type="range" min={0} max={1} step={0.02} value={volume} style={{ ['--pct' as string]: volume * 100 + '%' }} onChange={e => setVolume(Number(e.target.value))} aria-label="Volume" /></label>
                <select className="nm-select" value={rate} onChange={e => setRate(Number(e.target.value))} aria-label="Speed">
                  {[0.5, 0.75, 1, 1.25, 1.5, 2].map(r => <option key={r} value={r}>{r}x</option>)}</select>
                <button className={'icon-btn' + (showQueue ? ' on' : '')} onClick={() => setShowQueue(s => !s)} aria-label="Queue"><QueueIcon /></button>
                {isVideo && <button className="icon-btn" onClick={pip} aria-label="Picture in picture"><PipIcon /></button>}
                {isVideo && <button className="icon-btn" onClick={fullscreen} aria-label="Fullscreen"><ExpandIcon /></button>}
                {current && <a className="icon-btn" href={fileUrl(current.share, current.path, true)} aria-label="Download"><DownloadIcon /></a>}
              </>
            )}
            <button className="icon-btn" onClick={() => setMode(mode === 'full' ? 'mini' : 'full')} aria-label={mode === 'full' ? 'Minimize player' : 'Expand player'}>{mode === 'full' ? <MinimizeIcon /> : <ExpandIcon />}</button>
            <button className="icon-btn" onClick={stop} aria-label="Close player"><CloseIcon /></button>
          </div>

          {mode === 'full' && showQueue && (
            <ol className="queue">
              {queue.map((t, i) => (
                <li key={trackKey(t)} className={i === index ? 'now' : ''}>
                  <button onClick={() => go(i)}><FileTypeIcon kind={t.kind} size={22} /><span className="ellipsis">{stripExt(t.name)}</span></button>
                  <button className="icon-btn" aria-label="Remove from queue" onClick={() => {
                    setQueue(q => q.filter((_, j) => j !== i))
                    if (i < index) setIndex(index - 1)
                    else if (i === index) { autoplay.current = playing; setIndex(Math.min(index, queue.length - 2)) }
                  }}><TrashIcon /></button>
                </li>
              ))}
            </ol>
          )}
        </div>
      </div>
    </PlayerCtx.Provider>
  )
}
