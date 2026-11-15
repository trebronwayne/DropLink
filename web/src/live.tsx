import { useCallback, useEffect, useRef, useState } from 'react'
import { api, AuthError, CueTuple, liveArtUrl, liveMediaUrl, LiveItem, LiveState, LiveText } from './api'
import { Aubree } from './Aubree'

/**
 * Live media streaming. While the phone's owner has it switched on, this page shows the phone's player: the same
 * song or video, in step with the phone, with lyrics and subtitles. Polls often on the page, rarely elsewhere.
 */
export function useLive(enabled: boolean, fast: boolean, onAuthLost: () => void) {
  const [live, setLive] = useState<LiveState>({ on: false })
  const [receivedAt, setReceivedAt] = useState(0)
  const lost = useRef(onAuthLost)
  lost.current = onAuthLost
  const refresh = useCallback(async () => {
    try {
      const s = await api.live()
      setLive(s)
      setReceivedAt(Date.now())
    } catch (e) {
      if (e instanceof AuthError) lost.current()
    }
  }, [])
  useEffect(() => {
    if (!enabled) return
    let stop = false
    let timer = 0
    const loop = async () => {
      await refresh()
      if (!stop) timer = window.setTimeout(loop, document.hidden ? 10_000 : fast ? 1_500 : 5_000)
    }
    loop()
    const onVisible = () => { if (!document.hidden) { window.clearTimeout(timer); loop() } }
    document.addEventListener('visibilitychange', onVisible)
    return () => { stop = true; window.clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [enabled, fast, refresh])
  return { live, receivedAt, refresh }
}

const fmt = (ms: number) => {
  const t = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

/** Index of the last cue that starts at or before `t`, or -1. */
function indexAt(cues: CueTuple[], t: number) {
  let lo = 0, hi = cues.length - 1, found = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (cues[mid][0] <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
  }
  return found
}

const Svg = ({ d, size = 22 }: { d: string; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={d} /></svg>
)
const PLAY = 'M7 4.5v15l12-7.5z'
const PAUSE = 'M8 5v14M16 5v14'
const FULL = 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5'
const VOL = 'M4 9v6h4l5 4V5L8 9H4zM17 8.5a5 5 0 010 7'
const MUTE = 'M4 9v6h4l5 4V5L8 9H4zM17 9l4 6M21 9l-4 6'
const NOTE = 'M9 18V6l10-2v12M9 18a3 3 0 11-6 0 3 3 0 016 0zM19 16a3 3 0 11-6 0 3 3 0 016 0z'

function Cover({ item, className }: { item: LiveItem; className?: string }) {
  const [broken, setBroken] = useState(false)
  useEffect(() => setBroken(false), [item.id])
  return broken
    ? <span className={'cover-fallback ' + (className ?? '')}><Svg d={NOTE} size={28} /></span>
    : <img className={className} src={liveArtUrl(item.id)} alt="" loading="lazy" onError={() => setBroken(true)} />
}

export function LivePage({ live, receivedAt }: { live: LiveState; receivedAt: number }) {
  const media = useRef<HTMLMediaElement | null>(null)
  const stage = useRef<HTMLDivElement | null>(null)
  const lyricsBox = useRef<HTMLDivElement | null>(null)
  const [joined, setJoined] = useState(false)
  const [follow, setFollow] = useState(true)
  const [t, setT] = useState(0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [text, setText] = useState<LiveText | null>(null)
  const [sub, setSub] = useState(0)
  const [lyricsOn, setLyricsOn] = useState(true)
  const [paused, setPaused] = useState(true)

  const now = live.on ? live.now : null
  const playing = live.on && live.playing
  const speed = live.on ? live.speed : 1
  const target = useCallback(() => (live.on ? live.positionMs + (live.playing ? (Date.now() - receivedAt) * live.speed : 0) : 0), [live, receivedAt])

  // Lyrics and subtitles for whatever is playing
  const id = now?.id
  useEffect(() => {
    setText(null); setSub(0)
    if (id) api.liveText(id).then(setText).catch(() => undefined)
  }, [id])

  // Stay in step with the phone: same position, same play or pause, same speed
  useEffect(() => {
    const el = media.current
    if (!el || !live.on || !follow || !joined) return
    el.playbackRate = speed
    const want = target() / 1000
    if (Math.abs(el.currentTime - want) > 1.6) el.currentTime = want
    if (playing && el.paused) el.play().catch(() => undefined)
    if (!playing && !el.paused) el.pause()
  }, [live, receivedAt, follow, joined, playing, speed, target])

  const onMeta = () => {
    const el = media.current
    if (!el) return
    el.volume = volume
    el.muted = muted
    if (follow) el.currentTime = target() / 1000
    if (joined && (!follow || playing)) el.play().catch(() => undefined)
  }

  const join = () => {
    setJoined(true)
    const el = media.current
    if (!el) return
    el.currentTime = target() / 1000
    el.playbackRate = speed
    if (playing || !follow) el.play().catch(() => undefined)
  }

  // Keep the current lyric line in view without moving the whole page
  const lyricIndex = text?.lyrics?.synced ? indexAt(text.lyrics.cues, t) : -1
  useEffect(() => {
    const box = lyricsBox.current
    if (!box || lyricIndex < 0) return
    const line = box.querySelector<HTMLElement>(`[data-i="${lyricIndex}"]`)
    if (line) box.scrollTo({ top: line.offsetTop - box.clientHeight / 2 + line.clientHeight / 2, behavior: 'smooth' })
  }, [lyricIndex])

  if (!live.on) {
    return (
      <div className="empty"><Aubree state="sleepy" size={140} /><h3>Live streaming is off</h3>
        <p className="muted">When the phone’s owner turns on live streaming, its player appears here and you can watch or listen along.</p></div>
    )
  }
  if (!now) {
    return (
      <div className="empty"><Aubree state="welcome" size={140} /><h3>Waiting for something to play</h3>
        <p className="muted">Live streaming is on. As soon as the phone plays a song or video, it shows up here.</p></div>
    )
  }

  const isVideo = now.kind === 'video'
  const subCues = text?.subtitles[sub]?.cues
  const subAt = subCues ? indexAt(subCues, t) : -1
  const subLine = subCues && subAt >= 0 && t < subCues[subAt][1] ? subCues[subAt][2] : ''
  const duration = live.durationMs || now.durationMs

  const common = {
    key: now.id,
    ref: media as React.RefObject<never>,
    src: liveMediaUrl(now.id),
    preload: 'auto' as const,
    onLoadedMetadata: onMeta,
    onTimeUpdate: () => media.current && setT(media.current.currentTime * 1000),
    onPlay: () => setPaused(false),
    onPause: () => setPaused(true),
    // The phone moved on to the next item; the next poll brings it. Nothing to do here.
    onEnded: () => setPaused(true)
  }

  return (
    <div className="live">
      <div className="live-main">
        <div className={'live-stage' + (isVideo ? ' video' : ' audio')} ref={stage}>
          {isVideo
            ? <video {...common} playsInline className="live-video" />
            : <audio {...common} />}
          {!isVideo && (
            <div className="live-audio-art">
              {lyricsOn && text?.lyrics
                ? (
                  <div className="lyrics" ref={lyricsBox} aria-label="Lyrics">
                    {text.lyrics.synced
                      ? text.lyrics.cues.map((c, i) => <p key={i} data-i={i} className={i === lyricIndex ? 'on' : ''}>{c[2] || '\u266A'}</p>)
                      : <pre>{text.lyrics.plain}</pre>}
                  </div>
                )
                : <Cover item={now} className="live-cover" />}
            </div>
          )}
          {isVideo && subLine && <div className="live-sub">{subLine}</div>}
          {!joined && (
            <button className="live-join" onClick={join}>
              <Svg d={PLAY} size={34} /><strong>Tap to join the live player</strong>
              <span className="small">Starts {isVideo ? 'the video' : 'the music'} here, in step with the phone</span>
            </button>
          )}
        </div>

        <div className="live-bar nm-raised">
          {follow
            ? <span className="live-badge"><span className="dot" /> {playing ? 'Live' : 'Paused on the phone'}</span>
            : <button className="icon-btn" aria-label={paused ? 'Play' : 'Pause'} onClick={() => { const el = media.current; if (el) (el.paused ? el.play() : el.pause()) }}><Svg d={paused ? PLAY : PAUSE} /></button>}
          <input
            type="range" className="live-seek" min={0} max={Math.max(1, duration)} value={Math.min(t, Math.max(1, duration))} disabled={follow}
            aria-label="Position" onChange={e => { const el = media.current; if (el) el.currentTime = Number(e.target.value) / 1000 }}
          />
          <span className="small muted live-time">{fmt(t)} / {fmt(duration)}</span>
          <button className="icon-btn" aria-label={muted ? 'Unmute' : 'Mute'} onClick={() => { const m = !muted; setMuted(m); if (media.current) media.current.muted = m }}><Svg d={muted ? MUTE : VOL} /></button>
          <input type="range" className="live-vol" min={0} max={1} step={0.05} value={muted ? 0 : volume} aria-label="Volume"
            onChange={e => { const v = Number(e.target.value); setVolume(v); setMuted(false); if (media.current) { media.current.volume = v; media.current.muted = false } }} />
          {isVideo && <button className="icon-btn" aria-label="Full screen" onClick={() => stage.current?.requestFullscreen?.().catch(() => undefined)}><Svg d={FULL} /></button>}
        </div>

        <div className="live-info">
          <h2 className="live-title">{now.title}</h2>
          <p className="muted">{[now.artist, now.album].filter(Boolean).join(' · ') || (isVideo ? 'Video' : 'Music')}</p>
          <div className="live-actions">
            <label className="chip on-off"><input type="checkbox" checked={follow} onChange={e => { setFollow(e.target.checked); if (e.target.checked) { const el = media.current; if (el) el.currentTime = target() / 1000 } }} /> Follow the phone</label>
            {!isVideo && text?.lyrics && <button className={'chip' + (lyricsOn ? ' on' : '')} onClick={() => setLyricsOn(!lyricsOn)}>Lyrics</button>}
            {isVideo && text && text.subtitles.length > 0 && (
              <select className="nm-select" aria-label="Subtitles" value={sub} onChange={e => setSub(Number(e.target.value))}>
                {text.subtitles.map((s, i) => <option key={i} value={i}>{s.name}</option>)}
                <option value={-1}>Subtitles off</option>
              </select>
            )}
          </div>
          {!isVideo && text && !text.lyrics && <p className="muted small">No lyrics were found for this song.</p>}
          {text?.lyrics && <p className="muted small">{text.lyrics.source}</p>}
        </div>
      </div>

      <aside className="live-next" aria-label="Up next">
        <h3>Up next</h3>
        <p className="muted small">The phone controls what plays. This is its queue.</p>
        <ul>
          {live.queue.map(item => (
            <li key={item.id} className={item.id === now.id ? 'playing' : ''}>
              <span className="live-thumb"><Cover item={item} className="live-thumb-img" /></span>
              <span className="live-next-text"><strong className="ellipsis">{item.title}</strong><span className="muted small ellipsis">{item.kind === 'video' ? 'Video' : item.artist || 'Music'} · {fmt(item.durationMs)}</span></span>
              {item.id === now.id && <span className="live-badge small"><span className="dot" /> Playing</span>}
            </li>
          ))}
        </ul>
      </aside>
    </div>
  )
}
