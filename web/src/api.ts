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
  async pairRequest(name: string): Promise<{ id: string; code: string }> {
    return (await request('/api/pair/request', { method: 'POST', body: JSON.stringify({ name }) }, 1)).json()
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
  async ping(): Promise<boolean> {
    try { const r = await fetch('/health', { cache: 'no-store' }); return r.ok } catch { return false }
  }
}

export const fileUrl = (share: string, path: string, download = false) =>
  `/api/file?share=${q(share)}&path=${q(path)}${download ? '&download=1' : ''}`
export const thumbUrl = (share: string, path: string) => `/api/thumb?share=${q(share)}&path=${q(path)}`
export const qrUrl = (text: string) => `/api/qr?text=${q(text)}`
