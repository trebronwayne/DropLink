import { useCallback, useEffect, useRef, useState } from 'react'
import { api, AuthError, OfflineError, sendFile, UploadError, UploadInfo } from './api'
import { useDialog } from './a11y'
import { Aubree } from './Aubree'
import { CloseIcon, UploadIcon } from './icons'
import { useToast } from './toast'
import { formatSize } from './util'

type Phase = 'choose' | 'waiting' | 'sending' | 'done' | 'denied' | 'blocked' | 'expired' | 'error'
type Item = { file: File; state: 'queued' | 'sending' | 'done' | 'failed'; sent: number; error?: string }

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
const APPROVAL_WINDOW_MS = 5 * 60_000
const fileKey = (f: File) => `${f.name}|${f.size}|${f.lastModified}`

function errorText(e: unknown): string {
  if (e instanceof OfflineError) return 'Lost connection to the phone.'
  if (e instanceof UploadError) {
    if (e.code === 'no_space' || e.code === 'cannot_save') return 'The phone is out of storage space.'
    if (e.code === 'size_mismatch' || e.code === 'incomplete') return 'The upload was interrupted.'
    if (e.code === 'too_many_requests') return 'Too many requests. Wait a minute and try again.'
    if (e.code === 'file_too_large' || e.code === 'too_large') return 'These files are too large to send in one go.'
  }
  return 'Something went wrong.'
}

/** Sending files to the phone: choose files, wait for the phone's owner to approve, then watch them arrive. */
export function UploadDialog({ initialFiles, onClose }: { initialFiles: File[]; onClose: () => void }) {
  const dialog = useDialog<HTMLElement>(onClose)
  const toast = useToast()
  const [phase, setPhase] = useState<Phase>('choose')
  const [items, setItems] = useState<Item[]>([])
  const [info, setInfo] = useState<UploadInfo | null>(null)
  const [note, setNote] = useState('')
  const [deadline, setDeadline] = useState(0)
  const [now, setNow] = useState(Date.now())
  const [over, setOver] = useState(false)
  const itemsRef = useRef<Item[]>([])
  const cancelled = useRef(false)
  const abort = useRef<AbortController | null>(null)
  const requestId = useRef('')
  const input = useRef<HTMLInputElement>(null)

  const update = useCallback((fn: (cur: Item[]) => Item[]) => {
    itemsRef.current = fn(itemsRef.current)
    setItems(itemsRef.current)
  }, [])

  const add = useCallback((files: File[]) => {
    const limits = info
    const have = new Set(itemsRef.current.map(i => fileKey(i.file)))
    const skipped: string[] = []
    const fresh: Item[] = []
    for (const f of files) {
      if (have.has(fileKey(f))) continue
      if (limits && f.size > limits.maxFileBytes) { skipped.push(f.name); continue }
      if (limits && itemsRef.current.length + fresh.length >= limits.maxFiles) { skipped.push(f.name); continue }
      have.add(fileKey(f))
      fresh.push({ file: f, state: 'queued', sent: 0 })
    }
    update(cur => [...cur, ...fresh])
    setNote(skipped.length ? `${skipped.length} file${skipped.length === 1 ? ' was' : 's were'} skipped because of the size or count limit.` : '')
  }, [info, update])

  useEffect(() => { api.uploadInfo().then(setInfo).catch(() => undefined) }, [])
  useEffect(() => {
    if (info?.policy === 'blocked') setPhase('blocked')
  }, [info])
  useEffect(() => {
    if (phase === 'done') toast.show(itemsRef.current.length === 1 ? 'File sent to the phone' : `${itemsRef.current.length} files sent to the phone`)
  }, [phase, toast])
  const firstAdd = useRef(false)
  useEffect(() => {
    if (firstAdd.current || !info || !initialFiles.length) return
    firstAdd.current = true
    add(initialFiles)
  }, [info, initialFiles, add])
  useEffect(() => {
    if (phase !== 'waiting') return
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [phase])
  // Closing the dialog stops anything still going.
  useEffect(() => () => {
    cancelled.current = true
    abort.current?.abort()
    if (requestId.current) void api.uploadCancel(requestId.current)
  }, [])

  /**
   * Sends one file and, if the connection drops, asks the phone how much it already has and continues from there.
   * The phone keeps the partial file, so nothing is sent twice, even after a long break or a fresh request.
   */
  const sendResumable = async (id: string, n: number, i: number, file: File, controller: AbortController) => {
    let attempt = 0
    for (;;) {
      try {
        const have = await api.uploadOffset(id, n)
        if (have.done) return
        if (have.offset > 0) update(cur => cur.map((it, k) => (k === i ? { ...it, sent: have.offset } : it)))
        await sendFile(id, n, file, have.offset, loaded => update(cur => cur.map((it, k) => (k === i ? { ...it, sent: loaded } : it))), controller.signal)
        return
      } catch (e) {
        if (cancelled.current) throw e
        const retry = e instanceof OfflineError || (e instanceof UploadError && (e.code === 'incomplete' || e.code === 'offset_mismatch'))
        if (!retry || attempt >= 15) throw e
        attempt++
        setNote('Connection lost. Waiting to resume where it stopped\u2026')
        await sleep(Math.min(1000 * attempt, 8000))
        if (cancelled.current) throw e
      }
    }
  }

  const send = async () => {
    const list = itemsRef.current
    const indexes = list.map((_, i) => i).filter(i => list[i].state !== 'done')
    if (!indexes.length) return
    cancelled.current = false
    const controller = new AbortController()
    abort.current = controller
    update(cur => cur.map((it, i) => (indexes.includes(i) ? { ...it, state: 'queued', sent: 0, error: undefined } : it)))
    setNote('')
    setPhase('waiting')
    setDeadline(Date.now() + APPROVAL_WINDOW_MS)
    setNow(Date.now())
    try {
      const created = await api.uploadRequest(indexes.map(i => ({ name: list[i].file.name, size: list[i].file.size, fp: fileKey(list[i].file) })))
      requestId.current = created.id
      let status = created.status
      while (status === 'pending') {
        await sleep(1200)
        if (cancelled.current) return
        status = (await api.uploadStatus(created.id)).status
      }
      if (cancelled.current) return
      if (status === 'denied') { setPhase('denied'); return }
      if (status !== 'approved') { setPhase('expired'); return }
      setPhase('sending')
      for (let n = 0; n < indexes.length; n++) {
        const i = indexes[n]
        if (cancelled.current) return
        update(cur => cur.map((it, k) => (k === i ? { ...it, state: 'sending', sent: 0 } : it)))
        try {
          await sendResumable(created.id, n, i, list[i].file, controller)
          setNote('')
          update(cur => cur.map((it, k) => (k === i ? { ...it, state: 'done', sent: it.file.size } : it)))
        } catch (e) {
          if (cancelled.current) return
          if (e instanceof UploadError && e.code === 'not_approved') { setPhase('expired'); return }
          update(cur => cur.map((it, k) => (k === i ? { ...it, state: 'failed', error: errorText(e) } : it)))
          if (e instanceof OfflineError || e instanceof AuthError) { setNote(e instanceof AuthError ? 'This device is no longer paired. Reload the page to ask again.' : errorText(e)); setPhase('error'); return }
        }
      }
      setPhase(itemsRef.current.some(it => it.state === 'failed') ? 'error' : 'done')
    } catch (e) {
      if (cancelled.current) return
      if (e instanceof UploadError && e.code === 'uploads_blocked') { setPhase('blocked'); return }
      setNote(e instanceof AuthError ? 'This device is no longer paired. Reload the page to ask again.' : errorText(e))
      setPhase('error')
    }
  }

  const stopAndBack = () => {
    cancelled.current = true
    abort.current?.abort()
    if (requestId.current) void api.uploadCancel(requestId.current)
    requestId.current = ''
    update(cur => cur.map(it => (it.state === 'done' ? it : { ...it, state: 'queued', sent: 0, error: undefined })))
    setPhase('choose')
  }

  const reset = () => { update(() => []); setNote(''); requestId.current = ''; setPhase('choose') }

  const remaining = items.filter(i => i.state !== 'done')
  const total = items.reduce((n, i) => n + i.file.size, 0)
  const sentTotal = items.reduce((n, i) => n + (i.state === 'done' ? i.file.size : i.sent), 0)
  const pct = total ? Math.round((sentTotal / total) * 100) : 0
  const secondsLeft = Math.max(0, Math.round((deadline - now) / 1000))
  const doneCount = items.filter(i => i.state === 'done').length
  const policyLine = info?.policy === 'always' ? 'This phone accepts files from this device without asking.' : 'The phone\u2019s owner will be asked to approve before anything is sent.'

  const dropProps = {
    onDragOver: (e: React.DragEvent) => { if (phase === 'choose') { e.preventDefault(); e.stopPropagation(); setOver(true) } },
    onDragLeave: () => setOver(false),
    onDrop: (e: React.DragEvent) => { e.preventDefault(); e.stopPropagation(); setOver(false); if (phase === 'choose') add(Array.from(e.dataTransfer.files)) }
  }

  return (
    <div className="overlay" onClick={onClose}>
      <section ref={dialog} tabIndex={-1} className="viewer nm-raised upload" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="Send files to this phone" {...dropProps}>
        <header className="viewer-bar"><UploadIcon /><strong>Send files to this phone</strong><span className="spacer" />
          <button className="icon-btn" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>

        <div className="upload-body">
          {phase === 'choose' && (
            <>
              <button type="button" className={'dropzone' + (over ? ' over' : '')} onClick={() => input.current?.click()} data-autofocus>
                <UploadIcon width={28} height={28} />
                <strong>Drop files here, or choose files</strong>
                <span className="muted small">{info ? `Up to ${info.maxFiles} files at a time.` : 'Connecting to the phone\u2026'}</span>
              </button>
              <input ref={input} type="file" multiple hidden onChange={e => { add(Array.from(e.target.files ?? [])); e.target.value = '' }} />
              {items.length > 0 && (
                <ul className="upload-list" aria-label="Files to send">
                  {items.map((it, i) => (
                    <li key={fileKey(it.file)}>
                      <span className="ellipsis">{it.file.name}</span>
                      <span className="muted small">{formatSize(it.file.size)}</span>
                      <button className="icon-btn sm" aria-label={`Remove ${it.file.name}`} onClick={() => update(cur => cur.filter((_, k) => k !== i))}><CloseIcon width={18} height={18} /></button>
                    </li>
                  ))}
                </ul>
              )}
              <p className="muted small">{policyLine}</p>
              {note && <p className="upload-note" role="status">{note}</p>}
              <div className="upload-actions">
                <span className="muted small">{items.length ? `${items.length} file${items.length === 1 ? '' : 's'} \u00b7 ${formatSize(total)}` : 'No files chosen'}</span>
                <span className="spacer" />
                <button className="nm-btn" onClick={onClose}>Cancel</button>
                <button className="nm-btn primary" disabled={!items.length} onClick={send}>Send</button>
              </div>
            </>
          )}

          {phase === 'waiting' && (
            <div className="upload-state" role="status" aria-live="polite">
              <Aubree state="wait" size={140} />
              <h3>Waiting for the phone</h3>
              <p className="muted">On the phone, open DropLink and choose <strong>Approve once</strong> or <strong>Always approve</strong>. This request expires in {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')}.</p>
              <div className="upload-actions"><span className="spacer" /><button className="nm-btn" onClick={stopAndBack}>Cancel request</button></div>
            </div>
          )}

          {(phase === 'sending' || phase === 'error') && (
            <>
              <div className="upload-total" role="status" aria-live="polite">
                <strong>{phase === 'sending' ? `Sending\u2026 ${pct}%` : `${doneCount} of ${items.length} sent`}</strong>
                <span className="bar" role="progressbar" aria-label="Overall progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><span style={{ width: pct + '%' }} /></span>
              </div>
              <ul className="upload-list" aria-label="Upload progress">
                {items.map(it => (
                  <li key={fileKey(it.file)} className={it.state}>
                    <span className="ellipsis">{it.file.name}</span>
                    <span className="muted small">{it.state === 'done' ? 'Sent' : it.state === 'failed' ? (it.error ?? 'Failed') : it.state === 'sending' ? `${Math.round((it.sent / Math.max(1, it.file.size)) * 100)}%` : 'Waiting'}</span>
                    <span className="bar" role="progressbar" aria-label={`Progress for ${it.file.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round((it.sent / Math.max(1, it.file.size)) * 100)}><span style={{ width: Math.round((it.sent / Math.max(1, it.file.size)) * 100) + '%' }} /></span>
                  </li>
                ))}
              </ul>
              {note && <p className="upload-note" role="alert">{note}</p>}
              <div className="upload-actions">
                <span className="spacer" />
                {phase === 'sending' ? <button className="nm-btn" onClick={stopAndBack}>Stop</button>
                  : <>
                    <button className="nm-btn" onClick={onClose}>Close</button>
                    {remaining.length > 0 && <button className="nm-btn primary" onClick={send}>Try again</button>}
                  </>}
              </div>
            </>
          )}

          {phase === 'done' && (
            <div className="upload-state" role="status">
              <Aubree state="celebrate" size={140} />
              <h3>{items.length === 1 ? 'File sent' : `${items.length} files sent`}</h3>
              <p className="muted">They are saved on the phone in Downloads/DropLink.</p>
              <div className="upload-actions"><span className="spacer" /><button className="nm-btn" onClick={reset}>Send more</button><button className="nm-btn primary" onClick={onClose}>Done</button></div>
            </div>
          )}

          {phase === 'denied' && (
            <div className="upload-state" role="status">
              <Aubree state="sad" size={140} />
              <h3>The phone said no</h3>
              <p className="muted">The files were not accepted. Nothing was sent.</p>
              <div className="upload-actions"><span className="spacer" /><button className="nm-btn" onClick={onClose}>Close</button><button className="nm-btn primary" onClick={stopAndBack}>Back</button></div>
            </div>
          )}

          {phase === 'expired' && (
            <div className="upload-state" role="status">
              <Aubree state="sleepy" size={140} />
              <h3>The request timed out</h3>
              <p className="muted">Nobody answered on the phone in time, or the approval ran out. Send the files again.</p>
              <div className="upload-actions"><span className="spacer" /><button className="nm-btn" onClick={onClose}>Close</button><button className="nm-btn primary" onClick={stopAndBack}>Back</button></div>
            </div>
          )}

          {phase === 'blocked' && (
            <div className="upload-state" role="status">
              <Aubree state="sad" size={140} />
              <h3>Sending is turned off</h3>
              <p className="muted">The phone does not accept files from this device. Its owner can change this in DropLink settings, under Trusted devices.</p>
              <div className="upload-actions"><span className="spacer" /><button className="nm-btn primary" onClick={onClose}>Close</button></div>
            </div>
          )}
        </div>
      </section>
    </div>
  )
}
