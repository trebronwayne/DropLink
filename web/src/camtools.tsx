import { useCallback, useEffect, useRef, useState } from 'react'
import { CloseIcon, DownloadIcon, MicIcon, StopIcon, TrashIcon, UploadIcon, ArrowLeftIcon, ArrowRightIcon } from './icons'
import type { NodeSnapshot } from './camera'

/**
 * Helpers for the Cameras page: a snapshot gallery that survives reloads, motion detection on a live picture,
 * small "last seen" pictures for cameras that went offline, a battery hint for sharing devices, a dark kiosk screen
 * for a device that does nothing but share, and the banner that keeps sharing visible and stoppable on every page.
 */

/* ---------------------------------------------------------------------------------------------- */
/* Snapshot gallery (IndexedDB, with an in-memory fallback)                                        */
/* ---------------------------------------------------------------------------------------------- */

export type Shot = { id: string; node: string; at: number; kind: 'manual' | 'motion'; blob: Blob }

const DB = 'droplink-camera'
const STORE = 'shots'
const MAX_SHOTS = 80

let memory: Shot[] = []
let dbPromise: Promise<IDBDatabase | null> | null = null
const listeners = new Set<() => void>()
const notify = () => listeners.forEach(l => l())

function openDb(): Promise<IDBDatabase | null> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise(resolve => {
    try {
      const req = indexedDB.open(DB, 1)
      req.onupgradeneeded = () => { req.result.createObjectStore(STORE, { keyPath: 'id' }) }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => resolve(null)
      req.onblocked = () => resolve(null)
    } catch { resolve(null) }
  })
  return dbPromise
}

function tx<T>(db: IDBDatabase, mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode)
    const r = run(t.objectStore(STORE))
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => reject(r.error)
  })
}

async function loadShots(): Promise<Shot[]> {
  const db = await openDb()
  if (!db) return [...memory].sort((a, b) => b.at - a.at)
  try {
    const all = await tx<Shot[]>(db, 'readonly', s => s.getAll())
    return all.sort((a, b) => b.at - a.at)
  } catch { return [...memory].sort((a, b) => b.at - a.at) }
}

/** Saves a snapshot. Returns false if the browser had no room, in which case the oldest ones were dropped and it is kept for this visit only. */
export async function addShot(node: string, blob: Blob, kind: Shot['kind']): Promise<boolean> {
  const shot: Shot = { id: Math.random().toString(36).slice(2) + Date.now().toString(36), node, at: Date.now(), kind, blob }
  const db = await openDb()
  let stored = true
  if (db) {
    try { await tx(db, 'readwrite', s => s.put(shot)) } catch { stored = false }
  } else stored = false
  if (!stored) {
    memory = [shot, ...memory].slice(0, 20)
  } else {
    const all = await loadShots()
    for (const old of all.slice(MAX_SHOTS)) { try { await tx(db as IDBDatabase, 'readwrite', s => s.delete(old.id)) } catch { /* next time */ } }
  }
  notify()
  return stored
}

async function removeShot(id: string) {
  memory = memory.filter(s => s.id !== id)
  const db = await openDb()
  if (db) { try { await tx(db, 'readwrite', s => s.delete(id)) } catch { /* already gone */ } }
  notify()
}

async function clearShots() {
  memory = []
  const db = await openDb()
  if (db) { try { await tx(db, 'readwrite', s => s.clear()) } catch { /* nothing to clear */ } }
  notify()
}

function useShots() {
  const [shots, setShots] = useState<Shot[]>([])
  const refresh = useCallback(() => { void loadShots().then(setShots) }, [])
  useEffect(() => {
    refresh()
    listeners.add(refresh)
    return () => { listeners.delete(refresh) }
  }, [refresh])
  return shots
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 20_000)
}

const fileName = (s: Shot) => `${s.node.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30) || 'camera'}-${new Date(s.at).toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15)}.jpg`
const when = (t: number) => new Date(t).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })

/** A picture's address is released when it is no longer shown. */
function useUrls(shots: Shot[]) {
  const [urls, setUrls] = useState<Record<string, string>>({})
  useEffect(() => {
    const next: Record<string, string> = {}
    for (const s of shots) next[s.id] = URL.createObjectURL(s.blob)
    setUrls(next)
    return () => { Object.values(next).forEach(u => URL.revokeObjectURL(u)) }
  }, [shots])
  return urls
}

export function SnapshotGallery({ onUpload }: { onUpload: (files: File[]) => void }) {
  const shots = useShots()
  const urls = useUrls(shots)
  const [open, setOpen] = useState<number | null>(null)
  const [confirmClear, setConfirmClear] = useState(false)
  const toFile = (s: Shot) => new File([s.blob], fileName(s), { type: 'image/jpeg' })

  useEffect(() => {
    if (open === null) return
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(null)
      else if (e.key === 'ArrowLeft') setOpen(i => (i === null ? i : Math.max(0, i - 1)))
      else if (e.key === 'ArrowRight') setOpen(i => (i === null ? i : Math.min(shots.length - 1, i + 1)))
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [open, shots.length])

  if (shots.length === 0) return null
  const current = open !== null ? shots[open] : null

  return (
    <section className="cam-gallery" aria-label="Saved snapshots">
      <div className="cam-gallery-head">
        <h3 className="section">Snapshots <span className="muted small">({shots.length})</span></h3>
        <span className="spacer" />
        <button className="nm-btn sm" onClick={() => onUpload(shots.map(toFile))}><UploadIcon /> Send all to phone</button>
        {confirmClear
          ? <button className="nm-btn sm danger" onClick={() => { void clearShots(); setConfirmClear(false) }}>Delete all?</button>
          : <button className="nm-btn sm" onClick={() => setConfirmClear(true)} onBlur={() => setConfirmClear(false)}><TrashIcon /> Clear</button>}
      </div>
      <ul className="cam-shots">
        {shots.map((s, i) => (
          <li key={s.id}>
            <button className="cam-shot" onClick={() => setOpen(i)} aria-label={`Open snapshot from ${s.node}, ${when(s.at)}`}>
              {urls[s.id] && <img src={urls[s.id]} alt="" loading="lazy" />}
              <span className="cam-shot-meta ellipsis">{s.kind === 'motion' ? 'Motion · ' : ''}{s.node}</span>
            </button>
          </li>
        ))}
      </ul>
      <p className="muted small">Snapshots are kept in this browser only until you send or delete them.</p>

      {current && urls[current.id] && (
        <div className="cam-lightbox" role="dialog" aria-modal="true" aria-label="Snapshot" onClick={() => setOpen(null)}>
          <div className="cam-lightbox-box" onClick={e => e.stopPropagation()}>
            <img src={urls[current.id]} alt={`Snapshot from ${current.node}`} />
            <div className="cam-lightbox-bar">
              <div className="cam-lightbox-title"><strong className="ellipsis">{current.node}</strong><span className="small muted">{when(current.at)}{current.kind === 'motion' ? ' · motion' : ''}</span></div>
              <span className="spacer" />
              <button className="icon-btn" disabled={open === 0} onClick={() => setOpen(i => (i === null ? i : i - 1))} aria-label="Previous"><ArrowLeftIcon /></button>
              <button className="icon-btn" disabled={open === shots.length - 1} onClick={() => setOpen(i => (i === null ? i : i + 1))} aria-label="Next"><ArrowRightIcon /></button>
              <button className="nm-btn sm" onClick={() => saveBlob(current.blob, fileName(current))}><DownloadIcon /> Save</button>
              <button className="nm-btn sm" onClick={() => { onUpload([toFile(current)]); setOpen(null) }}><UploadIcon /> Send to phone</button>
              <button className="nm-btn sm" onClick={() => { void removeShot(current.id); setOpen(null) }}><TrashIcon /> Delete</button>
              <button className="icon-btn" onClick={() => setOpen(null)} aria-label="Close"><CloseIcon /></button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

/* ---------------------------------------------------------------------------------------------- */
/* Motion detection (runs in the viewer, on the live picture)                                      */
/* ---------------------------------------------------------------------------------------------- */

export type Sensitivity = 'low' | 'medium' | 'high'
const THRESHOLD: Record<Sensitivity, number> = { low: 0.08, medium: 0.035, high: 0.012 }
const COOLDOWN_MS = 20_000

/**
 * Compares small grey copies of the picture twice a second. When enough of it changes for two checks in a row,
 * [onMotion] is called, at most once every 20 seconds.
 */
export function useMotion(getVideo: () => HTMLVideoElement | null, enabled: boolean, sensitivity: Sensitivity, onMotion: () => void) {
  const cb = useRef(onMotion)
  cb.current = onMotion
  const get = useRef(getVideo)
  get.current = getVideo
  useEffect(() => {
    if (!enabled) return
    const canvas = document.createElement('canvas')
    canvas.width = 64
    canvas.height = 36
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) return
    let prev: Uint8ClampedArray | null = null
    let hits = 0
    let last = 0
    const timer = window.setInterval(() => {
      const v = get.current()
      if (!v || !v.videoWidth || v.readyState < 2) { prev = null; return }
      try { ctx.drawImage(v, 0, 0, 64, 36) } catch { return }
      const data = ctx.getImageData(0, 0, 64, 36).data
      const grey = new Uint8ClampedArray(64 * 36)
      for (let i = 0, p = 0; i < data.length; i += 4, p++) grey[p] = (data[i] * 299 + data[i + 1] * 587 + data[i + 2] * 114) / 1000
      if (prev) {
        let changed = 0
        for (let p = 0; p < grey.length; p++) if (Math.abs(grey[p] - prev[p]) > 28) changed++
        const moving = changed / grey.length > THRESHOLD[sensitivity]
        hits = moving ? hits + 1 : 0
        const now = Date.now()
        if (hits >= 2 && now - last > COOLDOWN_MS) { last = now; hits = 0; cb.current() }
      }
      prev = grey
    }, 500)
    return () => window.clearInterval(timer)
  }, [enabled, sensitivity])
}

/** A short two-note beep, so an alert is noticed even when the page is in another window. */
export function beep() {
  try {
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    const ctx = new AC()
    const play = (freq: number, at: number) => {
      const o = ctx.createOscillator()
      const g = ctx.createGain()
      o.frequency.value = freq
      g.gain.setValueAtTime(0.0001, ctx.currentTime + at)
      g.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + at + 0.02)
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.18)
      o.connect(g).connect(ctx.destination)
      o.start(ctx.currentTime + at)
      o.stop(ctx.currentTime + at + 0.2)
    }
    play(880, 0)
    play(1175, 0.2)
    window.setTimeout(() => void ctx.close(), 700)
  } catch { /* silent */ }
}

/** Shows a system notification when the page is hidden, if the person allowed notifications. */
export function systemNotice(title: string, body: string) {
  try {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted' && document.hidden) new Notification(title, { body, tag: 'droplink-motion' })
  } catch { /* not supported here */ }
}

export async function askNotifications() {
  try { if (typeof Notification !== 'undefined' && Notification.permission === 'default') await Notification.requestPermission() } catch { /* ignore */ }
}

/* ---------------------------------------------------------------------------------------------- */
/* "Last seen" pictures                                                                            */
/* ---------------------------------------------------------------------------------------------- */

const THUMB_PREFIX = 'dl.cam.thumb.'
const THUMB_MAX = 12

export function saveThumb(addr: string, video: HTMLVideoElement) {
  try {
    if (!video.videoWidth) return
    const canvas = document.createElement('canvas')
    canvas.width = 224
    canvas.height = Math.max(1, Math.round(224 * video.videoHeight / video.videoWidth))
    canvas.getContext('2d')?.drawImage(video, 0, 0, canvas.width, canvas.height)
    const url = canvas.toDataURL('image/jpeg', 0.6)
    localStorage.setItem(THUMB_PREFIX + addr, JSON.stringify({ at: Date.now(), url }))
    const keys: { k: string; at: number }[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(THUMB_PREFIX)) {
        try { keys.push({ k, at: JSON.parse(localStorage.getItem(k) ?? '{}').at ?? 0 }) } catch { keys.push({ k, at: 0 }) }
      }
    }
    keys.sort((a, b) => b.at - a.at).slice(THUMB_MAX).forEach(o => localStorage.removeItem(o.k))
  } catch { /* storage full or blocked: the card just has no picture */ }
}

export function readThumb(addr: string): { at: number; url: string } | null {
  try {
    const raw = localStorage.getItem(THUMB_PREFIX + addr)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

/* ---------------------------------------------------------------------------------------------- */
/* Battery                                                                                         */
/* ---------------------------------------------------------------------------------------------- */

type BatteryManagerLike = EventTarget & { level: number; charging: boolean }

export function useBattery(): { level: number; charging: boolean } | null {
  const [state, setState] = useState<{ level: number; charging: boolean } | null>(null)
  useEffect(() => {
    let battery: BatteryManagerLike | null = null
    let gone = false
    const read = () => { if (battery && !gone) setState({ level: battery.level, charging: battery.charging }) }
    const nav = navigator as Navigator & { getBattery?: () => Promise<BatteryManagerLike> }
    nav.getBattery?.().then(b => {
      battery = b
      read()
      b.addEventListener('levelchange', read)
      b.addEventListener('chargingchange', read)
    }).catch(() => undefined)
    return () => {
      gone = true
      battery?.removeEventListener('levelchange', read)
      battery?.removeEventListener('chargingchange', read)
    }
  }, [])
  return state
}

/* ---------------------------------------------------------------------------------------------- */
/* Sound meter (for cameras that share sound only)                                                 */
/* ---------------------------------------------------------------------------------------------- */

export function AudioMeter({ stream, label }: { stream: MediaStream; label: string }) {
  const bar = useRef<HTMLSpanElement | null>(null)
  useEffect(() => {
    let raf = 0
    let ctx: AudioContext | null = null
    try {
      const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      ctx = new AC()
      const src = ctx.createMediaStreamSource(stream)
      const an = ctx.createAnalyser()
      an.fftSize = 256
      src.connect(an)
      const buf = new Uint8Array(an.frequencyBinCount)
      const tick = () => {
        an.getByteTimeDomainData(buf)
        let peak = 0
        for (const v of buf) peak = Math.max(peak, Math.abs(v - 128))
        if (bar.current) bar.current.style.transform = `scaleX(${Math.min(1, peak / 64)})`
        raf = requestAnimationFrame(tick)
      }
      tick()
    } catch { /* the meter stays empty */ }
    return () => { cancelAnimationFrame(raf); void ctx?.close().catch(() => undefined) }
  }, [stream])
  return (
    <div className="cam-audio" role="img" aria-label={label}>
      <MicIcon width={34} height={34} />
      <strong>{label}</strong>
      <span className="cam-meter"><span ref={bar} /></span>
    </div>
  )
}

/* ---------------------------------------------------------------------------------------------- */
/* Kiosk screen: a dark page for a device that only shares                                         */
/* ---------------------------------------------------------------------------------------------- */

export function KioskScreen({ snap, onStop, onExit }: { snap: NodeSnapshot; onStop: () => void; onExit: () => void }) {
  const [controls, setControls] = useState(false)
  const [shift, setShift] = useState(0)
  const timer = useRef(0)

  useEffect(() => {
    try { void document.documentElement.requestFullscreen?.() } catch { /* the page is still dark */ }
    return () => { try { if (document.fullscreenElement) void document.exitFullscreen() } catch { /* already out */ } }
  }, [])
  // The text drifts a little, so a screen that stays on for days does not keep one picture burned in.
  useEffect(() => {
    const t = window.setInterval(() => setShift(s => (s + 1) % 4), 60_000)
    return () => window.clearInterval(t)
  }, [])
  const show = () => {
    setControls(true)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setControls(false), 5_000)
  }
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const text = snap.status === 'live' ? `Sharing · ${snap.viewers} watching`
    : snap.status === 'waiting' ? 'Waiting for the phone’s owner'
      : snap.status === 'reconnecting' ? 'Reconnecting…' : snap.status === 'starting' ? 'Starting…' : 'Not sharing'
  const offsets = [[0, 0], [24, 16], [-24, 32], [16, -24]]
  return (
    <div className="cam-kiosk" onClick={show} role="dialog" aria-label="Kiosk screen">
      <div className="cam-kiosk-text" style={{ transform: `translate(${offsets[shift][0]}px, ${offsets[shift][1]}px)` }}>
        <span className={'cam-kiosk-dot' + (snap.status === 'live' ? ' on' : '')} />
        <span>{text}</span>
        {snap.talking && <span className="cam-kiosk-talk">Someone is talking</span>}
      </div>
      {controls
        ? (
          <div className="cam-kiosk-controls" onClick={e => e.stopPropagation()}>
            <button className="nm-btn sm" onClick={onExit}>Leave kiosk screen</button>
            <button className="nm-btn sm" onClick={onStop}><StopIcon /> Stop sharing</button>
          </div>
        )
        : <span className="cam-kiosk-hint">Tap the screen for controls</span>}
    </div>
  )
}

/* ---------------------------------------------------------------------------------------------- */
/* Banner: sharing stays visible and stoppable on every page                                       */
/* ---------------------------------------------------------------------------------------------- */

export function SharingBanner({ snap, onStop, onOpen }: { snap: NodeSnapshot; onStop: () => void; onOpen: () => void }) {
  const text = snap.status === 'live' ? `Sharing your camera${snap.hasMic && snap.micOn ? ' and sound' : ''} · ${snap.viewers} watching`
    : snap.status === 'waiting' ? 'Waiting for permission to share'
      : snap.status === 'reconnecting' ? 'Sharing paused · reconnecting' : 'Starting to share'
  return (
    <div className="cam-banner" role="status">
      <span className={'cam-banner-dot' + (snap.status === 'live' ? ' on' : '')} aria-hidden="true" />
      <span className="cam-banner-text ellipsis">{text}</span>
      <button className="nm-btn sm" onClick={onOpen}>Open</button>
      <button className="nm-btn sm primary" onClick={onStop}><StopIcon /> Stop</button>
    </div>
  )
}

/* ---------------------------------------------------------------------------------------------- */
/* Screen stays on: fallback when the Wake Lock API is missing or refused                          */
/* ---------------------------------------------------------------------------------------------- */

/**
 * Plays a hidden, silent picture that is redrawn every second. A page that is playing video is not put to sleep by
 * most browsers, which covers the ones that have no Wake Lock API. Returns a function that stops it.
 */
export function startNoSleep(): () => void {
  try {
    const canvas = document.createElement('canvas')
    canvas.width = 16
    canvas.height = 16
    const ctx = canvas.getContext('2d')
    const stream = canvas.captureStream?.(2)
    if (!ctx || !stream) return () => undefined
    let flip = false
    const draw = () => { flip = !flip; ctx.fillStyle = flip ? '#010101' : '#000'; ctx.fillRect(0, 0, 16, 16) }
    draw()
    const timer = window.setInterval(draw, 1000)
    const video = document.createElement('video')
    video.setAttribute('playsinline', '')
    video.muted = true
    video.loop = true
    video.srcObject = stream
    Object.assign(video.style, { position: 'fixed', width: '2px', height: '2px', opacity: '0.01', pointerEvents: 'none', left: '0', top: '0' })
    video.setAttribute('aria-hidden', 'true')
    document.body.appendChild(video)
    const play = () => { void video.play().catch(() => undefined) }
    play()
    // Some browsers only allow playback after a touch; the first one starts it.
    const kick = () => { play(); window.removeEventListener('pointerdown', kick) }
    window.addEventListener('pointerdown', kick)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('pointerdown', kick)
      video.pause()
      video.srcObject = null
      video.remove()
      stream.getTracks().forEach(t => t.stop())
    }
  } catch { return () => undefined }
}
