import { Pencil, Plus, Underline } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import { AiError } from '../../ai/dashscope';
import type { Question } from '../../data/schema';
import { findDuplicate } from '../../domain/errorBook';
import { errorText, Page, Sheet } from '../../ui/components';
import { formatTime } from '../../ui/format';
import { QuestionEditor } from './QuestionEditor';
import { QuestionText } from './QuestionText';
import { newKey, type CaptureItem } from './types';

/**
 * Check recognised or typed questions before saving: edit, untick, add, recognise underlines.
 * Questions already in the book are unticked and labelled. Saving is one write and is
 * disabled while it runs, together with every other control on the page.
 */
export function CaptureQuestions({ image, items, existing, startEditing, onChange, onSave, onDiscard }: {
  image?: string; items: CaptureItem[]; existing: Question[]; startEditing?: boolean;
  onChange(items: CaptureItem[]): void; onSave(items: CaptureItem[]): Promise<void>; onDiscard(): void;
}) {
  const { ai } = useWorkspace();
  const [editing, setEditing] = useState<CaptureItem | 'new' | null>(startEditing ? 'new' : null);
  const [underline, setUnderline] = useState<{ busy: boolean; message?: string; error?: boolean }>({ busy: false });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [leaving, setLeaving] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const latest = useRef(items);
  latest.current = items;
  // Leaving the page by any way (also after saving) stops a pending underline request.
  useEffect(() => () => abort.current?.abort(), []);

  const marks = useMemo(() => new Map(items.map(item => [item.key, findDuplicate(item.text, existing)])), [items, existing]);
  // Duplicates are unticked until the user ticks them; others are ticked until unticked.
  const ticked = (item: CaptureItem) => (item.touched ? item.checked : !marks.get(item.key));
  const chosen = items.filter(item => item.text.trim() && ticked(item));

  const set = (next: CaptureItem[]) => { latest.current = next; onChange(next); };
  const change = (key: string, value: Partial<CaptureItem>) => set(latest.current.map(item => (item.key === key ? { ...item, ...value } : item)));

  const findUnderlines = async () => {
    if (!image) return;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setUnderline({ busy: true });
    try {
      const current = latest.current;
      const result = await ai.recognizeUnderlines(image, current.map(item => ({ text: item.text, answer: item.answer })), controller.signal);
      if (controller.signal.aborted) return;
      // Only questions still unchanged get the marks; an edit made while waiting is kept.
      const before = new Map(current.map((item, index) => [item.key, { text: item.text, marked: result.texts[index] }]));
      let edited = 0;
      set(latest.current.map(item => {
        const was = before.get(item.key);
        if (!was) return item;
        if (item.text !== was.text) { edited++; return item; }
        return { ...item, text: was.marked };
      }));
      const missed = result.missed ? `，另有 ${result.missed} 处没能在题目里找到` : '';
      const skipped = edited ? `；${edited} 道题在识别期间改过，没有改动` : '';
      setUnderline({ busy: false, message: result.marks ? `标出了 ${result.marks} 处划线${missed}${skipped}。请对照照片检查。` : `没有找到划线${missed}${skipped}。` });
    } catch (problem) {
      if (controller.signal.aborted || (problem instanceof AiError && problem.kind === 'cancelled')) return;
      setUnderline({ busy: false, error: true, message: `识别划线失败：${errorText(problem)}` });
    }
  };

  const save = async () => {
    setBusy(true);
    setError('');
    try { await onSave(chosen); } catch (problem) { setError(`没有保存成功：${errorText(problem)}`); setBusy(false); }
  };

  // No editing while the underline request runs: an editor opened on the old text would wipe the marks when saved.
  const locked = busy || underline.busy;

  const leave = () => { abort.current?.abort(); onDiscard(); };
  // Back is ignored while saving: leaving would hide a failed save and lose the questions.
  useBackHandler(!editing, () => { if (!busy) { if (items.length) setLeaving(true); else leave(); } });

  return (
    <Page title="确认错题"
      footer={<button className="button primary big grow" disabled={!chosen.length || busy || underline.busy} onClick={() => void save()}>
        {busy ? '正在保存…' : underline.busy ? '正在识别划线…' : `保存 ${chosen.length} 道错题`}
      </button>}>
      {image && <p className="muted small">答案由千问给出，不是照片上孩子写的答案。识别可能有错，点题目可以修改。已在错题本里的题默认不勾选。</p>}
      <div className="row wrap">
        {image && (
          <button className="button" disabled={busy || underline.busy || !items.length} onClick={() => void findUnderlines()}>
            <Underline size={18} />{underline.busy ? '正在识别划线…' : '识别划线'}
          </button>
        )}
        <button className="button" disabled={locked} onClick={() => setEditing('new')}><Plus size={18} />手动添加一道</button>
      </div>
      {underline.message && <div className={`notice${underline.error ? ' error' : ''}`}>{underline.message}</div>}
      {error && <div className="notice error">{error}</div>}
      {!items.length && <p className="muted small">还没有题目。点"手动添加一道"输入题目和答案。</p>}
      {!!items.length && (
        <div className="list">
          {items.map((item, index) => {
            const mark = marks.get(item.key);
            return (
              <div key={item.key} className="list-row question-row">
                <label className="check-area">
                  <input type="checkbox" className="check" aria-label={`保存第 ${index + 1} 题`} checked={ticked(item)} disabled={busy}
                    onChange={() => change(item.key, { checked: !ticked(item), touched: true })} />
                </label>
                <button className="grow question-button" disabled={locked} onClick={() => setEditing(item)}>
                  {mark && <span className="dup">{mark.kind === 'same' ? '已在错题本' : '可能重复'}（{formatTime(mark.question.createdAt)} 录入）</span>}
                  <QuestionText text={item.text} />
                  <div className="answer-line">答案：{item.answer || <span className="muted">（没有答案）</span>}</div>
                </button>
                <button className="icon-button" aria-label={`修改第 ${index + 1} 题`} disabled={locked} onClick={() => setEditing(item)}><Pencil size={18} /></button>
              </div>
            );
          })}
        </div>
      )}

      {editing && (
        <QuestionEditor title={editing === 'new' ? '手动添加错题' : '修改题目'} saveLabel={editing === 'new' ? '添加' : '确定'}
          text={editing === 'new' ? '' : editing.text} answer={editing === 'new' ? '' : editing.answer}
          onSave={value => {
            if (editing === 'new') set([...latest.current, { key: newKey(), ...value, checked: true, origin: 'manual' }]);
            else change(editing.key, value);
          }}
          onDelete={editing === 'new' ? undefined : () => { set(latest.current.filter(item => item.key !== editing.key)); setEditing(null); }}
          onClose={() => setEditing(null)} />
      )}
      {leaving && (
        <Sheet title="放弃这些题目？" onClose={() => setLeaving(false)}>
          <p className="muted">返回后，这次识别或输入的题目不会保存。</p>
          <div className="row">
            <button className="button grow" onClick={() => setLeaving(false)}>继续确认</button>
            <button className="button danger grow" onClick={() => { setLeaving(false); leave(); }}>放弃</button>
          </div>
        </Sheet>
      )}
    </Page>
  );
}
