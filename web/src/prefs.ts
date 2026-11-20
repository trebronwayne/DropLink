import { useEffect, useState, useCallback } from 'react'
import type { Kind } from './util'

const P = 'droplink.'
export function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(P + key)
    return raw === null ? fallback : (JSON.parse(raw) as T)
  } catch { return fallback }
}
export function save(key: string, value: unknown) {
  try { localStorage.setItem(P + key, JSON.stringify(value)) } catch { /* private mode */ }
}

/* ---------- theme ---------- */
export type ThemeMode = 'system' | 'light' | 'dark'
export const ACCENTS: Record<string, { name: string; light: string; dark: string }> = {
  blue: { name: 'Blue', light: '#0f6cbd', dark: '#4da3ff' },
  teal: { name: 'Teal', light: '#0e86b4', dark: '#55c6de' },
  violet: { name: 'Indigo', light: '#4f52b2', dark: '#9b9df5' },
  green: { name: 'Green', light: '#107c41', dark: '#4fbf7f' },
  graphite: { name: 'Graphite', light: '#4b5563', dark: '#a4adb9' }
}

export function applyTheme(mode: ThemeMode, accent: string) {
  const root = document.documentElement
  const dark = mode === 'dark' || (mode === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  root.dataset.theme = dark ? 'dark' : 'light'
  const a = ACCENTS[accent] ?? ACCENTS.teal
  root.style.setProperty('--accent', dark ? a.dark : a.light)
  const meta = document.querySelector('meta[name="theme-color"]')
  meta?.setAttribute('content', dark ? '#111113' : '#f3f4f6')
}

export function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(() => load('theme', 'system'))
  const [accent, setAccent] = useState<string>(() => load('accent', 'teal'))
  useEffect(() => {
    applyTheme(mode, accent)
    save('theme', mode); save('accent', accent)
    if (mode !== 'system') return
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const on = () => applyTheme(mode, accent)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [mode, accent])
  return { mode, setMode, accent, setAccent }
}

/* ---------- display & accessibility ---------- */
export type Display = { text: 'normal' | 'large' | 'xlarge'; motion: 'auto' | 'reduce'; contrast: 'normal' | 'high'; density: 'comfortable' | 'compact' }
const DISPLAY_DEFAULT: Display = { text: 'normal', motion: 'auto', contrast: 'normal', density: 'comfortable' }
const readDisplay = (): Display => ({ ...DISPLAY_DEFAULT, ...load<Partial<Display>>('display', {}) })
function applyDisplay(d: Display) {
  const root = document.documentElement
  root.dataset.text = d.text; root.dataset.motion = d.motion; root.dataset.contrast = d.contrast; root.dataset.density = d.density
}
applyDisplay(readDisplay()) // before the first paint, so large text or high contrast never flashes

export function useDisplay() {
  const [display, setDisplay] = useState<Display>(readDisplay)
  useEffect(() => { applyDisplay(display); save('display', display) }, [display])
  const set = useCallback(<K extends keyof Display>(key: K, value: Display[K]) => setDisplay(cur => ({ ...cur, [key]: value })), [])
  return { display, set }
}

/** A simple persisted setting. */
export function useSetting<T>(key: string, fallback: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => load(key, fallback))
  const set = useCallback((next: T) => { setValue(next); save(key, next) }, [key])
  return [value, set]
}

/* ---------- resume positions (videos and long audio) ---------- */
type ResumeEntry = { t: number; d: number; at: number }
let resumeCache: Record<string, ResumeEntry> | null = null
const resumeMap = () => {
  if (!resumeCache) resumeCache = load<Record<string, ResumeEntry>>('resume-map', {})
  return resumeCache
}
export const getResume = (share: string, path: string): ResumeEntry | undefined => resumeMap()[share + '|' + path]
export function setResume(share: string, path: string, t: number, d: number) {
  const map = resumeMap()
  const key = share + '|' + path
  if (!isFinite(d) || d <= 0 || t < 5 || t > d - 10) delete map[key]
  else map[key] = { t, d, at: Date.now() }
  const keys = Object.keys(map)
  if (keys.length > 150) keys.sort((x, y) => map[x].at - map[y].at).slice(0, keys.length - 150).forEach(k => { delete map[k] })
  save('resume-map', map)
}

/* ---------- favorites & recents ---------- */
export type Ref = { share: string; path: string; name: string; kind: Kind; size: number }
export type Recent = Ref & { at: number }

const same = (a: Ref, b: Ref) => a.share === b.share && a.path === b.path

export function useFavorites() {
  const [list, setList] = useState<Ref[]>(() => load<Ref[]>('favorites', []))
  const toggle = useCallback((ref: Ref) => {
    setList(cur => {
      const next = cur.some(r => same(r, ref)) ? cur.filter(r => !same(r, ref)) : [ref, ...cur]
      save('favorites', next)
      return next
    })
  }, [])
  const has = useCallback((ref: Ref) => list.some(r => same(r, ref)), [list])
  return { list, toggle, has }
}

export function useRecents() {
  const [list, setList] = useState<Recent[]>(() => load<Recent[]>('recents', []))
  const add = useCallback((ref: Ref) => {
    setList(cur => {
      const next = [{ ...ref, at: Date.now() }, ...cur.filter(r => !same(r, ref))].slice(0, 30)
      save('recents', next)
      return next
    })
  }, [])
  const clear = useCallback(() => { setList([]); save('recents', []) }, [])
  return { list, add, clear }
}
