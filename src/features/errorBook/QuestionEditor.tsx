import { Trash2, Underline } from 'lucide-react';
import { useRef, useState } from 'react';
import { stripUnderlines } from '../../domain/underline';
import { errorText, Sheet } from '../../ui/components';
import { QuestionText } from './QuestionText';

/**
 * Edit one question and its answer (also used for manual entry). Underlines are
 * typed as __x__, or by selecting letters and pressing "划线".
 */
export function QuestionEditor({ title, text: initialText = '', answer: initialAnswer = '', saveLabel = '保存', onSave, onDelete, onClose }: {
  title: string; text?: string; answer?: string; saveLabel?: string;
  onSave(value: { text: string; answer: string }): Promise<void> | void; onDelete?(): void; onClose(): void;
}) {
  const [text, setText] = useState(initialText);
  const [answer, setAnswer] = useState(initialAnswer);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const area = useRef<HTMLTextAreaElement>(null);
  const hasMarks = stripUnderlines(text) !== text;

  const underline = () => {
    const element = area.current;
    if (!element) return;
    const { selectionStart: start, selectionEnd: end } = element;
    const selected = text.slice(start, end).trim();
    if (!selected) { setError('先在题目里选中要划线的字母或单词，再点"划线"。'); return; }
    const from = start + text.slice(start, end).indexOf(selected);
    const next = `${text.slice(0, from)}__${selected}__${text.slice(from + selected.length)}`;
    setText(next);
    setError('');
    requestAnimationFrame(() => { element.focus(); element.setSelectionRange(from + selected.length + 4, from + selected.length + 4); });
  };

  const save = async () => {
    setBusy(true);
    setError('');
    try { await onSave({ text, answer }); onClose(); } catch (problem) { setError(errorText(problem)); setBusy(false); }
  };

  return (
    <Sheet title={title} onClose={onClose} keepOnOutsideTap>
      <label className="field"><span>题目</span>
        <textarea ref={area} className="input question-input" value={text} autoFocus onChange={event => setText(event.target.value)} /></label>
      <div className="row">
        <button className="button no-shrink" onClick={underline}><Underline size={18} />划线</button>
        <span className="muted small grow">选中字母或单词后点"划线"，也可以直接输入 __a__。</span>
      </div>
      {hasMarks && <div className="card preview"><span className="muted small">显示效果</span><QuestionText text={text} /></div>}
      <label className="field"><span>正确答案</span>
        <input className="input" value={answer} onChange={event => setAnswer(event.target.value)} /></label>
      {error && <div className="notice error">{error}</div>}
      <div className="row">
        {onDelete && <button className="button danger" onClick={onDelete}><Trash2 size={18} />删除</button>}
        <span style={{ flex: 1 }} />
        <button className="button" onClick={onClose}>取消</button>
        <button className="button primary" disabled={!text.trim() || busy} onClick={() => void save()}>{saveLabel}</button>
      </div>
    </Sheet>
  );
}
