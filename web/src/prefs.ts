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
  copper: { name: 'Copper', light: '#a9622f', dark: '#d4965f' },
  blue: { name: 'Blue', light: '#2f7de1', dark: '#5aa2ff' },
  teal: { name: 'Teal', light: '#0e9f94', dark: '#35d3c6' },
  violet: { name: 'Violet', light: '#7a5af5', dark: '#a58bff' },
  rose: { name: 'Rose', light: '#e0457b', dark: '#ff7aa5' },
  amber: { name: 'Amber', light: '#d98a0b', dark: '#ffbf47' }
}

export function applyTheme(mode: ThemeMode, accent: string) {
  const root = document.documentElement
  const dark = mode === 'dark' || (mode === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  root.dataset.theme = dark ? 'dark' : 'light'
  const a = ACCENTS[accent] ?? ACCENTS.copper
  root.style.setProperty('--accent', dark ? a.dark : a.light)
  const meta = document.querySelector('meta[name="theme-color"]')
  meta?.setAttribute('content', dark ? '#0c0908' : '#f8f3ee')
}

export function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(() => load('theme', 'dark'))
  const [accent, setAccent] = useState<string>(() => load('accent', 'copper'))
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
