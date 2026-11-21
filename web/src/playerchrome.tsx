import { useEffect, useRef, useState } from 'react'
import {
  CheckIcon, ChevronRightIcon, FullscreenExitIcon, FullscreenIcon, MuteIcon, NextIcon, PauseIcon, PipIcon, PlayIcon, PrevIcon, SettingsIcon, VolumeIcon, ArrowLeftIcon
} from './icons'
import { formatTime } from './util'

export type StageControlsProps = {
  isVideo: boolean
  /** Controls fade away while a video plays and nobody is touching it. */
  visible: boolean
  playing: boolean
  time: number
  duration: number
  /** Seconds that are already downloaded. */
  buffered: number
  volume: number
  onSeek: (v: number) => void
  onToggle: () => void
  onNext: () => void
  onPrev: () => void
  onMute: () => void
  onVolume: (v: number) => void
  rate: number
  rates: number[]
  onRate: (r: number) => void
  sleepValue: string
  sleepOptions: { value: string; label: string }[]
  onSleep: (v: string) => void
  shuffle: boolean
  onShuffle: () => void
  repeat: 'off' | 'all' | 'one'
  onRepeat: () => void
  canPip: boolean
  onPip: () => void
  fullscreen: boolean
  onFullscreen: () => void
  /** Reports whether the settings menu is open, so the controls stay on screen while it is. */
  onMenuChange: (open: boolean) => void
}

/** The controls laid over the picture, the way a video site does it: a thin progress line, then a few round buttons. */
export function StageControls(p: StageControlsProps) {
  const [menu, setMenu] = useState(false)
  const pct = p.duration ? Math.min(100, (p.time / p.duration) * 100) : 0
  const buf = p.duration ? Math.min(100, (p.buffered / p.duration) * 100) : 0
  const set = (open: boolean) => { setMenu(open); p.onMenuChange(open) }

  return (
    <div className={'sc' + (p.visible || menu ? ' show' : '')} onClick={e => e.stopPropagation()} onDoubleClick={e => e.stopPropagation()}>
      <input
        className="sc-seek" type="range" min={0} max={p.duration || 0} step={1} value={Math.min(p.time, p.duration || 0)}
        style={{ ['--pct' as string]: pct + '%', ['--buf' as string]: Math.max(pct, buf) + '%' }}
        onChange={e => p.onSeek(Number(e.target.value))} aria-label="Seek" aria-valuetext={`${formatTime(p.time)} of ${formatTime(p.duration)}`}
      />
      <div className="sc-row">
        <button className="sc-btn" onClick={p.onPrev} aria-label="Previous" title="Previous (P)"><PrevIcon width={22} height={22} /></button>
        <button className="sc-btn big" onClick={p.onToggle} aria-label={p.playing ? 'Pause' : 'Play'} title="Play / pause (Space)">
          {p.playing ? <PauseIcon width={26} height={26} /> : <PlayIcon width={26} height={26} />}
        </button>
        <button className="sc-btn" onClick={p.onNext} aria-label="Next" title="Next (N)"><NextIcon width={22} height={22} /></button>
        <div className="sc-vol">
          <button className="sc-btn" onClick={p.onMute} aria-label={p.volume === 0 ? 'Unmute' : 'Mute'} title="Mute (M)">
            {p.volume === 0 ? <MuteIcon width={22} height={22} /> : <VolumeIcon width={22} height={22} />}
          </button>
          <input className="sc-slider" type="range" min={0} max={1} step={0.02} value={p.volume} aria-label="Volume"
            style={{ ['--pct' as string]: p.volume * 100 + '%' }} onChange={e => p.onVolume(Number(e.target.value))} />
        </div>
        <span className="sc-time">{formatTime(p.time)} <span>/ {formatTime(p.duration)}</span></span>
        <span className="spacer" />
        <div className="sc-menu-wrap">
          <button className={'sc-btn' + (menu ? ' on' : '')} onClick={() => set(!menu)} aria-label="Settings" aria-haspopup="menu" aria-expanded={menu} title="Settings">
            <SettingsIcon width={22} height={22} />
          </button>
          {menu && <SettingsMenu {...p} onClose={() => set(false)} />}
        </div>
        {p.isVideo && p.canPip && <button className="sc-btn" onClick={p.onPip} aria-label="Picture in picture" title="Picture in picture"><PipIcon width={22} height={22} /></button>}
        {p.isVideo && <button className="sc-btn" onClick={p.onFullscreen} aria-label={p.fullscreen ? 'Exit full screen' : 'Full screen'} title="Full screen (F)">
          {p.fullscreen ? <FullscreenExitIcon width={22} height={22} /> : <FullscreenIcon width={22} height={22} />}
        </button>}
      </div>
    </div>
  )
}

/** Speed, sleep timer, shuffle and repeat live here, out of sight until needed. */
function SettingsMenu(p: StageControlsProps & { onClose: () => void }) {
  const [view, setView] = useState<'main' | 'speed' | 'sleep'>('main')
  const box = useRef<HTMLDivElement>(null)
  const sleepLabel = p.sleepOptions.find(o => o.value === p.sleepValue)?.label ?? 'Off'

  useEffect(() => {
    box.current?.querySelector<HTMLElement>('button')?.focus()
  }, [view])
  useEffect(() => {
    const away = (e: Event) => { if (!box.current?.parentElement?.contains(e.target as Node)) p.onClose() }
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); p.onClose() }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const items = Array.from(box.current?.querySelectorAll<HTMLElement>('button') ?? [])
        const at = items.indexOf(document.activeElement as HTMLElement)
        items[(at + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
      }
    }
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('keydown', key, true)
    return () => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('keydown', key, true) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="sc-menu" ref={box} role="menu" aria-label="Player settings">
      {view === 'main' && (
        <>
          <button role="menuitem" onClick={() => setView('speed')}><span>Playback speed</span><span className="val">{p.rate === 1 ? 'Normal' : p.rate + 'x'}<ChevronRightIcon width={16} height={16} /></span></button>
          <button role="menuitem" onClick={() => setView('sleep')}><span>Sleep timer</span><span className="val">{sleepLabel}<ChevronRightIcon width={16} height={16} /></span></button>
          <button role="menuitemcheckbox" aria-checked={p.shuffle} onClick={p.onShuffle}><span>Shuffle</span><span className="val">{p.shuffle ? 'On' : 'Off'}</span></button>
          <button role="menuitem" onClick={p.onRepeat}><span>Repeat</span><span className="val">{p.repeat === 'off' ? 'Off' : p.repeat === 'all' ? 'All' : 'One'}</span></button>
        </>
      )}
      {view === 'speed' && (
        <>
          <button className="back" role="menuitem" onClick={() => setView('main')}><ArrowLeftIcon width={16} height={16} /><span>Playback speed</span></button>
          {p.rates.map(r => (
            <button key={r} role="menuitemradio" aria-checked={p.rate === r} onClick={() => { p.onRate(r); setView('main') }}>
              <span className="tick">{p.rate === r && <CheckIcon width={16} height={16} />}</span><span>{r === 1 ? 'Normal' : r + 'x'}</span>
            </button>
          ))}
        </>
      )}
      {view === 'sleep' && (
        <>
          <button className="back" role="menuitem" onClick={() => setView('main')}><ArrowLeftIcon width={16} height={16} /><span>Sleep timer</span></button>
          {p.sleepOptions.map(o => (
            <button key={o.value} role="menuitemradio" aria-checked={p.sleepValue === o.value} onClick={() => { p.onSleep(o.value); setView('main') }}>
              <span className="tick">{p.sleepValue === o.value && <CheckIcon width={16} height={16} />}</span><span>{o.label}</span>
            </button>
          ))}
        </>
      )}
    </div>
  )
}
