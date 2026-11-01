import { useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import type { DictationLanguage } from '../../data/schema';
import { errorText, Page, Sheet, useSettled } from '../../ui/components';
import { languageTitle, type Run } from './types';

/**
 * Mark the words written wrong and submit. Submitting is disabled while saving, so a double tap saves once.
 * Leaving without submitting asks first; the marks are kept in the draft.
 */
export function MarkAnswers({ language, run, onToggle, onSubmit, onLeave, onDiscard }: {
  language: DictationLanguage; run: Run;
  onToggle(key: string): void; onSubmit(): Promise<void>; onLeave(): void; onDiscard(): void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  /**
   * A save failed. It may still come back: an interrupted save is restored from its temp file when the app is opened
   * again, with the marks as they were then. So the marks stay as they are, and the texts do not claim "not saved".
   */
  const [failed, setFailed] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const wrong = new Set(run.wrong);
  const settled = useSettled();
  // Back is ignored while saving: leaving would hide a failed save.
  useBackHandler(true, () => { if (!busy) setLeaving(true); });

  const submit = async () => {
    setBusy(true);
    setError('');
    try { await onSubmit(); } catch (problem) { setError(`保存出错：${errorText(problem)}`); setFailed(true); setBusy(false); }
  };

  return (
    <Page title={`${languageTitle(language)} · 对答案`}
      footer={<button className={`button primary big grow${settled ? '' : ' settling'}`} disabled={busy} onClick={() => void submit()}>
        {busy ? '正在保存…' : failed ? '再试一次提交' : wrong.size ? `提交（写错 ${wrong.size} 个）` : '提交（全对）'}
      </button>}>
      <p className="muted small">{failed ? '保存出错后不能再改对错，以免和可能已经暂存的结果不一致。' : '点一下写错的词，再点一次取消。'}</p>
      {error && <div className="notice error">{error}</div>}
      <div className="answer-grid">
        {run.words.map(word => (
          <button key={word.key} className={`answer${wrong.has(word.key) ? ' wrong' : ''}`} aria-pressed={wrong.has(word.key)} disabled={busy || failed} onClick={() => onToggle(word.key)}>
            <b>{word.text}</b>{word.translation && <span className="muted small">{word.translation}</span>}
          </button>
        ))}
      </div>
      {leaving && (
        <Sheet title="还没有提交" onClose={() => setLeaving(false)}>
          <p className="muted">{failed
            ? '保存出错了。如果提示说会用临时文件恢复，重新打开 App 后这次的结果会自动恢复；否则下次回来可以再提交。'
            : '这次听写的结果还没有保存。'}</p>
          <button className="button primary" onClick={() => setLeaving(false)}>继续对答案</button>
          <button className="button" onClick={() => { setLeaving(false); onLeave(); }}>先离开，下次接着对答案</button>
          {!failed && <button className="button danger" onClick={() => { setLeaving(false); onDiscard(); }}>放弃这次听写</button>}
        </Sheet>
      )}
    </Page>
  );
}
