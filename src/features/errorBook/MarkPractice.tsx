import { useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { errorText, Page, Sheet, useSettled } from '../../ui/components';
import { QuestionText } from './QuestionText';
import { runTitle, type PracticeRun } from './types';

/**
 * Show the answers, mark the questions answered wrong and submit (disabled while saving).
 * Leaving without submitting asks first; the marks stay in the draft.
 */
export function MarkPractice({ run, onToggle, onSubmit, onLeave, onDiscard }: {
  run: PracticeRun; onToggle(key: string): void; onSubmit(): Promise<void>; onLeave(): void; onDiscard(): void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  /** A save failed; as in MarkAnswers, the marks then stay as they are and the texts do not claim "not saved". */
  const [failed, setFailed] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const wrong = new Set(run.wrong);
  const settled = useSettled();
  const variation = run.kind === 'variation';
  // Back is ignored while saving: leaving would hide a failed save.
  useBackHandler(true, () => { if (!busy) setLeaving(true); });

  const submit = async () => {
    setBusy(true);
    setError('');
    try { await onSubmit(); } catch (problem) { setError(`保存出错：${errorText(problem)}`); setFailed(true); setBusy(false); }
  };

  return (
    <Page title={`${runTitle(run.kind)} · 对答案`}
      footer={<button className={`button primary big grow${settled ? '' : ' settling'}`} disabled={busy} onClick={() => void submit()}>
        {busy ? '正在保存…' : failed ? '再试一次提交' : wrong.size ? `提交（做错 ${wrong.size} 题）` : '提交（全对）'}
      </button>}>
      <p className="muted small">{failed ? '保存出错后不能再改对错，以免和可能已经暂存的结果不一致。'
        : variation ? '点一下做错的题，再点一次取消。只有做错的新题会保存到错题本。' : '点一下仍然做错的题，再点一次取消。'}</p>
      {error && <div className="notice error">{error}</div>}
      <div className="list">
        {run.items.map((item, index) => (
          <button key={item.key} className={`list-row question-row${wrong.has(item.key) ? ' wrong-row' : ''}`} aria-pressed={wrong.has(item.key)} disabled={busy || failed} onClick={() => onToggle(item.key)}>
            <span className="index">{index + 1}</span>
            <div className="grow">
              <QuestionText text={item.text} />
              <div className="answer-line">答案：{item.answer || <span className="muted">（没有答案）</span>}</div>
            </div>
            {wrong.has(item.key) && <span className="score bad">做错</span>}
          </button>
        ))}
      </div>
      {leaving && (
        <Sheet title="还没有提交" onClose={() => setLeaving(false)}>
          <p className="muted">{failed
            ? '保存出错了。如果提示说会用临时文件恢复，重新打开 App 后这次的结果会自动恢复；否则下次回来可以再提交。'
            : `这次${runTitle(run.kind)}的结果还没有保存。`}</p>
          <button className="button primary" onClick={() => setLeaving(false)}>继续对答案</button>
          <button className="button" onClick={() => { setLeaving(false); onLeave(); }}>先离开，下次接着对答案</button>
          {!failed && <button className="button danger" onClick={() => { setLeaving(false); onDiscard(); }}>放弃这次{runTitle(run.kind)}</button>}
        </Sheet>
      )}
    </Page>
  );
}
