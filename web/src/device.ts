/** What the browser can tell us about this device. Browsers hide the exact model, so the visitor can add it. */
export type Detected = { name: string; platform: string; model: string }

type UAData = { platform?: string; getHighEntropyValues?: (hints: string[]) => Promise<{ model?: string; platformVersion?: string }> }

export function browserName(): string {
  const ua = navigator.userAgent
  return /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /SamsungBrowser\//.test(ua) ? 'Samsung Internet' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
}

export function osName(): string {
  const ua = navigator.userAgent
  return /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : 'device'
}

/** "Chrome on Android". */
export const guessDeviceName = () => `${browserName()} on ${osName()}`

/** Newer Chrome freezes the model to a single letter; older Android browsers still put the real one in the user agent. */
function modelFromUserAgent(): string {
  const m = /Android [\d.]+; ([^;)]+?)(?: Build\/[^;)]*)?[;)]/.exec(navigator.userAgent)
  const model = m?.[1]?.trim() ?? ''
  return model.length > 2 && model !== 'K' ? model : ''
}

/** Best guess of the device model: the real one if the browser shares it, otherwise empty so the visitor can type it. */
export async function detectModel(): Promise<string> {
  const data = (navigator as unknown as { userAgentData?: UAData }).userAgentData
  try {
    const high = await data?.getHighEntropyValues?.(['model'])
    const model = high?.model?.trim()
    if (model && model.length > 1) return model
  } catch { /* the browser said no */ }
  return modelFromUserAgent()
}

const KEY = 'dl.who'
export type Who = { person: string; model: string }

/** What this visitor typed last time, so reconnecting does not ask again. */
export function savedWho(): Who {
  try { const o = JSON.parse(localStorage.getItem(KEY) ?? '{}'); return { person: String(o.person ?? ''), model: String(o.model ?? '') } } catch { return { person: '', model: '' } }
}
export function saveWho(w: Who) { try { localStorage.setItem(KEY, JSON.stringify(w)) } catch { /* private mode */ } }
