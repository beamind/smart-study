import { useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import type { DictationLanguage } from '../../data/schema';
import { errorText, Page, Sheet, useSettled } from '../../ui/components';
import { languageTitle, type Run } from './types';

/**
 * Mark the words written wrong and submit. Submitting is disabled while saving, so a double tap saves once.
 * Leaving without submitting asks first; the marks are kept in the draft.
 * `locked`: a submission failed in a way that cannot be checked in this run (the file is not written again until
 * the app is opened again, which shows whether the submission is in it). The marks stay as they are, the draft cannot
 * be discarded, and leaving is all that is left.
 */
export function MarkAnswers({ language, run, locked, onToggle, onSubmit, onLeave, onDiscard }: {
  language: DictationLanguage; run: Run; locked: boolean;
  onToggle(key: string): void; onSubmit(): Promise<void>; onLeave(): void; onDiscard(): void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [leaving, setLeaving] = useState(false);
  const wrong = new Set(run.wrong);
  const settled = useSettled();
  // Back is ignored while saving: leaving would hide a failed save. Locked, there is nothing left to do here.
  useBackHandler(true, () => { if (locked) onLeave(); else if (!busy) setLeaving(true); });

  const submit = async () => {
    setBusy(true);
    setError('');
    try { await onSubmit(); } catch (problem) { setError(`保存出错：${errorText(problem)}`); setBusy(false); }
  };

  return (
    <Page title={`${languageTitle(language)} · 对答案`}
      footer={locked
        ? <button className={`button primary big grow${settled ? '' : ' settling'}`} onClick={onLeave}>先离开</button>
        : <button className={`button primary big grow${settled ? '' : ' settling'}`} disabled={busy} onClick={() => void submit()}>
          {busy ? '正在保存…' : wrong.size ? `提交（写错 ${wrong.size} 个）` : '提交（全对）'}
        </button>}>
      <p className="muted small">{locked ? '保存没有完成，重新打开 App 之前不能再保存。重新打开后会检查这次记好了没有，所以先不能改对错。'
        : `${run.saveFailed ? '上次提交时没能确认保存，结果不在记录里，可以再提交一次。' : ''}点一下写错的词，再点一次取消。`}</p>
      {error && <div className="notice error">{error}</div>}
      <div className="answer-grid">
        {run.words.map(word => (
          <button key={word.key} className={`answer${wrong.has(word.key) ? ' wrong' : ''}`} aria-pressed={wrong.has(word.key)} disabled={busy || locked} onClick={() => onToggle(word.key)}>
            <b>{word.text}</b>{word.translation && <span className="muted small">{word.translation}</span>}
          </button>
        ))}
      </div>
      {leaving && (
        <Sheet title="还没有提交" onClose={() => setLeaving(false)}>
          <p className="muted">这次听写的结果还没有保存。</p>
          <button className="button primary" onClick={() => setLeaving(false)}>继续对答案</button>
          <button className="button" onClick={() => { setLeaving(false); onLeave(); }}>先离开，下次接着对答案</button>
          <button className="button danger" onClick={() => { setLeaving(false); onDiscard(); }}>放弃这次听写</button>
        </Sheet>
      )}
    </Page>
  );
}
