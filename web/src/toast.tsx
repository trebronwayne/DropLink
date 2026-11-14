import { createContext, useCallback, useContext, useMemo, useRef, useState, ReactNode } from 'react'

type Tone = 'info' | 'error'
type Toast = { id: number; text: string; tone: Tone }
type Ctx = { show: (text: string, tone?: Tone) => void }

const ToastCtx = createContext<Ctx>({ show: () => undefined })
export const useToast = () => useContext(ToastCtx)

/** Short confirmations ("Added to queue"). They are also announced to screen readers. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([])
  const seq = useRef(0)
  const show = useCallback((text: string, tone: Tone = 'info') => {
    const id = ++seq.current
    setItems(cur => [...cur.slice(-2), { id, text, tone }])
    window.setTimeout(() => setItems(cur => cur.filter(t => t.id !== id)), 3400)
  }, [])
  const ctx = useMemo(() => ({ show }), [show])
  return (
    <ToastCtx.Provider value={ctx}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map(t => <div key={t.id} className={'toast' + (t.tone === 'error' ? ' error' : '')}>{t.text}</div>)}
      </div>
    </ToastCtx.Provider>
  )
}
