import { useEffect, useMemo, useRef } from 'react'
import { thumbUrl } from './api'
import { FileTypeIcon, MinimizeIcon, TrashIcon } from './icons'
import { formatSize, stripExt } from './util'
import type { Track } from './player'

export type ListTab = 'all' | 'music' | 'videos' | 'recent' | 'queue'

type Props = {
  tab: ListTab
  setTab: (t: ListTab) => void
  current: Track | null
  loading: boolean
  folderName: string
  /** Every song and video in the folder of the file that is playing. This is the only folder ever listed. */
  all: Track[]
  recent: Track[]
  queue: Track[]
  index: number
  onPick: (list: Track[], i: number) => void
  onGo: (i: number) => void
  onMove: (i: number, d: -1 | 1) => void
  onRemove: (i: number) => void
  onClear: () => void
}

const same = (a: Track | null, b: Track) => !!a && a.share === b.share && a.path === b.path

export function PlayerLists(p: Props) {
  const music = useMemo(() => p.all.filter(t => t.kind === 'audio'), [p.all])
  const videos = useMemo(() => p.all.filter(t => t.kind === 'video'), [p.all])
  const tabs: { id: ListTab; label: string; count: number }[] = [
    { id: 'all', label: 'All', count: p.all.length },
    { id: 'music', label: 'Music', count: music.length },
    { id: 'videos', label: 'Videos', count: videos.length },
    { id: 'recent', label: 'Recent', count: p.recent.length },
    { id: 'queue', label: 'Queue', count: p.queue.length }
  ]
  const list = p.tab === 'all' ? p.all : p.tab === 'music' ? music : p.tab === 'videos' ? videos : p.tab === 'recent' ? p.recent : p.queue
  const folderTab = p.tab === 'all' || p.tab === 'music' || p.tab === 'videos'
  const box = useRef<HTMLOListElement>(null)
  const key = p.current ? p.current.share + '|' + p.current.path : ''

  // Keep the item that is playing in view, without moving the whole page.
  useEffect(() => {
    const host = box.current
    const el = host?.querySelector<HTMLElement>('[aria-current="true"]')
    if (host && el) host.scrollTop = Math.max(0, el.offsetTop - host.offsetTop - host.clientHeight / 3)
  }, [key, p.tab, list.length])

  const lead = p.tab === 'recent' ? 'What you played lately'
    : p.tab === 'queue' ? `${p.queue.length} in the queue`
      : p.current ? `From “${p.folderName}”` : ''
  const empty = p.loading && folderTab ? 'Loading this folder…'
    : p.tab === 'music' ? 'No music in this folder.'
      : p.tab === 'videos' ? 'No videos in this folder.'
        : p.tab === 'recent' ? 'Nothing played yet.'
          : p.tab === 'queue' ? 'The queue is empty.' : 'No songs or videos in this folder.'

  return (
    <aside className="watch-lists" aria-label="Up next and library">
      <div className="watch-tabs" role="tablist" aria-label="Lists">
        {tabs.map(t => (
          <button key={t.id} role="tab" aria-selected={p.tab === t.id} className={'chip' + (p.tab === t.id ? ' on' : '')} onClick={() => p.setTab(t.id)}>
            {t.label}{t.count > 0 && <span className="chip-count">{t.count}</span>}
          </button>
        ))}
      </div>
      <div className="watch-lead">
        <span className="muted small ellipsis">{lead}</span>
        <span className="spacer" />
        {p.tab === 'queue' && p.queue.length > 1 && <button className="nm-btn sm" onClick={p.onClear}>Clear others</button>}
      </div>
      {list.length === 0 ? (
        <p className={'muted small watch-empty' + (p.loading && folderTab ? ' loading' : '')} role="status">{empty}</p>
      ) : (
        <ol className="watch-list" ref={box} role="tabpanel">
          {list.map((t, i) => {
            const playing = p.tab === 'queue' ? i === p.index : same(p.current, t)
            return (
              <li key={t.share + '|' + t.path + (p.tab === 'queue' ? '|' + i : '')} className={playing ? 'now' : ''}>
                <button className="wl-item" aria-current={playing ? 'true' : undefined} onClick={() => (p.tab === 'queue' ? p.onGo(i) : p.onPick(list, i))}>
                  <span className="wl-thumb">
                    <span className="wl-icon"><FileTypeIcon kind={t.kind} size={26} /></span>
                    {t.kind === 'video' && t.size > 0 && <img src={thumbUrl(t.share, t.path)} alt="" loading="lazy" decoding="async" onError={e => { e.currentTarget.style.display = 'none' }} />}
                    {playing && <span className="eq on small" aria-hidden="true"><i /><i /><i /></span>}
                  </span>
                  <span className="wl-text">
                    <strong className="wl-title">{stripExt(t.name)}</strong>
                    <span className="muted small ellipsis">
                      {t.kind === 'video' ? 'Video' : 'Music'} · {formatSize(t.size)}
                      {p.tab === 'recent' ? ` · ${t.path.split('/').slice(-2, -1)[0] || 'Shared folder'}` : ''}
                    </span>
                  </span>
                </button>
                {p.tab === 'queue' && (
                  <span className="wl-actions">
                    <button className="icon-btn sm" aria-label={`Move ${stripExt(t.name)} up`} disabled={i === 0} onClick={() => p.onMove(i, -1)}><MinimizeIcon className="flip-y" width={18} height={18} /></button>
                    <button className="icon-btn sm" aria-label={`Move ${stripExt(t.name)} down`} disabled={i === p.queue.length - 1} onClick={() => p.onMove(i, 1)}><MinimizeIcon width={18} height={18} /></button>
                    <button className="icon-btn sm" aria-label={`Remove ${stripExt(t.name)} from the queue`} onClick={() => p.onRemove(i)}><TrashIcon width={18} height={18} /></button>
                  </span>
                )}
              </li>
            )
          })}
        </ol>
      )}
    </aside>
  )
}
