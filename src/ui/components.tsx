import { ArrowLeft } from 'lucide-react';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useBackHandler, useNavigation } from '../app/navigation';
import { SaveInterruptedError } from '../storage/jsonFiles';

/** How long a new page footer or sheet ignores taps: longer than a double tap, too short to notice. */
const SETTLE_MS = 400;

/**
 * False for a moment after the screen appears, and again whenever `key` changes (e.g. the next word). A button that
 * replaces another in the same place (写完了 → 提交, 下一个 → 下一个) ignores taps meanwhile (class "settling"), so the
 * second tap of a double tap does not press it. Page footers and sheets do this by themselves (SETTLE_MS).
 */
export function useSettled(key: unknown = null, ms = 600): boolean {
  const [settledFor, setSettledFor] = useState<{ key: unknown } | null>(null);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettledFor({ key }), ms);
    return () => window.clearTimeout(timer);
  }, [key, ms]);
  return settledFor !== null && Object.is(settledFor.key, key);
}

export function Page({ title, actions, footer, children, back = true }: {
  title: ReactNode; actions?: ReactNode; footer?: ReactNode; children: ReactNode; back?: boolean;
}) {
  const navigation = useNavigation();
  // The footer's buttons often sit where the button that opened this page was (开始 / 下一个, 写完了 / 提交); the back
  // button sits where the home page's name button was, so a double tap there must not close the page it opened.
  const settled = useSettled(null, SETTLE_MS);
  return (
    <div className="page">
      <header className="page-header">
        {back && navigation.depth > 1
          ? <button className={`icon-button${settled ? '' : ' settling'}`} aria-label="返回" onClick={() => navigation.back()}><ArrowLeft size={24} /></button>
          : <span className="header-spacer" />}
        <h1>{title}</h1>
        {actions}
      </header>
      <main className="page-body"><div className="page-inner">{children}</div></main>
      {footer && <footer className={`page-footer${settled ? '' : ' settling'}`}><div>{footer}</div></footer>}
    </div>
  );
}

/**
 * Bottom sheet on phones, centred dialog on wider screens. Back and tapping outside close it; sheets for typing
 * (keepOnOutsideTap) close only by back or their own buttons, because a tap outside to hide the keyboard would lose the text.
 */
export function Sheet({ title, onClose, keepOnOutsideTap = false, children }: { title: ReactNode; onClose(): void; keepOnOutsideTap?: boolean; children: ReactNode }) {
  useBackHandler(true, onClose);
  // A sheet's buttons often sit where the button that opened it was; the second tap of a double tap must not press them.
  const settled = useSettled(null, SETTLE_MS);
  return (
    <div className="backdrop" onClick={keepOnOutsideTap || !settled ? undefined : onClose}>
      <div className={`sheet${settled ? '' : ' settling'}`} role="dialog" aria-modal="true" aria-label={typeof title === 'string' ? title : undefined} onClick={event => event.stopPropagation()}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/** Destructive actions ask for a small sum, so a child tapping around cannot delete data by accident. */
export function ArithmeticConfirm({ title, message, confirmLabel, onConfirm, onClose }: {
  title: string; message: ReactNode; confirmLabel: string; onConfirm(): Promise<void> | void; onClose(): void;
}) {
  const [question] = useState(() => {
    // Both the numbers and the result stay within 10.
    const a = 2 + Math.floor(Math.random() * 7);
    if (Math.random() < 0.5) {
      const b = 1 + Math.floor(Math.random() * (10 - a));
      return { text: `${a} + ${b} = ?`, answer: a + b };
    }
    const b = 1 + Math.floor(Math.random() * (a - 1));
    return { text: `${a} - ${b} = ?`, answer: a - b };
  });
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const correct = Number(value) === question.answer && value.trim() !== '';
  return (
    <Sheet title={title} onClose={onClose}>
      <div>{message}</div>
      <label className="field">
        <span>请计算后确认：{question.text}</span>
        <input className="input" inputMode="numeric" autoFocus value={value} onChange={event => setValue(event.target.value)} />
      </label>
      {error && <div className="notice error">{error}</div>}
      <div className="row">
        <button className="button grow" onClick={onClose}>取消</button>
        <button className="button danger solid grow" disabled={!correct || busy} onClick={async () => {
          setBusy(true);
          try { await onConfirm(); onClose(); } catch (problem) { setError((problem as Error).message); setBusy(false); }
        }}>{confirmLabel}</button>
      </div>
    </Sheet>
  );
}

// ---------- toast with an optional action (used for "撤销") ----------

interface ToastMessage { id: number; text: string; action?: { label: string; run(): void } }
interface Toasts {
  show(text: string, action?: ToastMessage['action'], durationMs?: number): void;
  /** Remove the current toast, e.g. when a full-screen run starts and its buttons would be covered. */
  hide(): void;
}
const ToastContext = createContext<Toasts>({ show: () => undefined, hide: () => undefined });
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<ToastMessage | null>(null);
  const timer = useRef<number>(undefined);
  const show = useCallback<Toasts['show']>((text, action, durationMs = action ? 8000 : 3000) => {
    window.clearTimeout(timer.current);
    const id = Date.now();
    setMessage({ id, text, action });
    timer.current = window.setTimeout(() => setMessage(current => current?.id === id ? null : current), durationMs);
  }, []);
  const hide = useCallback(() => { window.clearTimeout(timer.current); setMessage(null); }, []);
  const value = useMemo(() => ({ show, hide }), [show, hide]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {message && (
        <div className="toast" role="status">
          <span>{message.text}</span>
          {message.action && <button onClick={() => { message.action!.run(); setMessage(null); }}>{message.action.label}</button>}
        </div>
      )}
    </ToastContext.Provider>
  );
}

export const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * The toast for a change whose save failed. An interrupted save is kept (DataCache builds on it, and the next save
 * or start writes it), so its own message ("已记下，但…") is shown without "没有保存".
 */
export const notSavedText = (error: unknown) => (error instanceof SaveInterruptedError ? errorText(error) : `没有保存：${errorText(error)}`);
