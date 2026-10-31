import { Languages, Plus, RefreshCw, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useWorkspace } from '../../app/workspace';
import { wordKey } from '../../data/normalize';
import type { DictationFile, DictationLanguage } from '../../data/schema';
import { wordIndex } from '../../domain/dictation';
import { Page } from '../../ui/components';
import { languageTitle, planWord, type Plan } from './types';

/**
 * Check the word list before dictating: fix or delete misrecognised words, add missing
 * ones and, for English, choose what is read aloud. Missing translations are looked up in the word book
 * first and only then requested from the model, a few at a time.
 */
export function ConfirmWords({ language, plan, file, onChange, onStart }: {
  language: DictationLanguage; plan: Plan; file?: DictationFile; onChange(plan: Plan): void; onStart(plan: Plan): void;
}) {
  const { ai } = useWorkspace();
  const [adding, setAdding] = useState('');
  const [failed, setFailed] = useState<Set<string>>(new Set());
  /** Word key → the text being translated. At most one request per word is in flight. */
  const [pending, setPending] = useState<Map<string, string>>(new Map());
  /** Words already sent for translation; a ref so re-running the effect never sends a word twice. */
  const requested = useRef(new Set<string>());
  const latest = useRef(plan);
  latest.current = plan;
  // Translations still running when the page is left are cancelled; a late reply must not bring this word list back.
  const leaving = useRef(new AbortController());
  useEffect(() => {
    const controller = new AbortController();
    leaving.current = controller;
    return () => controller.abort();
  }, []);
  const known = useMemo(() => (file ? wordIndex(file) : new Map()), [file]);
  const english = language === 'english';
  const needsTranslation = english && plan.readMode === 'translation';

  // Update the ref right away: several translations can arrive before React re-renders.
  const setWords = (words: Plan['words']) => { latest.current = { ...latest.current, words }; onChange(latest.current); };
  const update = (key: string, change: Partial<Plan['words'][number]>) =>
    setWords(latest.current.words.map(word => (word.key === key ? { ...word, ...change } : word)));

  // Fill translations: word book first, then the model (3 requests at a time). Waits until typing pauses,
  // so editing a word does not send a request per keystroke.
  useEffect(() => {
    if (!needsTranslation) return;
    const timer = window.setTimeout(() => {
      const missing = latest.current.words.filter(word => word.text.trim() && !word.translation.trim() && !requested.current.has(`${word.key}:${word.text}`) && !failed.has(word.key));
      if (!missing.length) return;
      const fromBook = missing.map(word => [word, known.get(wordKey(word.text, language))?.translation] as const).filter(([, translation]) => translation);
      if (fromBook.length) {
        const found = new Map(fromBook.map(([word, translation]) => [word.key, translation!]));
        setWords(latest.current.words.map(word => (found.has(word.key) ? { ...word, translation: found.get(word.key)! } : word)));
        return;
      }
      const batch = missing.filter(word => !pending.has(word.key)).slice(0, Math.max(0, 3 - pending.size));
      if (!batch.length) return;
      batch.forEach(word => requested.current.add(`${word.key}:${word.text}`));
      setPending(current => new Map([...current, ...batch.map(word => [word.key, word.text] as const)]));
      for (const word of batch) {
        const { signal } = leaving.current;
        ai.translate(word.text.trim(), signal)
          .then(translation => {
            if (signal.aborted) return;
            const now = latest.current.words.find(item => item.key === word.key);
            if (now && now.text === word.text && !now.translation.trim()) update(word.key, { translation });
          })
          .catch(() => {
            if (signal.aborted) return;
            requested.current.delete(`${word.key}:${word.text}`);
            // Only a failure for the text still in the field counts; the word may have been edited meanwhile.
            if (latest.current.words.find(item => item.key === word.key)?.text === word.text) setFailed(current => new Set(current).add(word.key));
          })
          .finally(() => !signal.aborted && setPending(current => {
            if (current.get(word.key) !== word.text) return current;
            const next = new Map(current);
            next.delete(word.key);
            return next;
          }));
      }
    }, 500);
    return () => window.clearTimeout(timer);
  }, [plan.words, needsTranslation, pending, failed, known]);

  // The same word twice is read once (same key as when the run starts), so the count on the button is right.
  const repeated: string[] = [];
  const seen = new Set<string>();
  const words = plan.words.filter(word => {
    const text = word.text.trim().replace(/\s+/g, ' ');
    if (!text) return false;
    const key = wordKey(text, language);
    if (seen.has(key)) { repeated.push(text); return false; }
    seen.add(key);
    return true;
  });
  /** Not translated yet, but a request is pending or about to start once typing pauses. */
  const waiting = (word: Plan['words'][number]) => pending.has(word.key) || (!requested.current.has(`${word.key}:${word.text}`) && !failed.has(word.key));
  const translating = needsTranslation ? words.filter(word => !word.translation.trim() && waiting(word)).length : 0;
  const untranslated = needsTranslation ? words.filter(word => !word.translation.trim() && !waiting(word)).length : 0;
  const add = () => {
    const parts = adding.split(/[\n，,、；;]/).map(item => item.trim()).filter(Boolean);
    if (!parts.length) return;
    setWords([...latest.current.words, ...parts.map(text => planWord(text))]);
    setAdding('');
  };

  return (
    <Page title="确认词语"
      footer={<button className="button primary big grow" disabled={!words.length || translating > 0} onClick={() => onStart({ ...plan, words })}>
        {translating ? `正在翻译…` : `开始${languageTitle(language)}（${words.length} 个）`}
      </button>}>
      <p className="muted small">识别结果可能有错。改正或删掉不对的词，确认后才开始；顺序会自动打乱。</p>
      {english && (
        <div className="segmented">
          <button className={plan.readMode === 'translation' ? 'selected' : ''} onClick={() => onChange({ ...plan, readMode: 'translation' })}>读中文，写英文</button>
          <button className={plan.readMode === 'original' ? 'selected' : ''} onClick={() => onChange({ ...plan, readMode: 'original' })}>读英文，写英文</button>
        </div>
      )}
      {untranslated > 0 && (
        <div className="notice row">
          <span className="grow">{untranslated} 个词没有译文，听写时会读英文。可以手动填写。</span>
          {failed.size > 0 && <button className="button" onClick={() => setFailed(new Set())}><RefreshCw size={18} />重新翻译</button>}
        </div>
      )}
      {repeated.length > 0 && <p className="muted small">重复的词只读一次：{[...new Set(repeated)].join('、')}</p>}
      <div className="list">
        {plan.words.map((word, index) => (
          <div key={word.key} className="list-row confirm-row">
            <span className="index">{index + 1}</span>
            <input className="input grow" aria-label={`第 ${index + 1} 个词`} value={word.text}
              onChange={event => {
                const text = event.target.value;
                // Typing a word back to a text translated before must ask again: its old result was cleared.
                requested.current.delete(`${word.key}:${text}`);
                update(word.key, { text, translation: english ? '' : word.translation });
                setFailed(current => { const next = new Set(current); next.delete(word.key); return next; });
              }} />
            {english && (
              <input className="input grow" aria-label={`第 ${index + 1} 个词的译文`} placeholder={pending.has(word.key) ? '翻译中…' : '译文'}
                value={word.translation} onChange={event => update(word.key, { translation: event.target.value })} />
            )}
            <button className="icon-button" aria-label={`删除 ${word.text}`} onClick={() => setWords(latest.current.words.filter(item => item.key !== word.key))}><X size={20} /></button>
          </div>
        ))}
        <div className="list-row">
          <Plus size={20} className="muted" />
          <input className="input grow" placeholder="补充词语，回车添加（可用逗号分隔多个）" value={adding}
            onChange={event => setAdding(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') add(); }} />
          <button className="button" disabled={!adding.trim()} onClick={add}>添加</button>
        </div>
      </div>
      {english && plan.readMode === 'original' && <p className="muted small"><Languages size={16} style={{ verticalAlign: -3 }} /> 读英文时不需要译文；对答案时有译文会一起显示。</p>}
    </Page>
  );
}
