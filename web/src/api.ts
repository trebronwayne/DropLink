export type Share = { id: string; name: string }
export type Entry = { name: string; isDir: boolean; size: number; mime: string; modified: number }

export class AuthError extends Error {}
export class LockedError extends Error {}
export class OfflineError extends Error {}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

/** fetch with automatic retries, so a brief Wi-Fi hiccup or a phone waking up never shows an error. */
async function request(url: string, init?: RequestInit, retries = 3): Promise<Response> {
  let attempt = 0
  for (;;) {
    try {
      const response = await fetch(url, { credentials: 'same-origin', ...init })
      if (response.status === 401) throw new AuthError()
      if (response.status === 429) throw new LockedError()
      if (response.status >= 500 && attempt < retries) { attempt++; await sleep(300 * attempt); continue }
      if (!response.ok) throw new Error(`Request failed (${response.status})`)
      return response
    } catch (e) {
      if (e instanceof AuthError || e instanceof LockedError) throw e
      if (e instanceof TypeError || (e instanceof Error && e.message.startsWith('Request failed (5'))) {
        if (attempt >= retries) throw new OfflineError()
        attempt++
        await sleep(400 * attempt)
        continue
      }
      throw e
    }
  }
}

const q = encodeURIComponent

/* ---------- sending files to the phone ---------- */
export type UploadPolicy = 'ask' | 'always' | 'blocked'
export type UploadInfo = { policy: UploadPolicy; maxFiles: number; maxFileBytes: number; maxTotalBytes: number }
export type UploadState = 'pending' | 'approved' | 'denied' | 'expired' | 'done'

/** The phone said no, or could not take the file. `code` is a short machine-readable reason. */
export class UploadError extends Error {
  code: string
  /** For `offset_mismatch` and `incomplete`: how much the phone really has. */
  offset?: number
  constructor(code: string, message?: string, offset?: number) { super(message ?? code); this.code = code; this.offset = offset }
}

async function uploadJson<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response
  try { response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...init }) } catch { throw new OfflineError() }
  if (response.status === 401) throw new AuthError()
  let body: Record<string, unknown> = {}
  try { body = await response.json() } catch { /* empty body */ }
  if (!response.ok) throw new UploadError(String(body.error ?? 'failed'), `Request failed (${response.status})`)
  return body as T
}

/** What the phone already holds of a file it is receiving. */
export type UploadOffset = { offset: number; done: boolean }

/**
 * Sends one file (from [offset] onward) as the raw request body. The phone keeps what already arrived, so after a
 * dropped connection the next call starts at the phone's offset instead of at zero.
 * XMLHttpRequest is used because fetch cannot report upload progress.
 */
export function sendFile(id: string, index: number, file: File, offset: number, onProgress: (loaded: number) => void, signal: AbortSignal): Promise<{ name: string }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', `/api/upload/file?id=${q(id)}&index=${index}`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.setRequestHeader('X-Upload-Offset', String(offset))
    xhr.upload.onprogress = e => onProgress(offset + e.loaded)
    xhr.onload = () => {
      let body: Record<string, unknown> = {}
      try { body = JSON.parse(xhr.responseText) } catch { /* empty body */ }
      if (xhr.status === 401) reject(new AuthError())
      else if (xhr.status >= 200 && xhr.status < 300) resolve({ name: String(body.name ?? file.name) })
      else reject(new UploadError(String(body.error ?? 'failed'), `Upload failed (${xhr.status})`, typeof body.offset === 'number' ? body.offset : undefined))
    }
    xhr.onerror = () => reject(new OfflineError())
    xhr.onabort = () => reject(new DOMException('Aborted', 'AbortError'))
    signal.addEventListener('abort', () => xhr.abort(), { once: true })
    xhr.send(offset > 0 ? file.slice(offset) : file)
  })
}

/* ---------- who this device is, and files the phone sends here ---------- */
export type DeviceInfo = { id: string; name: string; person: string; model: string; platform: string }
export type InboxFile = { index: number; name: string; size: number; mime: string; bytes: number; state: 'waiting' | 'downloading' | 'done' | 'interrupted' }
export type InboxItem = { id: string; createdAt: number; files: InboxFile[] }

/* ---------- listing cache: instant paint from memory/session, then revalidate ---------- */
type Cached = { at: number; data: Entry[] }
const mem = new Map<string, Cached>()
const inflight = new Map<string, Promise<Entry[]>>()
const key = (share: string, path: string) => `${share}|${path}`

export function cachedBrowse(share: string, path: string): Entry[] | undefined {
  const k = key(share, path)
  const hit = mem.get(k)
  if (hit) return hit.data
  try {
    const raw = sessionStorage.getItem('dl.l.' + k)
    if (raw) {
      const parsed = JSON.parse(raw) as Cached
      mem.set(k, parsed)
      return parsed.data
    }
  } catch { /* ignore */ }
  return undefined
}

function remember(k: string, data: Entry[]) {
  const c: Cached = { at: Date.now(), data }
  mem.set(k, c)
  if (mem.size > 200) mem.delete(mem.keys().next().value as string)
  try { if (data.length < 1500) sessionStorage.setItem('dl.l.' + k, JSON.stringify(c)) } catch { /* quota */ }
}

export const api = {
  async pairRequest(info: { name: string; person: string; model: string; platform: string }): Promise<{ id: string; code: string }> {
    return (await request('/api/pair/request', { method: 'POST', body: JSON.stringify(info) }, 1)).json()
  },
  async device(): Promise<DeviceInfo> {
    return (await request('/api/device')).json()
  },
  async setDevice(person: string, model: string): Promise<void> {
    await request('/api/device/info', { method: 'POST', body: JSON.stringify({ person, model }) }, 1)
  },
  /** Files the phone's owner sent to this device. Also tells the phone this device is online. */
  async inbox(): Promise<InboxItem[]> {
    const r = await fetch('/api/inbox', { credentials: 'same-origin', cache: 'no-store' })
    if (r.status === 401) throw new AuthError()
    if (!r.ok) throw new Error(`Request failed (${r.status})`)
    return (await r.json()).items as InboxItem[]
  },
  async pairStatus(id: string): Promise<'pending' | 'approved' | 'denied' | 'expired'> {
    return (await request(`/api/pair/status?id=${q(id)}`, undefined, 1)).json().then(r => r.status)
  },
  async shares(): Promise<Share[]> {
    return (await request('/api/shares')).json()
  },
  /** Network fetch (deduplicated). Use cachedBrowse() first for an instant paint. */
  browse(share: string, path: string, fresh = false): Promise<Entry[]> {
    const k = key(share, path)
    const running = inflight.get(k)
    if (running && !fresh) return running
    const p = request(`/api/browse?share=${q(share)}&path=${q(path)}${fresh ? '&fresh=1' : ''}`)
      .then(r => r.json() as Promise<Entry[]>)
      .then(data => { remember(k, data); return data })
      .finally(() => inflight.delete(k))
    inflight.set(k, p)
    return p
  },
  prefetch(share: string, path: string) {
    const k = key(share, path)
    const hit = mem.get(k)
    if (hit && Date.now() - hit.at < 15_000) return
    this.browse(share, path).catch(() => undefined)
  },
  async addresses(): Promise<{ url: string; label: string }[]> {
    return (await request('/api/addresses')).json()
  },
  async preview(share: string, path: string): Promise<string> {
    const r = await request(fileUrl(share, path), { headers: { Range: 'bytes=0-204799' } })
    return r.text()
  },
  uploadInfo(): Promise<UploadInfo> {
    return uploadJson<UploadInfo>('/api/upload/info')
  },
  uploadOffset(id: string, index: number): Promise<UploadOffset> {
    return uploadJson<UploadOffset>(`/api/upload/offset?id=${q(id)}&index=${index}`)
  },
  uploadRequest(files: { name: string; size: number; fp: string }[]): Promise<{ id: string; status: UploadState; always: boolean }> {
    return uploadJson('/api/upload/request', { method: 'POST', body: JSON.stringify({ files }) })
  },
  uploadStatus(id: string): Promise<{ status: UploadState }> {
    return uploadJson(`/api/upload/status?id=${q(id)}`)
  },
  uploadCancel(id: string): Promise<unknown> {
    return uploadJson(`/api/upload/cancel?id=${q(id)}`, { method: 'POST' }).catch(() => undefined)
  },
  async ping(): Promise<boolean> {
    try { const r = await fetch('/health', { cache: 'no-store' }); return r.ok } catch { return false }
  }
}

export const fileUrl = (share: string, path: string, download = false) =>
  `/api/file?share=${q(share)}&path=${q(path)}${download ? '&download=1' : ''}`
export const inboxUrl = (batch: string, index: number) => `/api/inbox/file?batch=${q(batch)}&index=${index}`
export const thumbUrl = (share: string, path: string) => `/api/thumb?share=${q(share)}&path=${q(path)}`
export const qrUrl = (text: string) => `/api/qr?text=${q(text)}`
