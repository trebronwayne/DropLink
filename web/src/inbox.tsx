import { useCallback, useEffect, useRef, useState } from 'react'
import { api, AuthError, InboxFile, InboxItem, inboxUrl } from './api'
import { useDialog } from './a11y'
import { Aubree } from './Aubree'
import { detectModel, saveWho, savedWho } from './device'
import { CloseIcon, DownloadIcon, RefreshIcon } from './icons'
import { useToast } from './toast'
import { formatSize } from './util'

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

/**
 * Files the phone's owner sent to this device. Checking in also tells the phone this device is online, which is
 * what lets the owner choose it as a recipient. Quick while the page is in front, slow in the background.
 */
export function useInbox(enabled: boolean, onAuthLost: () => void) {
  const [items, setItems] = useState<InboxItem[]>([])
  const known = useRef<Set<string> | null>(null)
  const toast = useToast()
  const lost = useRef(onAuthLost)
  lost.current = onAuthLost

  const refresh = useCallback(async () => {
    try {
      const list = await api.inbox()
      setItems(list)
      if (known.current === null) known.current = new Set(list.map(i => i.id))
      else {
        const fresh = list.filter(i => !known.current!.has(i.id))
        fresh.forEach(i => known.current!.add(i.id))
        if (fresh.length) {
          const n = fresh.reduce((sum, i) => sum + i.files.length, 0)
          toast.show(`The phone sent you ${n} file${n === 1 ? '' : 's'}`)
        }
      }
    } catch (e) {
      if (e instanceof AuthError) lost.current()
      /* offline: try again on the next round */
    }
  }, [toast])

  useEffect(() => {
    if (!enabled) return
    let stop = false
    let timer = 0
    const loop = async () => {
      await refresh()
      if (!stop) timer = window.setTimeout(loop, document.hidden ? 15_000 : 4_000)
    }
    loop()
    const onVisible = () => { if (!document.hidden) { window.clearTimeout(timer); loop() } }
    document.addEventListener('visibilitychange', onVisible)
    return () => { stop = true; window.clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [enabled, refresh])

  const waiting = items.reduce((n, i) => n + i.files.filter(f => f.state !== 'done').length, 0)
  return { items, waiting, refresh }
}

function startDownload(batch: string, f: InboxFile) {
  const a = document.createElement('a')
  a.href = inboxUrl(batch, f.index)
  a.download = f.name
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

const label = (f: InboxFile) =>
  f.state === 'done' ? 'Downloaded' : f.state === 'downloading' ? `${Math.round((f.bytes / Math.max(1, f.size)) * 100)}%`
    : f.state === 'interrupted' ? `Stopped at ${Math.round((f.bytes / Math.max(1, f.size)) * 100)}%` : 'Ready'

/** The "From the phone" page. */
export function InboxPage({ items, onRefresh }: { items: InboxItem[]; onRefresh: () => void }) {
  const [busy, setBusy] = useState(false)
  const downloadAll = async (item: InboxItem) => {
    setBusy(true)
    // A short gap between files, because browsers can refuse several downloads started at the same instant.
    for (const f of item.files) { startDownload(item.id, f); await sleep(900) }
    setBusy(false)
    window.setTimeout(onRefresh, 1500)
  }
  return (
    <>
      <div className="page-head">
        <h2 className="page-title">From the phone</h2>
        <button className="nm-btn sm" onClick={onRefresh}><RefreshIcon width={18} height={18} /> Refresh</button>
      </div>
      {items.length === 0 && (
        <div className="empty"><Aubree state="empty" size={140} /><h3>Nothing sent to you yet</h3><p className="muted">When the phone’s owner sends you files, they appear here and you get a notice.</p></div>
      )}
      {items.map(item => {
        const total = item.files.reduce((n, f) => n + f.size, 0)
        return (
          <section key={item.id} className="nm-raised card inbox-card" aria-label="Files from the phone">
            <header className="inbox-head">
              <div>
                <h3>{item.files.length} file{item.files.length === 1 ? '' : 's'} · {formatSize(total)}</h3>
                <span className="muted small">{new Date(item.createdAt).toLocaleString()}</span>
              </div>
              <span className="spacer" />
              {item.files.length > 1 && <button className="nm-btn primary sm" disabled={busy} onClick={() => downloadAll(item)}><DownloadIcon width={18} height={18} /> Download all</button>}
            </header>
            <ul className="inbox-list">
              {item.files.map(f => {
                const pct = f.state === 'done' ? 100 : Math.round((f.bytes / Math.max(1, f.size)) * 100)
                return (
                  <li key={f.index} className={f.state}>
                    <span className="ellipsis inbox-name">{f.name}</span>
                    <span className="muted small">{formatSize(f.size)} · {label(f)}</span>
                    <button className={'nm-btn sm' + (f.state === 'waiting' ? ' primary' : '')} onClick={() => { startDownload(item.id, f); window.setTimeout(onRefresh, 1500) }}>
                      <DownloadIcon width={18} height={18} /> {f.state === 'done' ? 'Again' : f.state === 'interrupted' ? 'Retry' : 'Download'}
                    </button>
                    <span className="bar" role="progressbar" aria-label={`Progress for ${f.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><span style={{ width: pct + '%' }} /></span>
                  </li>
                )
              })}
            </ul>
            {item.files.some(f => f.state === 'interrupted') && (
              <p className="muted small">A download that stopped can usually be continued from your browser’s downloads list with its Resume button, and it carries on from the same place. Retry starts it again.</p>
            )}
          </section>
        )
      })}
    </>
  )
}

/** Asks who is using this device and its exact model, so the phone’s owner can tell devices apart. */
export function IdentifyDialog({ onClose }: { onClose: () => void }) {
  const dialog = useDialog<HTMLElement>(onClose)
  const toast = useToast()
  const [person, setPerson] = useState(savedWho().person)
  const [model, setModel] = useState(savedWho().model)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    api.device().then(d => { if (d.person) setPerson(d.person); if (d.model) setModel(d.model) }).catch(() => undefined)
    detectModel().then(m => setModel(cur => cur || m))
  }, [])
  const save = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      await api.setDevice(person.trim(), model.trim())
      saveWho({ person: person.trim(), model: model.trim() })
      toast.show('Saved. The phone now shows your name.')
      onClose()
    } catch { toast.show('Could not reach the phone.', 'error') }
    finally { setBusy(false) }
  }
  return (
    <div className="overlay" onClick={onClose}>
      <section ref={dialog} tabIndex={-1} className="viewer nm-raised identify" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Who is this device?">
        <header className="viewer-bar"><strong>Who is this device?</strong><span className="spacer" />
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>
        <form className="stack identify-body" onSubmit={save}>
          <p className="muted">Browsers don’t share a device’s name or model, so tell the phone. Its owner sees this when choosing who to send files to.</p>
          <label className="field-label" htmlFor="who-name">Your name</label>
          <input id="who-name" className="nm-input" value={person} maxLength={40} placeholder="For example Sarah" onChange={e => setPerson(e.target.value)} data-autofocus />
          <label className="field-label" htmlFor="who-model">Device model</label>
          <input id="who-model" className="nm-input" value={model} maxLength={60} placeholder="For example Samsung Galaxy A14" onChange={e => setModel(e.target.value)} />
          <div className="upload-actions"><span className="spacer" /><button type="button" className="nm-btn" onClick={onClose}>Not now</button><button className="nm-btn primary" disabled={busy || !person.trim()}>Save</button></div>
        </form>
      </section>
    </div>
  )
}
