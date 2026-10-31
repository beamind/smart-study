import { Pause, Play } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import { useKeepAwake, useOnHidden } from '../../services/device';
import { Page, Sheet, useSettled, useToast } from '../../ui/components';
import { QuestionText } from './QuestionText';
import { runTitle, type PracticeRun } from './types';

/**
 * One question at a time with a countdown (settings: 单题限时, 0 = no limit). "下一题" or the
 * countdown reaching zero moves on. Pausing, or the app going to the background, stops the countdown.
 */
export function RunPractice({ run, onIndex, onFinish, onQuit }: {
  run: PracticeRun; onIndex(index: number): void; onFinish(doneCount: number): void; onQuit(): void;
}) {
  const { settings } = useWorkspace();
  const limit = settings.timeLimitSeconds.errorBook;
  const index = run.index;
  const [left, setLeft] = useState(limit);
  const [paused, setPaused] = useState(false);
  const [leaving, setLeaving] = useState(false);
  useKeepAwake(!paused);
  // A toast left from the page before would cover the run screen's buttons.
  const { hide } = useToast();
  useEffect(() => { hide(); }, [hide]);
  const settled = useSettled(index);

  useEffect(() => { setLeft(limit); }, [index, limit]);
  useEffect(() => {
    if (paused || !limit) return;
    const timer = window.setInterval(() => setLeft(value => value - 1), 1000);
    return () => window.clearInterval(timer);
  }, [paused, index, limit]);
  useEffect(() => { if (limit && left <= 0 && !paused) next(); }, [left]);

  const last = index + 1 >= run.items.length;
  const next = () => (last ? onFinish(run.items.length) : onIndex(index + 1));
  const pause = useCallback(() => setPaused(true), []);
  useOnHidden(pause);
  useBackHandler(true, () => { pause(); setLeaving(true); });

  return (
    <Page title={runTitle(run.kind)} footer={
      <div className="practice-actions">
        <button className="button big" onClick={() => setPaused(!paused)}>{paused ? <><Play size={22} />继续</> : <><Pause size={22} />暂停</>}</button>
        <button className={`button primary big run-next${settled ? '' : ' settling'}`} disabled={paused} onClick={next}>{last ? '做完了，对答案' : '下一题'}</button>
      </div>
    }>
      <div className="run-progress">第 <b>{index + 1}</b> / {run.items.length} 题</div>
      {limit > 0 && (
        <div className="timer centered" aria-label={`剩余 ${left} 秒`}>
          <div className="timer-bar" style={{ width: `${(Math.max(0, left) / limit) * 100}%` }} />
          <span>{left} 秒</span>
        </div>
      )}
      <div className={`card practice-card${paused ? ' paused' : ''}`}>
        {paused ? <p className="muted">已暂停，点"继续"接着做。</p> : <QuestionText text={run.items[index].text} />}
      </div>
      {leaving && (
        <Sheet title="要结束这次练习吗？" onClose={() => setLeaving(false)}>
          <p className="muted">已经做到第 {index + 1} / {run.items.length} 题。</p>
          <button className="button primary big" onClick={() => { setLeaving(false); setPaused(false); }}>继续练习</button>
          <button className="button big" onClick={() => { setLeaving(false); onFinish(index + 1); }}>到这里为止，去对答案</button>
          <button className="button big" onClick={() => { setLeaving(false); onQuit(); }}>先离开，下次接着做</button>
        </Sheet>
      )}
    </Page>
  );
}
