import { BookMarked, Camera, ChevronRight, Image, Keyboard, RotateCcw, Undo2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useBackHandler, useNavigation, type Replay } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import { AiError } from '../../ai/dashscope';
import { nowIso } from '../../core/time';
import { wordKey } from '../../data/normalize';
import type { DictationFile, DictationLanguage } from '../../data/schema';
import { mayBeRestored, SaveInterruptedError } from '../../storage/jsonFiles';
import { newSessionId, submit, undoSession, wordRows } from '../../domain/dictation';
import { sample, shuffle } from '../../domain/sampling';
import { drafts } from '../../services/device';
import { pickPhoto, type PhotoSource } from '../../services/photo';
import { ArithmeticConfirm, errorText, Page, Sheet, useSettled, useToast } from '../../ui/components';
import { formatRelative } from '../../ui/format';
import { useDictationFile } from '../useUserData';
import { ConfirmWords } from './ConfirmWords';
import { CropPhoto } from './CropPhoto';
import { MarkAnswers } from './MarkAnswers';
import { RunDictation } from './RunDictation';
import { draftKey, languageTitle, planWord, splitWords, type Plan, type Run } from './types';

type Step =
  | { kind: 'home' }
  | { kind: 'crop'; image: string }
  | { kind: 'recognizing'; image: string; error?: string }
  | { kind: 'confirm' }
  | { kind: 'run' };

const REVIEW_COUNTS = [5, 10, 15, 20, 25, 30, 35, 40];

export function DictationPage({ language, replay }: { language: DictationLanguage; replay?: Replay }) {
  const { ai, currentUser } = useWorkspace();
  const { pop, replace } = useNavigation();
  const toast = useToast();
  const { entry, update } = useDictationFile(language);
  const file = entry?.status === 'ok' ? entry.value : undefined;
  const key = draftKey(currentUser!.id, language);
  const title = languageTitle(language);

  // Opened from the learning record with a past dictation's words: go straight to confirming them.
  const [step, setStep] = useState<Step>(replay ? { kind: 'confirm' } : { kind: 'home' });
  const [plan, setPlan] = useState<Plan>(() => (replay
    ? { kind: 'review', readMode: replay.readMode, words: replay.words.map(word => planWord(word.text, word.translation ?? '')) }
    : { kind: 'dictation', words: [], readMode: 'translation' }));
  const [run, setRun] = useState<Run | null>(null);
  /** True until the replayed words are started: going back then returns to the learning record. */
  const replaying = useRef(!!replay);
  const [savedDraft, setSavedDraft] = useState<Run | null | undefined>(undefined);
  /** A confirmed plan waiting for "放弃上次的" because a draft exists. */
  const [replacing, setReplacing] = useState<Plan | null>(null);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => { void drafts.read<Run>(key).then(value => setSavedDraft(value)); }, [key]);
  // A draft whose submission is in the file already (saved, then reported as failed, and found when the app was
  // opened again) is done: drop it instead of offering to submit it a second time.
  useEffect(() => {
    if (!savedDraft?.sessionId || !file?.sessions.some(session => session.id === savedDraft.sessionId)) return;
    void drafts.clear(key);
    setSavedDraft(null);
    toast.show('上次没能确认保存的那次听写已经在记录里了');
  }, [savedDraft, file, key, toast]);

  /** Every change to a run is saved as a draft, so closing the app loses nothing. */
  const changeRun = useCallback((next: Run | null) => {
    setRun(next);
    if (next) void drafts.write(key, next); else void drafts.clear(key);
  }, [key]);

  const goHome = () => { abort.current?.abort(); setStep({ kind: 'home' }); };

  const startPlan = (next: Plan) => { setPlan(next); setStep({ kind: 'confirm' }); };
  const startRun = (confirmed: Plan) => {
    // An unfinished dictation, possibly with answers already marked, is never replaced silently.
    if (savedDraft) { setReplacing(confirmed); return; }
    replaceRun(confirmed);
  };
  const replaceRun = (confirmed: Plan) => {
    // The replayed words are used once: coming back to this page later (e.g. from 错词本) shows its home.
    if (replaying.current) replace({ name: 'dictation', language });
    replaying.current = false;
    setReplacing(null);
    // A word listed twice is read once: two copies could be marked differently, and only one is saved.
    const seen = new Set<string>();
    const unique = confirmed.words.filter(word => {
      const wordId = wordKey(word.text.trim().replace(/\s+/g, ' '), language);
      if (seen.has(wordId)) return false;
      seen.add(wordId);
      return true;
    });
    changeRun({ ...confirmed, words: shuffle(unique), index: 0, phase: 'running', wrong: [], startedAt: nowIso(), sessionId: newSessionId() });
    setSavedDraft(null);
    setStep({ kind: 'run' });
  };

  const recognize = async (image: string) => {
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setStep({ kind: 'recognizing', image });
    try {
      const words = await ai.recognizeWords(image, language, controller.signal);
      if (controller.signal.aborted) return;
      startPlan({ kind: 'dictation', readMode: plan.readMode, words: words.map(text => planWord(text)) });
    } catch (error) {
      if (controller.signal.aborted || (error instanceof AiError && error.kind === 'cancelled')) return;
      setStep({ kind: 'recognizing', image, error: errorText(error) });
    }
  };

  const takePhoto = async (source: PhotoSource) => {
    try {
      const image = await pickPhoto(source);
      if (image) setStep({ kind: 'crop', image });
    } catch (error) {
      toast.show(`没有拿到照片：${errorText(error)}`);
    }
  };

  const finishSubmit = async (current: Run) => {
    const wrong = new Set(current.wrong);
    const sessionId = current.sessionId ?? newSessionId();
    const undo = {
      label: '撤销',
      run: () => {
        void update(value => ({ value: undoSession(value, sessionId), result: undefined }))
          .then(() => toast.show('已撤销这次听写'), error => toast.show(error instanceof SaveInterruptedError ? errorText(error) : `撤销失败：${errorText(error)}`));
      },
    };
    let saved: boolean;
    try {
      saved = await update(value => {
        // Saved already: an earlier submission reported as failed that was found in the file when the app was opened again.
        if (value.sessions.some(session => session.id === sessionId)) return { value, result: false };
        const result = submit(value, {
          kind: current.kind, readMode: current.readMode, sessionId,
          words: current.words.map(word => ({ text: word.text, translation: word.translation, correct: !wrong.has(word.key) })),
        });
        return { value: result.file, result: true };
      });
    } catch (error) {
      // Kept: DataCache builds on the new value and the next save or start writes it, so the dictation is done.
      if (error instanceof SaveInterruptedError) {
        changeRun(null);
        setStep({ kind: 'home' });
        toast.show(errorText(error), undo);
        return;
      }
      // Cannot be checked in this run: keep the draft as it is until opening the app again shows whether it is in the file.
      if (mayBeRestored(error)) changeRun({ ...current, sessionId, saveFailed: true });
      throw error;
    }
    changeRun(null);
    setStep({ kind: 'home' });
    if (!saved) { toast.show('这次听写已经保存过了，没有再记一次'); return; }
    const right = current.words.length - wrong.size;
    toast.show(wrong.size ? `已保存：写对 ${right} / ${current.words.length} 个` : `全对！${current.words.length} 个词都写对了`, undo);
  };

  // Back from steps without their own handler.
  useBackHandler(step.kind === 'crop' || step.kind === 'recognizing', goHome);

  if (step.kind === 'crop') {
    return <CropPhoto image={step.image} title={title} onCancel={goHome} onDone={jpeg => void recognize(jpeg)} />;
  }
  if (step.kind === 'recognizing') {
    return (
      <Page title={`${title} · 识别`}>
        <div className="recognizing">
          <img src={step.image} alt="正在识别的照片" />
          {step.error
            ? <>
                <div className="notice error">{step.error}</div>
                <div className="row wrap">
                  <button className="button primary" onClick={() => void recognize(step.image)}>重试</button>
                  <button className="button" onClick={() => startPlan({ kind: 'dictation', readMode: plan.readMode, words: [] })}>改为手动输入</button>
                  <button className="button" onClick={goHome}>返回</button>
                </div>
              </>
            : <>
                <div className="spinner" aria-hidden />
                <p>正在识别词语…</p>
                <button className="button" onClick={goHome}>取消</button>
              </>}
        </div>
      </Page>
    );
  }
  if (step.kind === 'confirm') {
    return (
      <>
        <ConfirmStep language={language} plan={plan} file={file} onChange={setPlan} onStart={startRun} onDiscard={replaying.current ? pop : goHome} />
        {replacing && savedDraft && (savedDraft.saveFailed && entry?.status === 'unusable'
          ? <Sheet title="上次的听写还没确认保存" onClose={() => setReplacing(null)}>
              <p className="muted">上次提交时保存没有完成。请先重新打开 App，它会检查这次记好了没有，然后再开始新的听写。</p>
              <button className="button primary" onClick={() => setReplacing(null)}>先不开始</button>
            </Sheet>
          : <Sheet title={savedDraft.phase === 'running' ? '上次的听写还没完成' : '上次的听写还没提交'} onClose={() => setReplacing(null)}>
              <p className="muted">开始新的听写，会放弃上次{savedDraft.phase === 'running' ? `没做完的 ${savedDraft.words.length} 个词` : '已经对过的答案'}。</p>
              <button className="button primary" onClick={() => setReplacing(null)}>先不开始</button>
              <button className="button danger" onClick={() => replaceRun(replacing)}>放弃上次的，开始新的</button>
            </Sheet>
        )}
      </>
    );
  }
  if (step.kind === 'run' && run) {
    if (run.phase === 'running') {
      return (
        <RunDictation key={run.startedAt} language={language} run={run}
          onIndex={index => changeRun({ ...run, index })}
          onFinish={count => changeRun({ ...run, words: run.words.slice(0, count), phase: 'answers' })}
          onQuit={() => { setSavedDraft(run); setStep({ kind: 'home' }); }} />
      );
    }
    return (
      <MarkAnswers language={language} run={run} locked={!!run.saveFailed && entry?.status === 'unusable'}
        onToggle={wordKey => changeRun({ ...run, wrong: run.wrong.includes(wordKey) ? run.wrong.filter(item => item !== wordKey) : [...run.wrong, wordKey] })}
        onSubmit={() => finishSubmit(run)}
        onLeave={() => { setSavedDraft(run); setStep({ kind: 'home' }); }}
        onDiscard={() => { changeRun(null); setSavedDraft(null); setStep({ kind: 'home' }); }} />
    );
  }

  return (
    <DictationHome language={language} file={file} problem={entry?.status === 'unusable' ? entry.error : undefined} loading={!entry}
      draft={savedDraft ?? null}
      onResume={draft => { changeRun({ ...draft, sessionId: draft.sessionId ?? newSessionId() }); setSavedDraft(null); setStep({ kind: 'run' }); }}
      onDiscardDraft={() => { void drafts.clear(key); setSavedDraft(null); }}
      onPhoto={source => void takePhoto(source)}
      onWords={next => startPlan(next)}
      onUndo={async sessionId => {
        try { await update(value => ({ value: undoSession(value, sessionId), result: undefined })); } catch (error) {
          // Kept, and written by the next save or start: the undo is done.
          if (!(error instanceof SaveInterruptedError)) throw error;
          toast.show(errorText(error));
          return;
        }
        toast.show('已撤销这次听写');
      }}
      />
  );
}

function ConfirmStep({ onDiscard, ...props }: Parameters<typeof ConfirmWords>[0] & { onDiscard(): void }) {
  const [asking, setAsking] = useState(false);
  useBackHandler(true, () => (props.plan.words.length ? setAsking(true) : onDiscard()));
  return (
    <>
      <ConfirmWords {...props} />
      {asking && (
        <Sheet title="放弃这些词语？" onClose={() => setAsking(false)}>
          <p className="muted">返回后，这次识别或输入的词语不会保存。</p>
          <div className="row">
            <button className="button grow" onClick={() => setAsking(false)}>继续确认</button>
            <button className="button danger grow" onClick={() => { setAsking(false); onDiscard(); }}>放弃</button>
          </div>
        </Sheet>
      )}
    </>
  );
}

function DictationHome({ language, file, problem, loading, draft, onResume, onDiscardDraft, onPhoto, onWords, onUndo }: {
  language: DictationLanguage; file?: DictationFile; problem?: string; loading: boolean; draft: Run | null;
  onResume(draft: Run): void; onDiscardDraft(): void; onPhoto(source: PhotoSource): void; onWords(plan: Plan): void;
  onUndo(sessionId: string): Promise<void>;
}) {
  const { push } = useNavigation();
  const [manual, setManual] = useState(false);
  const [count, setCount] = useState(10);
  const [undoing, setUndoing] = useState<{ id: string; at: string; count: number } | null>(null);
  const [discarding, setDiscarding] = useState(false);
  // The tiles sit where the home page's tile was: the second tap of a double tap there must not open the camera.
  // So does the resume card, which takes the first row when there is a draft.
  const settled = useSettled();
  // A file that could not be read cannot take a new dictation: do not let the child do one that cannot be saved.
  const unusable = !file && !loading;
  // A submission that could not be checked, while its file cannot be written: only opening the app again tells
  // whether it is in the file, so the draft is kept as it is until then.
  const locked = !!draft?.saveFailed && unusable;
  const rows = useMemo(() => (file ? wordRows(file) : []), [file]);
  const wrongEver = rows.filter(row => row.stats.wrong > 0).length;
  const recent = useMemo(() => {
    if (!file) return [];
    const byId = new Map(file.words.map(word => [word.id, word]));
    return [...file.sessions].reverse().slice(0, 5).map(session => ({ session, words: session.wordIds.map(id => byId.get(id)).filter(word => !!word) }));
  }, [file]);

  const review = () => {
    const picked = sample(rows.map(row => ({ item: row.word, total: row.stats.total, wrong: row.stats.wrong, lastMs: row.stats.lastMs })), count, Date.now());
    onWords({ kind: 'review', readMode: 'translation', words: picked.map(word => planWord(word.text, word.translation ?? '')) });
  };

  return (
    <Page title={languageTitle(language)}>
      {problem && <div className="notice error">{problem}</div>}
      {draft && (
        <div className="card resume">
          <div className="grow">
            <b>{draft.phase === 'running' ? `上次的听写还没完成（第 ${draft.index + 1} / ${draft.words.length} 个）` : `上次的听写还没提交（${draft.words.length} 个词）`}</b>
            <div className="muted small">{locked ? '上次提交时保存没有完成，重新打开 App 后会检查这次记好了没有'
              : draft.saveFailed ? '上次提交时没能确认保存，结果不在记录里，可以再提交一次'
              : `${formatRelative(draft.startedAt)} 开始`}</div>
          </div>
          {!locked && <button className={`button${settled ? '' : ' settling'}`} onClick={() => setDiscarding(true)}>放弃</button>}
          <button className={`button primary${settled ? '' : ' settling'}`} disabled={unusable} onClick={() => onResume(draft)}>{draft.phase === 'running' ? '继续听写' : '继续对答案'}</button>
        </div>
      )}
      {draft && discarding && (
        <Sheet title="放弃上次的听写？" onClose={() => setDiscarding(false)}>
          <p className="muted">{draft.phase === 'running' ? '读到一半的听写' : '已经对过的答案'}不会保存，也不能找回。</p>
          <div className="row">
            <button className="button grow" onClick={() => setDiscarding(false)}>取消</button>
            <button className="button danger grow" onClick={() => { setDiscarding(false); onDiscardDraft(); }}>放弃</button>
          </div>
        </Sheet>
      )}

      <section className="stack-tight">
        <h2 className="section-title">新的听写</h2>
        <div className="action-grid">
          <button className={`action-tile${settled ? '' : ' settling'}`} disabled={unusable} onClick={() => onPhoto('camera')}><Camera size={28} />拍照</button>
          <button className={`action-tile${settled ? '' : ' settling'}`} disabled={unusable} onClick={() => onPhoto('gallery')}><Image size={28} />相册</button>
          <button className={`action-tile${settled ? '' : ' settling'}`} disabled={unusable} onClick={() => setManual(true)}><Keyboard size={28} />手动输入</button>
        </div>
      </section>

      <section className="stack-tight">
        <h2 className="section-title">错词听写</h2>
        <div className="card stack">
          <div className="muted small">{loading ? '正在读取错词本…' : rows.length ? `错词本共 ${rows.length} 个词，其中 ${wrongEver} 个错过。按错误率和间隔天数抽取。` : '错词本还是空的，听写提交后会自动记录。'}</div>
          <div className="chips">
            {REVIEW_COUNTS.map(value => <button key={value} className={`chip${value === count ? ' selected' : ''}`} onClick={() => setCount(value)}>{value}</button>)}
          </div>
          <button className="button primary" disabled={!rows.length} onClick={review}>抽 {Math.min(count, rows.length || count)} 个词开始</button>
        </div>
        <button className="list-row link-row card" onClick={() => push({ name: 'word-book', language })}>
          <BookMarked size={20} className="muted" />
          <span className="grow">错词本<span className="muted small">　查看、添加、修改、删除</span></span>
          <ChevronRight size={18} className="muted" />
        </button>
      </section>

      <section className="stack-tight">
        <h2 className="section-title">最近的听写</h2>
        {!loading && !recent.length && <p className="muted small">还没有记录。</p>}
        {!!recent.length && (
          <div className="list">
            {recent.map(({ session, words }) => {
              const wrong = words.filter(word => word.attempts.some(attempt => attempt.sessionId === session.id && !attempt.correct)).length;
              return (
                <div key={session.id} className="list-row">
                  <span className="grow">
                    <b>{formatRelative(session.at)}</b>　<span className={`small ${wrong ? 'bad' : 'ok'}`}>{session.kind === 'added' ? `添加 ${words.length} 个` : `对 ${words.length - wrong} / ${words.length}`}</span><br />
                    <span className="muted small ellipsis">{words.map(word => word.text).join('、')}</span>
                  </span>
                  <button className="icon-button" aria-label="撤销这次" onClick={() => setUndoing({ id: session.id, at: session.at, count: words.length })}><Undo2 size={20} /></button>
                  <button className="button" disabled={!words.length} onClick={() => onWords({ kind: 'review', readMode: session.readMode ?? 'translation', words: words.map(word => planWord(word.text, word.translation ?? '')) })}>
                    <RotateCcw size={18} />再听一遍
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {manual && <ManualInput language={language} onClose={() => setManual(false)} onDone={words => { setManual(false); onWords({ kind: 'dictation', readMode: 'translation', words: words.map(text => planWord(text)) }); }} />}
      {undoing && (
        <ArithmeticConfirm title="撤销这次听写？" confirmLabel="撤销"
          message={<p>将删除 {formatRelative(undoing.at)} 这次的 {undoing.count} 条记录。只在这次出现过的词会从错词本里一起删除。</p>}
          onConfirm={() => onUndo(undoing.id)} onClose={() => setUndoing(null)} />
      )}
    </Page>
  );
}

function ManualInput({ language, onDone, onClose }: { language: DictationLanguage; onDone(words: string[]): void; onClose(): void }) {
  const [text, setText] = useState('');
  const words = splitWords(text, language);
  return (
    <Sheet title="手动输入" onClose={onClose} keepOnOutsideTap>
      <textarea className="input" autoFocus placeholder={language === 'chinese' ? '每行一个词，也可以用空格、逗号或顿号分隔' : '每行一个词或短语，也可以用逗号分隔'} value={text} onChange={event => setText(event.target.value)} />
      <button className="button primary" disabled={!words.length} onClick={() => onDone(words)}>下一步（{words.length} 个词）</button>
    </Sheet>
  );
}
