import type { Entry } from './api'

export type Kind = 'folder' | 'image' | 'video' | 'audio' | 'pdf' | 'text' | 'archive' | 'file'

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[i]}`
}

export const formatDate = (ms: number) =>
  ms > 0 ? new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : ''

export function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '0:00'
  const s = Math.floor(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`
}

const TEXT_EXT = ['txt', 'md', 'log', 'csv', 'json', 'xml', 'html', 'css', 'js', 'ts', 'kt', 'java', 'py', 'c', 'cpp', 'h', 'sh', 'yml', 'yaml', 'ini', 'srt']
const ARCHIVE_EXT = ['zip', 'rar', '7z', 'tar', 'gz', 'tgz']
const AUDIO_EXT = ['mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'opus', 'wma']
const VIDEO_EXT = ['mp4', 'mkv', 'webm', 'mov', 'avi', 'm4v', '3gp']
const IMAGE_EXT = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', 'heic']

export const extOf = (name: string) => (name.includes('.') ? name.split('.').pop()!.toLowerCase() : '')

export function kindOf(e: Pick<Entry, 'name' | 'mime' | 'isDir'>): Kind {
  if (e.isDir) return 'folder'
  const m = e.mime.toLowerCase()
  const ext = extOf(e.name)
  if (m.startsWith('image/') || IMAGE_EXT.includes(ext)) return 'image'
  if (m.startsWith('video/') || VIDEO_EXT.includes(ext)) return 'video'
  if (m.startsWith('audio/') || AUDIO_EXT.includes(ext)) return 'audio'
  if (m === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (ARCHIVE_EXT.includes(ext) || m.includes('zip') || m.includes('compressed')) return 'archive'
  if (m.startsWith('text/') || m === 'application/json' || TEXT_EXT.includes(ext)) return 'text'
  return 'file'
}

export const stripExt = (name: string) => name.replace(/\.[^./]+$/, '')
