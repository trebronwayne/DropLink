import { useCallback, useEffect, useRef, useState } from 'react'
import { AuthError, CameraError, CameraFeed, CameraNode, cameraApi } from './api'
import { Aubree } from './Aubree'
import { CameraIcon, CloseIcon, ExpandIcon, MicIcon, MicOffIcon, PlusIcon, RecordIcon, StopIcon, SwitchCameraIcon, VolumeIcon, MuteIcon } from './icons'
import { useToast } from './toast'

/**
 * Camera Nodes. Any paired browser can share its camera and microphone once the phone's owner allows it. Other
 * browsers then watch it live, take snapshots and record moments. The picture and sound travel straight between the
 * two browsers (WebRTC); the phone only approves the node and carries the small set-up messages, so it stays light
 * and needs no camera or microphone permission of its own.
 */

const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

const newId = () => {
  const bytes = new Uint8Array(9)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
}

/** An id that stays the same for this browser tab, so a reload finds its own node again. */
function tabId(name: string): string {
  try {
    let id = sessionStorage.getItem(name)
    if (!id) { id = newId(); sessionStorage.setItem(name, id) }
    return id
  } catch { return newId() }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const parse = (text: string): any => { try { return JSON.parse(text) } catch { return null } }

const stopStream = (stream: MediaStream) => stream.getTracks().forEach(t => t.stop())

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

const stamp = () => new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15)
const slug = (text: string) => text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30) || 'camera'

function agoText(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return 'moments ago'
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} min ago`
  const h = Math.floor(m / 60)
  return h < 24 ? `${h} h ago` : 'over a day ago'
}

/* ------------------------------------------------------------------------------------------------ */
/* Set-up messages: a long poll on the phone                                                         */
/* ------------------------------------------------------------------------------------------------ */

type Incoming = { from: string; session: string; type: string; data: any } // eslint-disable-line @typescript-eslint/no-explicit-any

type SignalHandlers = {
  onMessage: (m: Incoming) => void
  /** True while the phone answers, false while it cannot be reached. */
  onLink: (ok: boolean) => void
  /** The phone does not know this mailbox (it restarted, or the limit was reached). */
  onGone: () => void
  onAuthLost: () => void
}

class SignalLoop {
  private since = 0
  private stopped = false
  private ctrl: AbortController | null = null

  constructor(private role: 'node' | 'view', private client: string, private h: SignalHandlers) {}

  start() { void this.run() }
  stop() { this.stopped = true; this.ctrl?.abort() }

  private async run() {
    let fails = 0
    while (!this.stopped) {
      const ctrl = new AbortController()
      this.ctrl = ctrl
      try {
        const r = await cameraApi.poll(this.role, this.client, this.since, ctrl.signal)
        fails = 0
        this.h.onLink(true)
        for (const m of r.msgs) this.h.onMessage({ from: m.from, session: m.session, type: m.type, data: parse(m.data) })
        this.since = Math.max(this.since, r.last)
      } catch (e) {
        if (this.stopped) return
        if (e instanceof AuthError) { this.h.onAuthLost(); return }
        if (e instanceof CameraError && e.status === 404) { this.h.onGone(); return }
        fails++
        this.h.onLink(false)
        await sleep(Math.min(800 * fails, 5000))
      }
    }
  }
}

/* ------------------------------------------------------------------------------------------------ */
/* The Camera Node: this browser shares its camera and microphone                                    */
/* ------------------------------------------------------------------------------------------------ */

export type Quality = 'low' | 'standard' | 'high'
export type NodeOptions = { facing: 'user' | 'environment' | 'both'; mic: boolean; quality: Quality; name: string }
export type NodeStatus = 'off' | 'starting' | 'waiting' | 'live' | 'reconnecting' | 'denied' | 'blocked' | 'error'
type Facing = 'user' | 'environment'

const QUALITY: Record<Quality, { w: number; h: number; fps: number; bps: number }> = {
  low: { w: 640, h: 360, fps: 15, bps: 600_000 },
  standard: { w: 1280, h: 720, fps: 24, bps: 1_500_000 },
  high: { w: 1920, h: 1080, fps: 30, bps: 3_500_000 }
}
const MAX_VIEWERS = 6

type LocalFeed = { id: string; label: string; stream: MediaStream; facing: Facing | 'unknown' }
export type FeedView = { id: string; label: string; stream: MediaStream }
type NodeSession = { pc: RTCPeerConnection; from: string; id: string; pending: RTCIceCandidateInit[]; hasRemote: boolean }

export type NodeSnapshot = {
  status: NodeStatus
  /** A short code the page turns into words, see ERRORS. */
  error: string
  note: string
  feeds: FeedView[]
  hasMic: boolean
  micOn: boolean
  viewers: number
  canFlip: boolean
}

const EMPTY: NodeSnapshot = { status: 'off', error: '', note: '', feeds: [], hasMic: false, micOn: false, viewers: 0, canFlip: false }

const labelFor = (facing: Facing | 'unknown') => (facing === 'user' ? 'Front' : facing === 'environment' ? 'Back' : 'Camera')

const videoConstraints = (facing: Facing, q: { w: number; h: number; fps: number }): MediaTrackConstraints => ({
  facingMode: { ideal: facing }, width: { ideal: q.w }, height: { ideal: q.h }, frameRate: { ideal: q.fps }
})

function describe(e: unknown): string {
  const name = e instanceof DOMException ? e.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'browser_denied'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'no_camera'
  if (name === 'NotReadableError' || name === 'AbortError') return 'camera_busy'
  return 'failed'
}

async function capture(o: NodeOptions): Promise<{ feeds: LocalFeed[]; hasMic: boolean; note: string }> {
  const q = QUALITY[o.quality]
  const first: Facing = o.facing === 'both' ? 'environment' : o.facing
  let note = ''
  let main: MediaStream
  try {
    main = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(first, q), audio: o.mic })
  } catch (e) {
    if (!o.mic) throw e
    // The camera may work even if the microphone is not available.
    main = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(first, q), audio: false })
    note = 'The microphone is not available, so this node shares video only.'
  }
  const settings = main.getVideoTracks()[0]?.getSettings()
  const seen = settings?.facingMode === 'user' || settings?.facingMode === 'environment' ? settings.facingMode : o.facing === 'both' ? 'unknown' : first
  const feeds: LocalFeed[] = [{ id: main.id, label: labelFor(seen), stream: main, facing: seen }]
  if (o.facing === 'both') {
    const other: Facing = seen === 'user' ? 'environment' : 'user'
    try {
      const second = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(other, q), audio: false })
      await sleep(400)
      // Many phones stop the first camera when the second one opens: they can use only one at a time.
      if (main.getVideoTracks().some(t => t.readyState === 'ended')) {
        stopStream(second)
        main = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(seen === 'unknown' ? first : seen, q), audio: false })
        feeds[0] = { ...feeds[0], stream: main, id: main.id }
        note = (note ? note + ' ' : '') + 'This device can use only one camera at a time, so it shares one. You can switch between them.'
      } else {
        feeds.push({ id: second.id, label: labelFor(other), stream: second, facing: other })
      }
    } catch {
      note = (note ? note + ' ' : '') + 'This device could not open both cameras at once, so it shares one.'
    }
  }
  const hasMic = main.getAudioTracks().length > 0
  return { feeds, hasMic, note }
}

class CameraNodeController {
  private client = tabId('dl.cam.node')
  private snap: NodeSnapshot = EMPTY
  private opts: NodeOptions | null = null
  private feeds: LocalFeed[] = []
  private sessions = new Map<string, NodeSession>()
  private signal: SignalLoop | null = null
  private running = false
  private runId = 0
  private wake: WakeLockSentinel | null = null

  constructor(private publish: (s: NodeSnapshot) => void, private authLost: () => void) {}

  get status() { return this.snap.status }

  private set(p: Partial<NodeSnapshot>) {
    this.snap = { ...this.snap, ...p }
    this.publish(this.snap)
  }

  private meta(): CameraFeed[] { return this.feeds.map(f => ({ id: f.id, label: f.label })) }
  private micState() { return this.snap.hasMic && this.snap.micOn }
  private view(): FeedView[] { return this.feeds.map(f => ({ id: f.id, label: f.label, stream: f.stream })) }

  async start(o: NodeOptions) {
    if (this.running) return
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) { this.set({ ...EMPTY, status: 'error', error: 'insecure' }); return }
    this.running = true
    const run = ++this.runId
    this.opts = o
    this.set({ ...EMPTY, status: 'starting' })
    try {
      const got = await capture(o)
      if (run !== this.runId) { got.feeds.forEach(f => stopStream(f.stream)); return }
      this.feeds = got.feeds
      let cams = 1
      try { cams = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'videoinput').length } catch { /* keep 1 */ }
      this.set({ feeds: this.view(), hasMic: got.hasMic, micOn: got.hasMic, note: got.note, canFlip: got.feeds.length === 1 && cams > 1 })
    } catch (e) {
      if (run === this.runId) { this.running = false; this.set({ ...EMPTY, status: 'error', error: describe(e) }) }
      return
    }
    void this.keepAwake()
    void this.register(run)
  }

  /** Asks the phone until the owner answers. Safe to repeat: the phone treats it as the same request. */
  private async register(run: number) {
    for (;;) {
      if (run !== this.runId) return
      try {
        const r = await cameraApi.start({ client: this.client, name: this.opts?.name ?? '', feeds: this.meta(), mic: this.micState() })
        if (run !== this.runId) return
        if (r.status === 'approved') { this.set({ status: 'live' }); this.listen(run); return }
        if (r.status === 'denied') { await this.end('denied'); return }
        this.set({ status: 'waiting' })
      } catch (e) {
        if (run !== this.runId) return
        if (e instanceof AuthError) { this.authLost(); await this.stop(); return }
        if (e instanceof CameraError && e.code === 'camera_blocked') { await this.end('blocked'); return }
        if (e instanceof CameraError && e.code === 'too_many_requests') { await this.end('error', 'too_many'); return }
        this.set({ status: this.snap.status === 'waiting' ? 'waiting' : 'reconnecting' })
      }
      await sleep(2000)
    }
  }

  private listen(run: number) {
    this.signal?.stop()
    const loop = new SignalLoop('node', this.client, {
      onMessage: m => { void this.onMessage(m) },
      onLink: ok => {
        if (run !== this.runId) return
        if (ok && this.snap.status === 'reconnecting') this.set({ status: 'live' })
        if (!ok && this.snap.status === 'live') this.set({ status: 'reconnecting' })
      },
      // The phone no longer knows this node (it restarted). Register again; the owner may be asked again.
      onGone: () => {
        if (run !== this.runId) return
        this.signal = null
        void this.closeSessions(false)
        this.set({ status: 'reconnecting', viewers: 0 })
        void this.register(run)
      },
      onAuthLost: () => { this.authLost(); void this.stop() }
    })
    this.signal = loop
    loop.start()
  }

  private async onMessage(m: Incoming) {
    const key = m.from + '|' + m.session
    if (m.type === 'join') { await this.join(key, m); return }
    const s = this.sessions.get(key)
    if (!s) return
    if (m.type === 'answer') {
      try {
        await s.pc.setRemoteDescription(m.data as RTCSessionDescriptionInit)
        s.hasRemote = true
        for (const c of s.pending.splice(0)) await s.pc.addIceCandidate(c).catch(() => undefined)
      } catch { this.drop(key, true) }
    } else if (m.type === 'ice') {
      const c = m.data as RTCIceCandidateInit | null
      if (!c) return
      if (s.hasRemote) await s.pc.addIceCandidate(c).catch(() => undefined)
      else s.pending.push(c)
    } else if (m.type === 'bye') {
      this.drop(key, false)
    }
  }

  private async say(to: string, session: string, type: string, data?: unknown) {
    try { await cameraApi.send({ role: 'node', client: this.client, to, session, type, data }) } catch { /* the viewer will retry or give up */ }
  }

  private async join(key: string, m: Incoming) {
    if (this.sessions.has(key)) this.drop(key, false)
    if (this.sessions.size >= MAX_VIEWERS) { void this.say(m.from, m.session, 'busy'); return }
    const pc = new RTCPeerConnection({ iceServers: [] })
    this.sessions.set(key, { pc, from: m.from, id: m.session, pending: [], hasRemote: false })
    for (const f of this.feeds) for (const t of f.stream.getTracks()) pc.addTrack(t, f.stream)
    pc.onicecandidate = e => { if (e.candidate) void this.say(m.from, m.session, 'ice', e.candidate.toJSON()) }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected') this.countViewers()
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.drop(key, false)
    }
    try {
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      await this.say(m.from, m.session, 'offer', { type: offer.type, sdp: offer.sdp })
      this.limitBitrate(pc)
    } catch { this.drop(key, true) }
  }

  private limitBitrate(pc: RTCPeerConnection) {
    const bps = QUALITY[this.opts?.quality ?? 'standard'].bps
    for (const sender of pc.getSenders()) {
      if (sender.track?.kind !== 'video') continue
      try {
        const p = sender.getParameters()
        if (!p.encodings || p.encodings.length === 0) p.encodings = [{}]
        p.encodings[0].maxBitrate = bps
        sender.setParameters(p).catch(() => undefined)
      } catch { /* the browser keeps its own limit */ }
    }
  }

  private drop(key: string, notify: boolean) {
    const s = this.sessions.get(key)
    if (!s) return
    this.sessions.delete(key)
    s.pc.onconnectionstatechange = null
    s.pc.onicecandidate = null
    if (notify) void this.say(s.from, s.id, 'bye')
    s.pc.close()
    this.countViewers()
  }

  private async closeSessions(notify: boolean) {
    const all = [...this.sessions.entries()]
    this.sessions.clear()
    await Promise.all(all.map(async ([, s]) => {
      s.pc.onconnectionstatechange = null
      s.pc.onicecandidate = null
      if (notify) await this.say(s.from, s.id, 'bye')
      s.pc.close()
    }))
  }

  private countViewers() {
    const n = [...this.sessions.values()].filter(s => s.pc.connectionState === 'connected').length
    if (n === this.snap.viewers) return
    this.set({ viewers: n })
    this.push()
  }

  /** Tells the phone what this node shows now, so the other devices' lists stay right. */
  private push() {
    if (!this.running || this.snap.status === 'waiting') return
    cameraApi.update({ client: this.client, feeds: this.meta(), mic: this.micState(), viewers: this.snap.viewers }).catch(() => undefined)
  }

  setMic(on: boolean) {
    for (const f of this.feeds) f.stream.getAudioTracks().forEach(t => { t.enabled = on })
    this.set({ micOn: on })
    this.push()
  }

  /** Swaps front and back without ending any viewer's connection. */
  async flip() {
    const f = this.feeds[0]
    if (!f || this.feeds.length !== 1) return
    const prev: Facing = f.facing === 'user' ? 'user' : 'environment'
    const next: Facing = prev === 'user' ? 'environment' : 'user'
    const q = QUALITY[this.opts?.quality ?? 'standard']
    const old = f.stream.getVideoTracks()[0]
    // Many phones cannot open a second camera while the first is open, so the first is released first.
    old?.stop()
    let facing: Facing = next
    let fresh: MediaStream
    try {
      fresh = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(next, q), audio: false })
    } catch {
      try {
        facing = prev
        fresh = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(prev, q), audio: false })
        this.set({ note: 'This device could not switch cameras.' })
      } catch {
        await this.end('error', 'failed')
        return
      }
    }
    const track = fresh.getVideoTracks()[0]
    for (const s of this.sessions.values()) {
      for (const sender of s.pc.getSenders()) if (sender.track === old) await sender.replaceTrack(track).catch(() => undefined)
    }
    if (old) f.stream.removeTrack(old)
    f.stream.addTrack(track)
    f.facing = facing
    f.label = labelFor(facing)
    this.set({ feeds: this.view(), note: '' })
    this.push()
  }

  private async keepAwake() {
    try {
      this.wake = (await navigator.wakeLock?.request('screen')) ?? null
      this.wake?.addEventListener('release', () => { this.wake = null })
    } catch { this.wake = null }
  }

  /** The screen lock is released when the tab is hidden; take it again when the person comes back. */
  rewake() { if (this.running && !this.wake) void this.keepAwake() }

  /** Called when the page is closing: the phone is told right away instead of after a timeout. */
  beacon() {
    if (!this.running) return
    try { navigator.sendBeacon('/api/camera/node/stop', new Blob([JSON.stringify({ client: this.client })], { type: 'application/json' })) } catch { /* the phone notices by itself */ }
  }

  private async end(status: NodeStatus, error = '') {
    this.runId++
    this.running = false
    this.signal?.stop()
    this.signal = null
    await this.closeSessions(true)
    this.feeds.forEach(f => stopStream(f.stream))
    this.feeds = []
    try { await this.wake?.release() } catch { /* already released */ }
    this.wake = null
    this.set({ ...EMPTY, status, error })
  }

  async stop() {
    const was = this.running
    await this.end('off')
    if (was) { try { await cameraApi.stop(this.client) } catch { /* the phone notices by itself */ } }
  }
}

/** The Camera Node of this browser. Lives as long as the app, so leaving the Cameras page does not stop it. */
export function useCameraNode(onAuthLost: () => void) {
  const lost = useRef(onAuthLost)
  lost.current = onAuthLost
  const [snap, setSnap] = useState<NodeSnapshot>(EMPTY)
  const ctl = useRef<CameraNodeController | null>(null)
  if (!ctl.current) ctl.current = new CameraNodeController(setSnap, () => lost.current())
  useEffect(() => {
    const c = ctl.current
    const hide = () => c?.beacon()
    const show = () => { if (!document.hidden) c?.rewake() }
    window.addEventListener('pagehide', hide)
    document.addEventListener('visibilitychange', show)
    return () => { window.removeEventListener('pagehide', hide); document.removeEventListener('visibilitychange', show) }
  }, [])
  const start = useCallback((o: NodeOptions) => ctl.current?.start(o), [])
  const stop = useCallback(() => ctl.current?.stop(), [])
  const setMic = useCallback((on: boolean) => ctl.current?.setMic(on), [])
  const flip = useCallback(() => ctl.current?.flip(), [])
  return { snap, start, stop, setMic, flip }
}
export type CameraNodeApi = ReturnType<typeof useCameraNode>

/* ------------------------------------------------------------------------------------------------ */
/* The list of nodes                                                                                 */
/* ------------------------------------------------------------------------------------------------ */

export type NodeView = CameraNode & { ageMs: number }

/** Polls the phone for the nodes it knows about. Often while the Cameras page is open, rarely elsewhere. */
export function useCameraNodes(enabled: boolean, fast: boolean, onAuthLost: () => void) {
  const [nodes, setNodes] = useState<NodeView[]>([])
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const lost = useRef(onAuthLost)
  lost.current = onAuthLost
  const refresh = useCallback(async () => {
    try {
      const r = await cameraApi.nodes()
      setNodes(r.nodes.map(n => ({ ...n, ageMs: r.now - n.lastSeen })))
      setLoaded(true)
      setFailed(false)
    } catch (e) {
      if (e instanceof AuthError) lost.current()
      else setFailed(true)
    }
  }, [])
  useEffect(() => {
    if (!enabled) return
    let stop = false
    let timer = 0
    const loop = async () => {
      await refresh()
      if (!stop) timer = window.setTimeout(loop, document.hidden ? 15_000 : fast ? 3_000 : 8_000)
    }
    void loop()
    const onVisible = () => { if (!document.hidden) { window.clearTimeout(timer); void loop() } }
    document.addEventListener('visibilitychange', onVisible)
    return () => { stop = true; window.clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [enabled, fast, refresh])
  const online = nodes.filter(n => n.online && !n.mine).length
  return { nodes, loaded, failed, online, refresh }
}

/* ------------------------------------------------------------------------------------------------ */
/* Watching                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

/** One browser tab's mailbox on the phone, shared by every picture it watches. */
class ViewerClient {
  readonly client = tabId('dl.cam.view')
  private handlers = new Map<string, (m: Incoming) => void>()
  private signal: SignalLoop

  constructor(onAuthLost: () => void, onLink: (ok: boolean) => void) {
    this.signal = new SignalLoop('view', this.client, {
      onMessage: m => this.handlers.get(m.session)?.(m),
      onLink,
      onGone: () => onLink(false),
      onAuthLost
    })
    this.signal.start()
  }

  attach(session: string, h: (m: Incoming) => void) { this.handlers.set(session, h) }
  detach(session: string) { this.handlers.delete(session) }
  send(to: string, session: string, type: string, data?: unknown) {
    return cameraApi.send({ role: 'view', client: this.client, to, session, type, data })
  }
  close() { this.signal.stop() }
}

type Phase = 'connecting' | 'live' | 'offline' | 'busy' | 'failed'

const PHASE_TEXT: Record<Phase, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  offline: 'This camera is offline',
  busy: 'This camera has too many viewers. Trying again…',
  failed: 'Lost the connection. Trying again…'
}

function Video({ stream, muted, innerRef }: { stream: MediaStream; muted: boolean; innerRef: (el: HTMLVideoElement | null) => void }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => {
    const el = ref.current
    if (el && el.srcObject !== stream) { el.srcObject = stream; el.play().catch(() => undefined) }
  }, [stream])
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.muted = muted
    if (!muted) el.play().catch(() => undefined)
  }, [muted])
  return <video ref={el => { ref.current = el; innerRef(el) }} className="cam-video" autoPlay playsInline muted />
}

function pickRecorderType(): string {
  if (typeof MediaRecorder === 'undefined') return ''
  return ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4'].find(t => MediaRecorder.isTypeSupported(t)) ?? ''
}

const MAX_RECORD_SECONDS = 600

function Tile({ node, hub, onClose }: { node: NodeView; hub: ViewerClient; onClose: () => void }) {
  const toast = useToast()
  const [phase, setPhase] = useState<Phase>('connecting')
  const [streams, setStreams] = useState<MediaStream[]>([])
  const [listening, setListening] = useState(false)
  const [active, setActive] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [recSecs, setRecSecs] = useState<number | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const chunks = useRef<Blob[]>([])
  const videos = useRef<(HTMLVideoElement | null)[]>([])
  const box = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!node.online) { setPhase('offline'); setStreams([]); return }
    setPhase('connecting')
    setStreams([])
    const session = newId()
    const pc = new RTCPeerConnection({ iceServers: [] })
    const queue: RTCIceCandidateInit[] = []
    let remote = false
    let closed = false
    let lossTimer = 0
    const fail = (p: Phase) => { if (!closed) setPhase(p) }

    pc.ontrack = e => {
      const st = e.streams[0]
      if (st) setStreams(cur => (cur.includes(st) ? cur : [...cur, st]))
    }
    pc.onicecandidate = e => { if (e.candidate) hub.send(node.addr, session, 'ice', e.candidate.toJSON()).catch(() => undefined) }
    pc.onconnectionstatechange = () => {
      window.clearTimeout(lossTimer)
      const s = pc.connectionState
      if (s === 'connected') fail('live')
      else if (s === 'disconnected') lossTimer = window.setTimeout(() => fail('failed'), 8_000)
      else if (s === 'failed') fail('failed')
    }
    hub.attach(session, async m => {
      try {
        if (m.type === 'offer') {
          await pc.setRemoteDescription(m.data as RTCSessionDescriptionInit)
          remote = true
          for (const c of queue.splice(0)) await pc.addIceCandidate(c).catch(() => undefined)
          const answer = await pc.createAnswer()
          await pc.setLocalDescription(answer)
          await hub.send(node.addr, session, 'answer', { type: answer.type, sdp: answer.sdp })
        } else if (m.type === 'ice') {
          const c = m.data as RTCIceCandidateInit | null
          if (c) { if (remote) await pc.addIceCandidate(c).catch(() => undefined); else queue.push(c) }
        } else if (m.type === 'busy') fail('busy')
        else if (m.type === 'bye') fail('failed')
      } catch { fail('failed') }
    })

    // Ask the node to start sending. The viewer's own mailbox may still be opening, so a few tries are allowed.
    let tries = 0
    const join = async () => {
      for (;;) {
        try { await hub.send(node.addr, session, 'join'); return } catch (e) {
          if (closed) return
          if (e instanceof CameraError && e.code === 'not_started' && tries++ < 6) { await sleep(500); continue }
          fail(e instanceof CameraError && (e.status === 409 || e.status === 404) ? 'offline' : 'failed')
          return
        }
      }
    }
    void join()
    const giveUp = window.setTimeout(() => { if (pc.connectionState !== 'connected') fail('failed') }, 20_000)

    return () => {
      closed = true
      window.clearTimeout(lossTimer)
      window.clearTimeout(giveUp)
      hub.detach(session)
      hub.send(node.addr, session, 'bye').catch(() => undefined)
      pc.onconnectionstatechange = null
      pc.close()
    }
  }, [node.addr, node.online, attempt, hub])

  // A picture that dropped comes back by itself.
  useEffect(() => {
    if (phase !== 'failed' && phase !== 'busy' && phase !== 'offline') return
    if (!node.online && phase === 'offline') return
    const t = window.setTimeout(() => setAttempt(a => a + 1), phase === 'busy' ? 10_000 : 4_000)
    return () => window.clearTimeout(t)
  }, [phase, node.online])

  const stopRecording = useCallback(() => {
    const r = recorder.current
    if (r && r.state === 'recording') r.stop()
  }, [])

  // Whatever was recorded is saved when the picture goes away or the tile closes.
  useEffect(() => { if (phase !== 'live') stopRecording() }, [phase, stopRecording])
  useEffect(() => stopRecording, [stopRecording])

  useEffect(() => {
    if (recSecs === null) return
    const t = window.setInterval(() => setRecSecs(s => {
      if (s === null) return s
      if (s + 1 >= MAX_RECORD_SECONDS) stopRecording()
      return s + 1
    }), 1_000)
    return () => window.clearInterval(t)
  }, [recSecs === null, stopRecording]) // eslint-disable-line react-hooks/exhaustive-deps

  const audioStream = streams.find(s => s.getAudioTracks().length > 0)

  const snapshot = () => {
    const el = videos.current[active]
    if (!el || !el.videoWidth) { toast.show('There is no picture to capture yet.', 'error'); return }
    const canvas = document.createElement('canvas')
    canvas.width = el.videoWidth
    canvas.height = el.videoHeight
    canvas.getContext('2d')?.drawImage(el, 0, 0)
    canvas.toBlob(b => {
      if (!b) { toast.show('Could not save the snapshot.', 'error'); return }
      saveBlob(b, `${slug(node.name)}-${stamp()}.jpg`)
      toast.show('Snapshot saved')
    }, 'image/jpeg', 0.92)
  }

  const startRecording = () => {
    const stream = streams[active]
    if (!stream || typeof MediaRecorder === 'undefined') { toast.show('This browser cannot record video.', 'error'); return }
    const type = pickRecorderType()
    let r: MediaRecorder
    try { r = new MediaRecorder(stream, type ? { mimeType: type } : undefined) } catch { toast.show('This browser cannot record this picture.', 'error'); return }
    chunks.current = []
    r.ondataavailable = e => { if (e.data.size > 0) chunks.current.push(e.data) }
    r.onstop = () => {
      const mime = r.mimeType || type || 'video/webm'
      const blob = new Blob(chunks.current, { type: mime })
      chunks.current = []
      setRecSecs(null)
      if (blob.size > 0) { saveBlob(blob, `${slug(node.name)}-${stamp()}.${mime.includes('mp4') ? 'mp4' : 'webm'}`); toast.show('Recording saved') }
    }
    r.start(1_000)
    recorder.current = r
    setRecSecs(0)
  }

  const fullscreen = () => {
    const el = box.current
    if (!el) return
    if (document.fullscreenElement) void document.exitFullscreen()
    else void el.requestFullscreen?.()
  }

  const hasAudio = !!audioStream || node.mic
  const recording = recSecs !== null
  const mm = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`

  return (
    <section className="cam-tile nm-raised" ref={box} aria-label={`Camera ${node.name}`}>
      <header className="cam-tile-head">
        <strong className="ellipsis">{node.name}</strong>
        <span className={'cam-chip ' + (phase === 'live' ? 'on' : phase === 'offline' ? 'off' : '')}>
          {phase === 'live' && <span className="dot" />}
          {phase === 'live' ? 'Live' : phase === 'offline' ? 'Offline' : 'Connecting'}
        </span>
        <span className="spacer" />
        <button className="icon-btn" onClick={onClose} aria-label={`Stop watching ${node.name}`}><CloseIcon /></button>
      </header>

      <div className={'cam-stage' + (streams.length > 1 ? ' two' : '')}>
        {streams.map((st, i) => (
          <div key={st.id} className={'cam-feed' + (streams.length > 1 && i === active ? ' active' : '')} onClick={() => setActive(i)}>
            <Video stream={st} muted={!listening || st !== audioStream} innerRef={el => { videos.current[i] = el }} />
            {streams.length > 1 && <span className="cam-feed-label">{node.feeds.find(f => f.id === st.id)?.label ?? `Camera ${i + 1}`}</span>}
          </div>
        ))}
        {phase !== 'live' && (
          <div className="cam-overlay" role="status">
            {phase === 'offline' ? <Aubree state="offline" size={72} /> : <Aubree state="wait" size={72} />}
            <span>{PHASE_TEXT[phase]}</span>
            {phase === 'offline' && <span className="small muted">It will appear here by itself when it is back.</span>}
          </div>
        )}
        {phase === 'live' && recording && <span className="cam-rec"><RecordIcon width={12} height={12} /> {mm(recSecs)}</span>}
      </div>

      <div className="cam-bar">
        <button className={'nm-btn sm' + (listening ? ' primary' : '')} onClick={() => setListening(v => !v)} disabled={!hasAudio} aria-pressed={listening}
          title={hasAudio ? '' : 'This camera has no microphone on'}>
          {listening ? <VolumeIcon /> : <MuteIcon />} {listening ? 'Listening' : hasAudio ? 'Listen' : 'No sound'}
        </button>
        <button className="nm-btn sm" onClick={snapshot} disabled={phase !== 'live'}><CameraIcon /> Snapshot</button>
        {recording
          ? <button className="nm-btn sm primary" onClick={stopRecording}><StopIcon /> Stop {mm(recSecs)}</button>
          : <button className="nm-btn sm" onClick={startRecording} disabled={phase !== 'live'}><RecordIcon /> Record</button>}
        <button className="icon-btn" onClick={fullscreen} aria-label="Full screen"><ExpandIcon /></button>
      </div>
      {streams.length > 1 && <p className="small muted cam-hint">Tap a picture to choose it for snapshots and recordings.</p>}
    </section>
  )
}

function NodeCard({ node, watching, onToggle }: { node: NodeView; watching: boolean; onToggle: () => void }) {
  const cams = node.feeds.length
  return (
    <li className="cam-node nm-raised">
      <span className="cam-node-icon"><CameraIcon /></span>
      <div className="cam-node-text">
        <strong className="ellipsis">{node.name}{node.mine ? ' (this device)' : ''}</strong>
        <span className="small muted ellipsis">
          {[node.model, cams > 1 ? `${cams} cameras` : '', node.mic ? 'microphone on' : 'no sound', node.online && node.viewers > 0 ? `${node.viewers} watching` : '']
            .filter(Boolean).join(' · ')}
        </span>
        <span className={'cam-chip small ' + (node.online ? 'on' : 'off')}>
          {node.online ? <><span className="dot" /> Online</> : `Offline · seen ${agoText(node.ageMs)}`}
        </span>
      </div>
      <button className={'nm-btn sm' + (watching ? '' : ' primary')} onClick={onToggle} disabled={node.mine || (!node.online && !watching)}>
        {watching ? 'Stop watching' : 'Watch'}
      </button>
    </li>
  )
}

/* ------------------------------------------------------------------------------------------------ */
/* Sharing this device's camera                                                                      */
/* ------------------------------------------------------------------------------------------------ */

const ERRORS: Record<string, string> = {
  insecure: 'Camera access needs a secure connection. Open DropLink with https:// (HTTPS), then try again.',
  browser_denied: 'The browser blocked the camera or microphone. Allow them for this site in the browser settings, then try again.',
  no_camera: 'No camera was found on this device.',
  camera_busy: 'The camera is in use by another app. Close it and try again.',
  too_many: 'Too many requests. Wait a minute and try again.',
  failed: 'The camera could not be started.'
}

function Preview({ stream }: { stream: MediaStream }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => { const el = ref.current; if (el && el.srcObject !== stream) { el.srcObject = stream; el.play().catch(() => undefined) } }, [stream])
  return <video ref={ref} className="cam-video" autoPlay playsInline muted />
}

function SharePanel({ node }: { node: CameraNodeApi }) {
  const { snap } = node
  const [facing, setFacing] = useState<NodeOptions['facing']>(() => (localStorage.getItem('dl.cam.facing') as NodeOptions['facing']) || 'environment')
  const [mic, setMic] = useState(() => localStorage.getItem('dl.cam.mic') !== '0')
  const [quality, setQuality] = useState<Quality>(() => (localStorage.getItem('dl.cam.quality') as Quality) || 'standard')
  const [name, setName] = useState(() => localStorage.getItem('dl.cam.name') ?? '')
  const secure = window.isSecureContext && !!navigator.mediaDevices?.getUserMedia

  const begin = () => {
    try {
      localStorage.setItem('dl.cam.facing', facing); localStorage.setItem('dl.cam.mic', mic ? '1' : '0')
      localStorage.setItem('dl.cam.quality', quality); localStorage.setItem('dl.cam.name', name.trim())
    } catch { /* settings just will not be remembered */ }
    void node.start({ facing, mic, quality, name: name.trim() })
  }

  const active = snap.status === 'starting' || snap.status === 'waiting' || snap.status === 'live' || snap.status === 'reconnecting'

  if (!active) {
    const failed = snap.status === 'denied' || snap.status === 'blocked' || snap.status === 'error'
    return (
      <div className="cam-share card nm-raised">
        {failed && (
          <div className="cam-result" role="alert">
            <Aubree state="sad" size={84} />
            <p>
              {snap.status === 'denied' ? 'The phone’s owner said no.'
                : snap.status === 'blocked' ? 'This device is not allowed to share its camera. The phone’s owner can change that in DropLink on the phone.'
                  : ERRORS[snap.error] ?? ERRORS.failed}
            </p>
          </div>
        )}
        <h3>Share this device’s camera</h3>
        <p className="muted small">
          Turn this device into a Camera Node. The phone’s owner is asked first. The video and sound go straight to the devices that watch, not through the phone.
        </p>
        {!secure && <p className="cam-warn" role="alert">{ERRORS.insecure}</p>}
        <label className="field-label" htmlFor="cam-name">Name <span className="muted">(for example “Front door”)</span></label>
        <input id="cam-name" className="field" value={name} maxLength={40} onChange={e => setName(e.target.value)} placeholder="Uses this device’s name if left empty" />
        <div className="field-label">Camera</div>
        <div className="segmented wide" role="radiogroup" aria-label="Camera">
          {([['environment', 'Back'], ['user', 'Front'], ['both', 'Both']] as const).map(([v, l]) => (
            <button key={v} role="radio" aria-checked={facing === v} className={facing === v ? 'on' : ''} onClick={() => setFacing(v)}>{l}</button>
          ))}
        </div>
        <div className="field-label">Picture quality</div>
        <div className="segmented wide" role="radiogroup" aria-label="Picture quality">
          {([['low', 'Light'], ['standard', 'Standard'], ['high', 'High']] as const).map(([v, l]) => (
            <button key={v} role="radio" aria-checked={quality === v} className={quality === v ? 'on' : ''} onClick={() => setQuality(v)}>{l}</button>
          ))}
        </div>
        <label className="switch">
          <span className="switch-text"><strong>Share sound</strong><span className="muted small">Let watchers hear what is happening here.</span></span>
          <input type="checkbox" checked={mic} onChange={e => setMic(e.target.checked)} />
          <span className="switch-track"><span /></span>
        </label>
        <button className="nm-btn primary" onClick={begin} disabled={!secure || snap.status === 'starting'}><CameraIcon /> Start sharing</button>
        <p className="muted small">Keep this page open and the screen on. The browser stops sharing when the page is closed or the phone sleeps.</p>
      </div>
    )
  }

  return (
    <div className="cam-share card nm-raised">
      <div className="cam-share-head">
        <span className={'cam-chip ' + (snap.status === 'live' ? 'on' : '')}>
          {snap.status === 'live' && <span className="dot" />}
          {snap.status === 'live' ? `Sharing · ${snap.viewers} watching` : snap.status === 'waiting' ? 'Waiting for permission' : snap.status === 'reconnecting' ? 'Reconnecting…' : 'Starting…'}
        </span>
      </div>
      {snap.status === 'waiting' && (
        <div className="cam-result" role="status">
          <Aubree state="wait" size={84} />
          <p>Waiting for the phone’s owner to allow this device. Nothing is shared until they do.</p>
        </div>
      )}
      {snap.status === 'reconnecting' && <p className="cam-warn" role="status">Lost contact with the phone. Trying again. Viewers see this camera as offline until it is back.</p>}
      <div className={'cam-stage preview' + (snap.feeds.length > 1 ? ' two' : '')}>
        {snap.feeds.map(f => (
          <div key={f.id} className="cam-feed"><Preview stream={f.stream} /><span className="cam-feed-label">{f.label}</span></div>
        ))}
      </div>
      {snap.note && <p className="muted small" role="status">{snap.note}</p>}
      <div className="cam-bar">
        {snap.hasMic && (
          <button className="nm-btn sm" onClick={() => node.setMic(!snap.micOn)} aria-pressed={snap.micOn}>
            {snap.micOn ? <MicIcon /> : <MicOffIcon />} {snap.micOn ? 'Sound on' : 'Sound off'}
          </button>
        )}
        {snap.canFlip && <button className="nm-btn sm" onClick={() => void node.flip()}><SwitchCameraIcon /> Switch camera</button>}
        <span className="spacer" />
        <button className="nm-btn sm" onClick={() => void node.stop()}><StopIcon /> Stop sharing</button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------------------------------------ */
/* The page                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

export function CameraPage({ nodes, loaded, failed, node, onAuthLost }: {
  nodes: NodeView[]; loaded: boolean; failed: boolean; node: CameraNodeApi; onAuthLost: () => void
}) {
  const [mode, setMode] = useState<'watch' | 'share'>(() => (node.snap.status !== 'off' ? 'share' : 'watch'))
  const [watching, setWatching] = useState<string[]>([])
  const [hub, setHub] = useState<ViewerClient | null>(null)
  const [link, setLink] = useState(true)
  const lost = useRef(onAuthLost)
  lost.current = onAuthLost

  useEffect(() => {
    const h = new ViewerClient(() => lost.current(), setLink)
    setHub(h)
    return () => { setHub(null); h.close() }
  }, [])

  const toggle = (addr: string) => setWatching(cur => (cur.includes(addr) ? cur.filter(a => a !== addr) : [...cur, addr]))
  const shown = watching.map(a => nodes.find(n => n.addr === a)).filter((n): n is NodeView => !!n)
  const online = nodes.filter(n => n.online && !n.mine).length
  const sharing = node.snap.status === 'live'

  return (
    <div className="cam-page">
      <h2 className="page-title">Cameras</h2>
      <div className="segmented wide cam-mode" role="radiogroup" aria-label="Cameras">
        <button role="radio" aria-checked={mode === 'watch'} className={mode === 'watch' ? 'on' : ''} onClick={() => setMode('watch')}>
          Watch{online > 0 ? ` (${online} online)` : ''}
        </button>
        <button role="radio" aria-checked={mode === 'share'} className={mode === 'share' ? 'on' : ''} onClick={() => setMode('share')}>
          {sharing ? <><span className="dot cam-dot" /> Sharing this device</> : 'Share this device'}
        </button>
      </div>

      {mode === 'share' && <SharePanel node={node} />}

      {mode === 'watch' && (
        <>
          {(failed || !link) && <p className="cam-warn" role="status">Can’t reach the phone right now. Trying again…</p>}
          {shown.length > 0 && hub && (
            <div className={'cam-wall' + (shown.length > 1 ? ' multi' : '')}>
              {shown.map(n => <Tile key={n.addr} node={n} hub={hub} onClose={() => toggle(n.addr)} />)}
            </div>
          )}
          {loaded && nodes.length === 0 && (
            <div className="empty">
              <Aubree state="sleepy" size={140} />
              <h3>No cameras yet</h3>
              <p className="muted">Open DropLink on another device, choose Cameras, then Share this device. It shows up here once the phone’s owner allows it.</p>
              <button className="nm-btn" onClick={() => setMode('share')}><PlusIcon /> Share this device’s camera</button>
            </div>
          )}
          {nodes.length > 0 && (
            <>
              <h3 className="section">Camera nodes</h3>
              <ul className="cam-nodes">
                {nodes.map(n => <NodeCard key={n.addr} node={n} watching={watching.includes(n.addr)} onToggle={() => toggle(n.addr)} />)}
              </ul>
            </>
          )}
          {!loaded && !failed && <p className="muted" role="status">Looking for cameras…</p>}
        </>
      )}
    </div>
  )
}
