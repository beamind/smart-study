import { Camera, CheckSquare, Image, Keyboard, Plus, Search, Shuffle, Sparkles, Square, Trash2, Undo2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import { AiError } from '../../ai/dashscope';
import { nowIso } from '../../core/time';
import { questionKey } from '../../data/normalize';
import type { ErrorBookFile, Question } from '../../data/schema';
import { addQuestions, deleteQuestions, editQuestion, questionRows, recordPractice, sortForBook, undoSession } from '../../domain/errorBook';
import { sample } from '../../domain/sampling';
import { drafts } from '../../services/device';
import { pickPhoto, type PhotoSource } from '../../services/photo';
import { ArithmeticConfirm, errorText, Page, Sheet, useToast } from '../../ui/components';
import { formatRelative, formatTime } from '../../ui/format';
import { CropPhoto } from '../dictation/CropPhoto';
import { useErrorBookFile } from '../useUserData';
import { CaptureQuestions } from './CaptureQuestions';
import { GenerateVariations } from './GenerateVariations';
import { MarkPractice } from './MarkPractice';
import { QuestionEditor } from './QuestionEditor';
import { QuestionText } from './QuestionText';
import { RunPractice } from './RunPractice';
import { draftKey, newKey, runTitle, SESSION_LABELS, type CaptureItem, type PracticeItem, type PracticeRun, type RunKind } from './types';

type Step =
  | { kind: 'book' }
  | { kind: 'crop'; image: string }
  | { kind: 'recognizing'; image: string; error?: string }
  | { kind: 'confirm'; image?: string; manual?: boolean }
  | { kind: 'generate'; sources: Question[] }
  | { kind: 'run' };

const PRACTICE_COUNTS = [5, 10, 15, 20, 30];
const VARIATION_COUNTS = [3, 5, 10];

/** 英语错题本: list, capture (photo or manual), practice and 举一反三. */
export function ErrorBookPage() {
  const { ai, currentUser } = useWorkspace();
  const toast = useToast();
  const { entry, update } = useErrorBookFile();
  const file = entry?.status === 'ok' ? entry.value : undefined;
  const key = draftKey(currentUser!.id);

  const [step, setStep] = useState<Step>({ kind: 'book' });
  const [capture, setCapture] = useState<CaptureItem[]>([]);
  const [run, setRun] = useState<PracticeRun | null>(null);
  const [savedDraft, setSavedDraft] = useState<PracticeRun | null | undefined>(undefined);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => { void drafts.read<PracticeRun>(key).then(value => setSavedDraft(value)); }, [key]);

  /** Every change to a run is saved as a draft, so closing the app loses nothing. */
  const changeRun = useCallback((next: PracticeRun | null) => {
    setRun(next);
    if (next) void drafts.write(key, next); else void drafts.clear(key);
  }, [key]);

  const goBook = () => { abort.current?.abort(); setStep({ kind: 'book' }); };

  const undoToast = (text: string, sessionId: string) => toast.show(text, {
    label: '撤销',
    run: () => { void update(value => ({ value: undoSession(value, sessionId), result: undefined })).then(() => toast.show('已撤销'), error => toast.show(`撤销失败：${errorText(error)}`)); },
  });

  // ---------- capture ----------
  const takePhoto = async (source: PhotoSource) => {
    try {
      const image = await pickPhoto(source);
      if (image) setStep({ kind: 'crop', image });
    } catch (error) {
      toast.show(`没有拿到照片：${errorText(error)}`);
    }
  };

  const recognize = async (image: string) => {
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setStep({ kind: 'recognizing', image });
    try {
      const questions = await ai.recognizeQuestions(image, controller.signal);
      if (controller.signal.aborted) return;
      setCapture(questions.map(question => ({ key: newKey(), text: question.text, answer: question.answer, checked: true, origin: 'photo' })));
      setStep({ kind: 'confirm', image });
    } catch (error) {
      if (controller.signal.aborted || (error instanceof AiError && error.kind === 'cancelled')) return;
      setStep({ kind: 'recognizing', image, error: errorText(error) });
    }
  };

  const saveCapture = async (items: CaptureItem[]) => {
    const outcome = await update(value => {
      const result = addQuestions(value, 'capture', items.map(item => ({ text: item.text, answer: item.answer, origin: item.origin })));
      return { value: result.file, result };
    });
    setCapture([]);
    setStep({ kind: 'book' });
    const saved = outcome.created + outcome.existing; // a question entered twice is saved once
    undoToast(outcome.existing ? `已保存 ${saved} 道错题，其中 ${outcome.existing} 道原来就在错题本里，记了一次错` : `已保存 ${saved} 道错题`, outcome.sessionId);
  };

  // ---------- practice and 举一反三 ----------
  const startRun = (kind: RunKind, items: PracticeItem[]) => {
    changeRun({ kind, items, index: 0, phase: 'running', wrong: [], startedAt: nowIso() });
    setSavedDraft(null);
    setStep({ kind: 'run' });
  };

  const choose = (kind: RunKind, count: number) => {
    if (!file) return;
    const picked = sample(questionRows(file).map(row => ({ item: row.question, total: row.stats.total, wrong: row.stats.wrong, lastMs: row.stats.lastMs })), count, Date.now());
    if (kind === 'practice') startRun('practice', picked.map(question => ({ key: newKey(), text: question.text, answer: question.answer, questionId: question.id })));
    else setStep({ kind: 'generate', sources: picked });
  };

  const finishSubmit = async (current: PracticeRun) => {
    const wrong = new Set(current.wrong);
    if (current.kind === 'variation') {
      const missed = current.items.filter(item => wrong.has(item.key));
      if (!missed.length) {
        changeRun(null);
        setStep({ kind: 'book' });
        toast.show(`全对！${current.items.length} 道新题都做对了，不需要保存`);
        return;
      }
      const outcome = await update(value => {
        // The original may have been deleted in the meantime; then the new question simply has no source.
        const known = new Set(value.questions.map(question => question.id));
        const result = addQuestions(value, 'variation', missed.map(item => ({
          text: item.text, answer: item.answer, origin: 'generated',
          ...(item.sourceQuestionId && known.has(item.sourceQuestionId) ? { sourceQuestionId: item.sourceQuestionId } : {}),
        })));
        return { value: result.file, result };
      });
      changeRun(null);
      setStep({ kind: 'book' });
      undoToast(`已把做错的 ${outcome.created + outcome.existing} 道新题保存到错题本（共 ${current.items.length} 题）`, outcome.sessionId);
      return;
    }
    const outcome = await update(value => {
      // Questions deleted while the practice was paused are left out instead of failing the whole save.
      const known = new Set(value.questions.map(question => question.id));
      const kept = current.items.filter(item => item.questionId && known.has(item.questionId));
      if (!kept.length) throw new Error('这些题已经从错题本里删除了，没有可以保存的记录');
      const result = recordPractice(value, kept.map(item => ({ questionId: item.questionId!, correct: !wrong.has(item.key) })));
      return { value: result.file, result: { sessionId: result.sessionId, kept: kept.length, wrong: kept.filter(item => wrong.has(item.key)).length } };
    });
    changeRun(null);
    setStep({ kind: 'book' });
    const skipped = current.items.length - outcome.kept;
    undoToast(`已保存：${outcome.kept} 题，做错 ${outcome.wrong} 题${skipped ? `（${skipped} 题已被删除，没有记录）` : ''}`, outcome.sessionId);
  };

  useBackHandler(step.kind === 'crop' || step.kind === 'recognizing', goBook);

  if (step.kind === 'crop') {
    return <CropPhoto image={step.image} title="录入错题" hint="拖动方框，框住要录入的题目（包括选项），识别更准确。" onCancel={goBook} onDone={jpeg => void recognize(jpeg)} />;
  }
  if (step.kind === 'recognizing') {
    return (
      <Page title="录入错题 · 识别">
        <div className="recognizing">
          <img src={step.image} alt="正在识别的照片" />
          {step.error
            ? <>
                <div className="notice error">{step.error}</div>
                <div className="row wrap">
                  <button className="button primary" onClick={() => void recognize(step.image)}>重试</button>
                  <button className="button" onClick={() => { setCapture([]); setStep({ kind: 'confirm', manual: true }); }}>改为手动输入</button>
                  <button className="button" onClick={goBook}>返回</button>
                </div>
              </>
            : <>
                <div className="spinner" aria-hidden />
                <p>正在识别题目，并让千问给出正确答案…</p>
                <button className="button" onClick={goBook}>取消</button>
              </>}
        </div>
      </Page>
    );
  }
  if (step.kind === 'confirm') {
    return (
      <CaptureQuestions image={step.image} items={capture} existing={file?.questions ?? []} startEditing={step.manual}
        onChange={setCapture} onSave={saveCapture} onDiscard={() => { setCapture([]); goBook(); }} />
    );
  }
  if (step.kind === 'generate') {
    return <GenerateVariations sources={step.sources} onCancel={goBook} onStart={items => startRun('variation', items)} />;
  }
  if (step.kind === 'run' && run) {
    if (run.phase === 'running') {
      return (
        <RunPractice key={run.startedAt} run={run}
          onIndex={index => changeRun({ ...run, index })}
          onFinish={count => changeRun({ ...run, items: run.items.slice(0, count), phase: 'answers' })}
          onQuit={() => { setSavedDraft(run); setStep({ kind: 'book' }); }} />
      );
    }
    return (
      <MarkPractice run={run}
        onToggle={itemKey => changeRun({ ...run, wrong: run.wrong.includes(itemKey) ? run.wrong.filter(item => item !== itemKey) : [...run.wrong, itemKey] })}
        onSubmit={() => finishSubmit(run)}
        onLeave={() => { setSavedDraft(run); setStep({ kind: 'book' }); }}
        onDiscard={() => { changeRun(null); setSavedDraft(null); setStep({ kind: 'book' }); }} />
    );
  }

  return (
    <QuestionBook file={file} problem={entry?.status === 'unusable' ? entry.error : entry?.problem} loading={!entry}
      draft={savedDraft ?? null}
      onResume={draft => { setRun(draft); setSavedDraft(null); setStep({ kind: 'run' }); }}
      onDiscardDraft={() => { void drafts.clear(key); setSavedDraft(null); }}
      onPhoto={source => void takePhoto(source)}
      onManual={() => { setCapture([]); setStep({ kind: 'confirm', manual: true }); }}
      onChoose={choose}
      update={update} />
  );
}

type Filter = 'all' | 'wrong' | 'recent';
const PAGE = 100;

function QuestionBook({ file, problem, loading, draft, onResume, onDiscardDraft, onPhoto, onManual, onChoose, update }: {
  file?: ErrorBookFile; problem?: string; loading: boolean; draft: PracticeRun | null;
  onResume(draft: PracticeRun): void; onDiscardDraft(): void; onPhoto(source: PhotoSource): void; onManual(): void;
  onChoose(kind: RunKind, count: number): void;
  update: ReturnType<typeof useErrorBookFile>['update'];
}) {
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [shown, setShown] = useState(PAGE);
  const [adding, setAdding] = useState(false);
  const [choosing, setChoosing] = useState<RunKind | null>(null);
  const [replacing, setReplacing] = useState<{ kind: RunKind; count: number } | null>(null);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<Question | null>(null);
  const [deleting, setDeleting] = useState<Set<string> | null>(null);
  const [undoing, setUndoing] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  useBackHandler(selecting, () => { setSelecting(false); setSelected(new Set()); });

  const all = useMemo(() => (file ? questionRows(file) : []), [file]);
  const rows = useMemo(() => {
    const needle = questionKey(query);
    const filtered = all
      .filter(row => !needle || questionKey(row.question.text).includes(needle) || questionKey(row.question.answer).includes(needle))
      .filter(row => filter !== 'wrong' || row.stats.wrong > 0);
    return filter === 'recent'
      ? filtered.sort((a, b) => Date.parse(b.question.createdAt) - Date.parse(a.question.createdAt))
      : sortForBook(filtered);
  }, [all, query, filter]);

  /** The newest session, so an accidental extra submission can be found and undone at once. */
  const latest = useMemo(() => {
    const session = file?.sessions.at(-1);
    if (!session || session.kind === 'legacy') return null;
    const wrong = file!.questions.filter(question => question.attempts.some(attempt => attempt.sessionId === session.id && !attempt.correct)).length;
    return { session, count: session.questionIds.length, wrong };
  }, [file]);

  const save = async (change: Parameters<typeof update>[0], done: string) => {
    try { await update(change); toast.show(done); } catch (error) { toast.show(`没有保存：${errorText(error)}`); throw error; }
  };
  const toggle = (id: string) => setSelected(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  const total = file?.questions.length ?? 0;

  return (
    <Page title="英语错题本"
      actions={total > 0 && <button className="button text" onClick={() => { setSelecting(!selecting); setSelected(new Set()); }}>{selecting ? '完成' : '选择'}</button>}
      footer={selecting
        ? <>
            <button className="button grow" onClick={() => setSelected(selected.size === rows.length ? new Set() : new Set(rows.map(row => row.question.id)))}>
              {selected.size === rows.length && rows.length ? '取消全选' : '全选'}
            </button>
            <button className="button danger solid grow" disabled={!selected.size} onClick={() => setDeleting(selected)}><Trash2 size={18} />删除 {selected.size} 道</button>
          </>
        : <>
            <button className="button" disabled={!file} onClick={() => setAdding(true)}><Plus size={20} />录入</button>
            <button className="button" disabled={!total} onClick={() => setChoosing('practice')}><Shuffle size={20} />练习</button>
            <button className="button primary grow" disabled={!total} onClick={() => setChoosing('variation')}><Sparkles size={20} />举一反三</button>
          </>}>
      {problem && <div className="notice error">{problem}</div>}
      {draft && (
        <div className="card resume">
          <div className="grow">
            <b>{draft.phase === 'running' ? `上次的${runTitle(draft.kind)}还没做完（第 ${draft.index + 1} / ${draft.items.length} 题）` : `上次的${runTitle(draft.kind)}还没提交（${draft.items.length} 题）`}</b>
            <div className="muted small">{formatRelative(draft.startedAt)} 开始</div>
          </div>
          <button className="button" onClick={() => setDiscarding(true)}>放弃</button>
          <button className="button primary" onClick={() => onResume(draft)}>{draft.phase === 'running' ? '继续做' : '继续对答案'}</button>
        </div>
      )}
      {draft && discarding && (
        <Sheet title={`放弃上次的${runTitle(draft.kind)}？`} onClose={() => setDiscarding(false)}>
          <p className="muted">{draft.phase === 'running' ? '做到一半的题' : '已经对过的答案'}不会保存，也不能找回。</p>
          <div className="row">
            <button className="button grow" onClick={() => setDiscarding(false)}>取消</button>
            <button className="button danger grow" onClick={() => { setDiscarding(false); onDiscardDraft(); }}>放弃</button>
          </div>
        </Sheet>
      )}
      {latest && !selecting && (
        <div className="list-row card latest">
          <span className="grow small">
            <span className="muted">最近一次：</span>{formatRelative(latest.session.at)}　{SESSION_LABELS[latest.session.kind]} {latest.count} 道
            {latest.session.kind === 'practice' && <span className={latest.wrong ? 'bad' : 'ok'}>，做错 {latest.wrong} 道</span>}
          </span>
          <button className="button text" onClick={() => setUndoing(true)}><Undo2 size={18} />撤销</button>
        </div>
      )}

      <div className="search">
        <Search size={18} className="muted" />
        <input placeholder="搜索题目或答案" value={query} onChange={event => { setQuery(event.target.value); setShown(PAGE); setSelected(new Set()); }} />
      </div>
      <div className="chips">
        {([['all', '全部'], ['wrong', '错过的'], ['recent', '最近添加']] as const).map(([value, label]) => (
          <button key={value} className={`chip${filter === value ? ' selected' : ''}`} onClick={() => { setFilter(value); setShown(PAGE); setSelected(new Set()); }}>{label}</button>
        ))}
        {file && <span className="muted small" style={{ alignSelf: 'center' }}>{rows.length} 道</span>}
      </div>
      {loading && <p className="muted small">正在读取…</p>}
      {file && !rows.length && <p className="muted small">{total ? '没有符合条件的题。' : '错题本还是空的。点"录入"拍照，或手动输入题目和答案。'}</p>}
      {!!rows.length && (
        <div className="list">
          {rows.slice(0, shown).map(({ question, stats }) => (
            <button key={question.id} className="list-row question-row" aria-pressed={selecting ? selected.has(question.id) : undefined}
              onClick={() => (selecting ? toggle(question.id) : setEditing(question))}>
              {selecting && (selected.has(question.id) ? <CheckSquare size={22} className="ok" /> : <Square size={22} className="muted" />)}
              <div className="grow">
                <QuestionText text={question.text} />
                {question.answer && <div className="answer-line">答案：{question.answer}</div>}
                <div className="muted small">{formatTime(question.createdAt)} 录入{question.origin === 'generated' ? ' · 举一反三' : question.origin === 'manual' ? ' · 手动输入' : ''}</div>
              </div>
              <span className={`score${stats.wrong ? ' bad' : ''}`}>错 {stats.wrong} / {stats.total}</span>
            </button>
          ))}
        </div>
      )}
      {rows.length > shown && <button className="button" onClick={() => setShown(shown + PAGE)}>再显示 {Math.min(PAGE, rows.length - shown)} 道</button>}

      {adding && (
        <Sheet title="录入错题" onClose={() => setAdding(false)}>
          <div className="action-grid">
            <button className="action-tile" onClick={() => { setAdding(false); onPhoto('camera'); }}><Camera size={28} />拍照</button>
            <button className="action-tile" onClick={() => { setAdding(false); onPhoto('gallery'); }}><Image size={28} />相册</button>
            <button className="action-tile" onClick={() => { setAdding(false); onManual(); }}><Keyboard size={28} />手动输入</button>
          </div>
        </Sheet>
      )}
      {choosing && <ChooseCount kind={choosing} total={total} onClose={() => setChoosing(null)} onStart={count => {
        setChoosing(null);
        // An unfinished practice, possibly with answers already marked, is never replaced silently.
        if (draft) setReplacing({ kind: choosing, count }); else onChoose(choosing, count);
      }} />}
      {replacing && draft && (
        <Sheet title={draft.phase === 'running' ? `上次的${runTitle(draft.kind)}还没做完` : `上次的${runTitle(draft.kind)}还没提交`} onClose={() => setReplacing(null)}>
          <p className="muted">开始新的{runTitle(replacing.kind)}，会放弃上次{draft.phase === 'running' ? `没做完的 ${draft.items.length} 道题` : '已经对过的答案'}。</p>
          <button className="button primary" onClick={() => setReplacing(null)}>先不开始</button>
          <button className="button danger" onClick={() => { const next = replacing; setReplacing(null); onChoose(next.kind, next.count); }}>放弃上次的，开始新的</button>
        </Sheet>
      )}
      {editing && (
        <QuestionEditor title="修改错题" text={editing.text} answer={editing.answer} onClose={() => setEditing(null)}
          onSave={value => save(book => ({ value: editQuestion(book, editing.id, value), result: undefined }), '已保存')}
          onDelete={() => { setDeleting(new Set([editing.id])); setEditing(null); }} />
      )}
      {deleting && (
        <ArithmeticConfirm title={`删除 ${deleting.size} 道题？`} confirmLabel="删除"
          message={<p>这些题和它们的全部练习记录都会删除。删除前的数据会保存在 data/history 里。</p>}
          onConfirm={async () => { await save(value => ({ value: deleteQuestions(value, deleting), result: undefined }), `已删除 ${deleting.size} 道题`); setSelecting(false); setSelected(new Set()); }}
          onClose={() => setDeleting(null)} />
      )}
      {undoing && latest && (
        <ArithmeticConfirm title={`撤销这次${SESSION_LABELS[latest.session.kind]}？`} confirmLabel="撤销"
          message={<p>将删除 {formatRelative(latest.session.at)} 这次的 {latest.count} 条记录。只在这次出现过的题会从错题本里一起删除。</p>}
          onConfirm={() => save(value => ({ value: undoSession(value, latest.session.id), result: undefined }), '已撤销')}
          onClose={() => setUndoing(false)} />
      )}
    </Page>
  );
}

function ChooseCount({ kind, total, onStart, onClose }: { kind: RunKind; total: number; onStart(count: number): void; onClose(): void }) {
  const counts = kind === 'practice' ? PRACTICE_COUNTS : VARIATION_COUNTS;
  const [count, setCount] = useState(counts[1]);
  const picked = Math.min(count, total);
  return (
    <Sheet title={runTitle(kind)} onClose={onClose}>
      <p className="muted small">
        {kind === 'practice'
          ? `从错题本的 ${total} 道题里按错误率和间隔天数抽题，每题限时作答，做完后对答案。`
          : `从错题本里按错误率和间隔天数抽原题，千问为每道题出一道考同样知识点的新题。只有做错的新题会保存。`}
      </p>
      <div className="chips">
        {counts.map(value => <button key={value} className={`chip${value === count ? ' selected' : ''}`} onClick={() => setCount(value)}>{value}</button>)}
      </div>
      <button className="button primary big" onClick={() => onStart(picked)}>{kind === 'practice' ? `抽 ${picked} 道题开始` : `出 ${picked} 道新题`}</button>
    </Sheet>
  );
}
