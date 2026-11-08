export type Share = { id: string; name: string }
export type Entry = { name: string; isDir: boolean; size: number; mime: string; modified: number }

export class AuthError extends Error {}
export class LockedError extends Error {}

async function request(url: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, { credentials: 'same-origin', ...init })
  if (response.status === 401) throw new AuthError()
  if (response.status === 429) throw new LockedError()
  if (!response.ok) throw new Error(`Request failed (${response.status})`)
  return response
}

const q = encodeURIComponent

export const api = {
  async login(pin: string): Promise<void> {
    await request('/api/login', { method: 'POST', body: JSON.stringify({ pin }) })
  },
  async info(): Promise<{ nodeId: string }> {
    return (await request('/api/info')).json()
  },
  async shares(): Promise<Share[]> {
    return (await request('/api/shares')).json()
  },
  async browse(share: string, path: string): Promise<Entry[]> {
    return (await request(`/api/browse?share=${q(share)}&path=${q(path)}`)).json()
  },
  async preview(share: string, path: string): Promise<string> {
    const r = await request(fileUrl(share, path), { headers: { Range: 'bytes=0-204799' } })
    return r.text()
  }
}

export const fileUrl = (share: string, path: string, download = false) =>
  `/api/file?share=${q(share)}&path=${q(path)}${download ? '&download=1' : ''}`
