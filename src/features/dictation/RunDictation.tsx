import { Pause, Play, RotateCcw, Volume2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import type { DictationLanguage } from '../../data/schema';
import { useKeepAwake, useOnHidden } from '../../services/device';
import { speechEngine, WordReader } from '../../services/tts';
import { errorText, Page, Sheet, useSettled, useToast } from '../../ui/components';
import { languageTitle, type Run } from './types';

/**
 * Reading the words one by one: each word twice with a gap, then the countdown runs while the child
 * writes. "下一个" moves on, the countdown moving to zero does too. Pausing (or the app going to the
 * background) stops both reading and the countdown.
 */
export function RunDictation({ language, run, onIndex, onFinish, onQuit }: {
  language: DictationLanguage; run: Run; onIndex(index: number): void; onFinish(readCount: number): void; onQuit(): void;
}) {
  const { settings } = useWorkspace();
  const limit = settings.timeLimitSeconds.dictation;
  const index = run.index;
  const [left, setLeft] = useState(limit);
  const [paused, setPaused] = useState(false);
  const [speaking, setSpeaking] = useState(true);
  const [problem, setProblem] = useState<string | null>(null);
  const [leaving, setLeaving] = useState(false);
  const reader = useMemo(() => new WordReader(speechEngine()), []);
  useKeepAwake(!paused);
  // A toast left from the page before would cover the run screen's buttons.
  const { hide } = useToast();
  useEffect(() => { hide(); }, [hide]);
  const settled = useSettled(index);

  const word = run.words[index];
  const useTranslation = language === 'english' && run.readMode === 'translation' && word.translation.trim();
  const spoken = useTranslation
    ? { text: word.translation, lang: 'zh-CN' as const, what: '中文' }
    : { text: word.text, lang: language === 'english' ? 'en-US' as const : 'zh-CN' as const, what: language === 'english' ? (run.readMode === 'translation' ? '英文（没有译文）' : '英文') : '词语' };

  const say = useCallback((times: number) => {
    setSpeaking(true);
    reader.read(spoken.text, spoken.lang, { rate: settings.tts.rate, pitch: settings.tts.pitch, gapMs: settings.tts.repeatGapMs[language], times })
      .then(finished => { if (finished) setSpeaking(false); })
      .catch(error => { setSpeaking(false); setProblem(`朗读失败：${errorText(error)}`); });
  }, [reader, spoken.text, spoken.lang, settings.tts, language]);

  useEffect(() => { void speechEngine().check(spoken.lang).then(setProblem); }, [spoken.lang]);
  useEffect(() => { if (paused) return; setLeft(limit); say(2); }, [index]);
  useEffect(() => () => { void reader.cancel(); }, [reader]);
  useEffect(() => {
    if (paused || speaking || !limit) return;
    const timer = window.setInterval(() => setLeft(value => value - 1), 1000);
    return () => window.clearInterval(timer);
  }, [paused, speaking, index, limit]);
  useEffect(() => { if (limit && left <= 0 && !paused) next(); }, [left]);

  const next = () => {
    void reader.cancel();
    if (index + 1 >= run.words.length) onFinish(run.words.length);
    else onIndex(index + 1);
  };
  const pause = useCallback(() => {
    void reader.cancel();
    setSpeaking(false);
    setPaused(true);
  }, [reader]);
  const resume = () => { setPaused(false); say(1); };

  useOnHidden(pause);
  useBackHandler(true, () => { pause(); setLeaving(true); });

  return (
    <Page title={languageTitle(language)} footer={
      <div className="run-actions">
        <button className="button big" onClick={paused ? resume : pause}>{paused ? <><Play size={22} />继续</> : <><Pause size={22} />暂停</>}</button>
        <button className="button big" disabled={paused} onClick={() => say(1)}><RotateCcw size={22} />再读一遍</button>
        <button className={`button primary big run-next${settled ? '' : ' settling'}`} disabled={paused} onClick={next}>{index + 1 >= run.words.length ? '写完了' : '下一个'}</button>
      </div>
    }>
      {problem && <div className="notice">{problem}</div>}
      <div className="run">
        <div className="run-progress">第 <b>{index + 1}</b> / {run.words.length} 个</div>
        <div className="run-status">{paused ? '已暂停，点"继续"接着听写' : speaking ? <><Volume2 size={22} />正在朗读{spoken.what}…</> : '请写下来'}</div>
        {limit > 0 && (
          <div className="timer" role="timer" aria-label={`剩余 ${left} 秒`}>
            <div className="timer-bar" style={{ width: `${(Math.max(0, left) / limit) * 100}%` }} />
            <span>{left} 秒</span>
          </div>
        )}
      </div>
      {leaving && (
        <Sheet title="要结束这次听写吗？" onClose={() => setLeaving(false)}>
          <p className="muted">已经读了 {index + 1} / {run.words.length} 个。</p>
          <button className="button primary big" onClick={() => { setLeaving(false); resume(); }}>继续听写</button>
          <button className="button big" onClick={() => { setLeaving(false); onFinish(index + 1); }}>到这里为止，去对答案</button>
          <button className="button big" onClick={() => { setLeaving(false); onQuit(); }}>先离开，下次接着听写</button>
        </Sheet>
      )}
    </Page>
  );
}
