import { Check, CheckSquare, RotateCcw, Square, Trash2, Undo2, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useBackHandler, useNavigation } from '../../app/navigation';
import { wordKey } from '../../data/normalize';
import type { DictationFile, ErrorBookFile } from '../../data/schema';
import * as dictation from '../../domain/dictation';
import * as errorBook from '../../domain/errorBook';
import { summarizeItems, summarizeSessions, totals, type ItemSummary, type RecordKind, type SessionSummary } from '../../domain/stats';
import { SaveInterruptedError } from '../../storage/jsonFiles';
import { ArithmeticConfirm, notSavedText, Page, Sheet, useToast } from '../../ui/components';
import { formatTime } from '../../ui/format';
import { EditWord } from '../dictation/WordBook';
import { QuestionEditor } from '../errorBook/QuestionEditor';
import { useDictationFile, useErrorBookFile } from '../useUserData';

const SESSION_LABELS: Record<string, string> = {
  'chinese:dictation': '语文听写', 'chinese:review': '语文错词听写', 'chinese:added': '语文添加错词', 'chinese:legacy': '语文听写',
  'english:dictation': '英语听写', 'english:review': '英语错词听写', 'english:added': '英语添加错词', 'english:legacy': '英语听写',
  'errorBook:capture': '错题录入', 'errorBook:practice': '错题练习', 'errorBook:variation': '举一反三', 'errorBook:legacy': '错题本',
};
const sessionLabel = (session: SessionSummary) => SESSION_LABELS[`${session.kind}:${session.sessionKind}`] ?? session.sessionKind;
const KIND_LABELS: Record<RecordKind, string> = { chinese: '语文', english: '英语', errorBook: '错题' };

type Preset = 'today' | 'week' | 'month' | 'all' | 'custom';
type View = 'sessions' | 'items';
const PAGE = 100;

const dayStart = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
const dateInput = (ms: number) => { const date = new Date(ms); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; };
const parseDate = (value: string) => { const [y, m, d] = value.split('-').map(Number); return new Date(y, m - 1, d).getTime(); };
const monthDay = (ms: number) => { const date = new Date(ms); return `${date.getMonth() + 1}月${date.getDate()}日`; };

/** Selected range as [from, to) in ms, with a label for messages. */
function rangeOf(preset: Preset, custom: { from: string; to: string }, nowMs = Date.now()) {
  const today = dayStart(new Date(nowMs));
  switch (preset) {
    case 'today': return { from: today, to: Infinity, label: '今天' };
    case 'week': return { from: today - 6 * 86_400_000, to: Infinity, label: '最近 7 天' };
    case 'month': return { from: today - 29 * 86_400_000, to: Infinity, label: '最近 30 天' };
    case 'all': return { from: -Infinity, to: Infinity, label: '全部时间' };
    case 'custom': {
      const from = parseDate(custom.from);
      const toDay = parseDate(custom.to);
      const to = new Date(toDay).setDate(new Date(toDay).getDate() + 1);
      return { from, to, label: from === toDay ? monthDay(from) : `${monthDay(from)}–${monthDay(toDay)}` };
    }
  }
}

/** The chosen range and view survive leaving the page (e.g. "再听一遍" and back) for the rest of the app session. */
const remembered: { preset: Preset; custom: { from: string; to: string } | null; view: View } = { preset: 'month', custom: null, view: 'sessions' };

/**
 * 学习记录: every submission as one row, newest first, with undo and
 * "再听一遍"; or every word and question answered in the chosen range, where deleting only removes the
 * records inside that range.
 */
export function RecordsPage() {
  const chinese = useDictationFile('chinese');
  const english = useDictationFile('english');
  const book = useErrorBookFile();
  const toast = useToast();
  const [preset, setPresetState] = useState<Preset>(remembered.preset);
  const [custom, setCustomState] = useState(() => remembered.custom ?? { from: dateInput(Date.now() - 6 * 86_400_000), to: dateInput(Date.now()) });
  const [view, setViewState] = useState<View>(remembered.view);
  const setPreset = (value: Preset) => { remembered.preset = value; setPresetState(value); };
  const setCustom = (value: { from: string; to: string }) => { remembered.custom = value; setCustomState(value); };
  const setView = (value: View) => { remembered.view = value; setViewState(value); };

  const files = {
    chinese: chinese.entry?.status === 'ok' ? chinese.entry.value : undefined,
    english: english.entry?.status === 'ok' ? english.entry.value : undefined,
    errorBook: book.entry?.status === 'ok' ? book.entry.value : undefined,
  };
  const loading = !chinese.entry || !english.entry || !book.entry;
  const problems = [
    chinese.entry?.status === 'unusable' && `语文听写：${chinese.entry.error}`,
    english.entry?.status === 'unusable' && `英语听写：${english.entry.error}`,
    book.entry?.status === 'unusable' && `错题本：${book.entry.error}`,
  ].filter(Boolean) as string[];

  const range = rangeOf(preset, custom);
  const allSessions = useMemo(() => summarizeSessions(files), [files.chinese, files.english, files.errorBook]);
  const sessions = useMemo(() => allSessions.filter(session => session.ms >= range.from && session.ms < range.to), [allSessions, range.from, range.to]);
  const sum = totals(sessions);

  /** Apply one change to the file of `kind` and save it (one write). */
  const change = async (kind: RecordKind, edit: { dictation?: (file: DictationFile) => DictationFile; errorBook?: (file: ErrorBookFile) => ErrorBookFile }, done: string) => {
    try {
      if (kind === 'errorBook') await book.update(value => ({ value: edit.errorBook!(value), result: undefined }));
      else await (kind === 'chinese' ? chinese : english).update(value => ({ value: edit.dictation!(value), result: undefined }));
      toast.show(done);
    } catch (error) {
      toast.show(notSavedText(error));
      // An interrupted save is kept and written by the next save or start: it counts as done.
      if (!(error instanceof SaveInterruptedError)) throw error;
    }
  };

  return (
    <Page title="学习记录">
      {problems.map(problem => <div key={problem} className="notice error">{problem}</div>)}
      <div className="chips">
        {([['today', '今天'], ['week', '最近 7 天'], ['month', '最近 30 天'], ['all', '全部'], ['custom', '选择日期']] as const).map(([value, text]) => (
          <button key={value} className={`chip${preset === value ? ' selected' : ''}`} onClick={() => setPreset(value)}>{text}</button>
        ))}
      </div>
      {preset === 'custom' && (
        <div className="row wrap">
          <label className="field date-field"><span>从</span><input className="input" type="date" value={custom.from} max={custom.to} onChange={event => event.target.value && setCustom({ ...custom, from: event.target.value })} /></label>
          <label className="field date-field"><span>到</span><input className="input" type="date" value={custom.to} min={custom.from} onChange={event => event.target.value && setCustom({ ...custom, to: event.target.value })} /></label>
        </div>
      )}
      {!loading && (
        <div className="summary-grid">
          <div className="card"><div className="muted small">听写</div><b className="big-number">{sum.dictation.total}</b> 个词
            <div className="muted small">{sum.dictation.sessions} 次 · 正确率 {percent(sum.dictation.rate)}</div></div>
          <div className="card"><div className="muted small">错题练习</div><b className="big-number">{sum.errorBook.total}</b> 道题
            <div className="muted small">{sum.errorBook.sessions} 次 · 正确率 {percent(sum.errorBook.rate)}</div></div>
        </div>
      )}
      {!loading && <p className="muted small">汇总只算听写和错题练习；录入错题、添加错词和举一反三保存的新题不算在内。</p>}
      <div className="segmented">
        <button className={view === 'sessions' ? 'selected' : ''} onClick={() => setView('sessions')}>按次</button>
        <button className={view === 'items' ? 'selected' : ''} onClick={() => setView('items')}>按词和题</button>
      </div>
      {loading && <p className="muted small">正在读取…</p>}
      {!loading && (view === 'sessions'
        ? <SessionList key={`${range.from}-${range.to}`} sessions={sessions} files={files} onChange={change} />
        : <ItemList key={`${range.from}-${range.to}`} files={files} range={range} onChange={change} />)}
    </Page>
  );
}

type Change = (kind: RecordKind, edit: { dictation?: (file: DictationFile) => DictationFile; errorBook?: (file: ErrorBookFile) => ErrorBookFile }, done: string) => Promise<void>;
type Files = { chinese?: DictationFile; english?: DictationFile; errorBook?: ErrorBookFile };

function SessionList({ sessions, files, onChange }: { sessions: SessionSummary[]; files: Files; onChange: Change }) {
  const { push } = useNavigation();
  const [shown, setShown] = useState(PAGE);
  const [open, setOpen] = useState<SessionSummary | null>(null);
  const [undoing, setUndoing] = useState<SessionSummary | null>(null);
  const groups = useMemo(() => {
    const map = new Map<string, SessionSummary[]>();
    for (const session of sessions.slice(0, shown)) {
      const date = new Date(session.ms);
      const key = `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
      map.set(key, [...(map.get(key) ?? []), session]);
    }
    return [...map];
  }, [sessions, shown]);

  /** Words of a dictation session in reading order, for "再听一遍". */
  const replay = (session: SessionSummary) => {
    const file = session.kind === 'chinese' ? files.chinese : files.english;
    const record = file?.sessions.find(item => item.id === session.id);
    if (!file || !record || session.kind === 'errorBook') return;
    const byId = new Map(file.words.map(word => [word.id, word]));
    const words = record.wordIds.map(id => byId.get(id)).filter(word => !!word).map(word => ({ text: word.text, translation: word.translation }));
    push({ name: 'dictation', language: session.kind, replay: { words, readMode: record.readMode ?? 'translation' } });
  };

  if (!sessions.length) return <p className="muted small">这段时间没有记录。</p>;
  return (
    <>
      {groups.map(([day, list]) => (
        <section key={day} className="stack-tight">
          <h2 className="section-title">{day}</h2>
          <div className="list">
            {list.map(session => (
              <button key={`${session.kind}-${session.id}`} className="list-row" onClick={() => setOpen(session)}>
                <span className="time">{formatTime(session.at).split(' ')[1]}</span>
                <span className="grow"><b>{sessionLabel(session)}</b><br /><span className="muted small ellipsis">{session.items.slice(0, 8).map(item => item.text).join('、')}</span></span>
                <span className={`score${session.wrong ? ' bad' : ''}`}>{session.sessionKind === 'capture' || session.sessionKind === 'added' || session.sessionKind === 'variation' ? `${session.total} ${session.kind === 'errorBook' ? '道' : '个'}` : `对 ${session.total - session.wrong} / ${session.total}`}</span>
              </button>
            ))}
          </div>
        </section>
      ))}
      {sessions.length > shown && <button className="button" onClick={() => setShown(shown + PAGE)}>再显示 {Math.min(PAGE, sessions.length - shown)} 次</button>}

      {open && (
        <Sheet title={`${sessionLabel(open)} · ${formatTime(open.at)}`} onClose={() => setOpen(null)}>
          <div className="list sheet-list">
            {open.items.map(item => (
              <div key={item.id} className="list-row">
                {item.correct ? <Check size={20} className="ok" aria-label="对" /> : <X size={20} className="bad" aria-label="错" />}
                <span className="grow">{item.text}{item.detail && <span className="muted small">　{item.detail}</span>}</span>
              </div>
            ))}
          </div>
          <div className="row">
            {open.kind !== 'errorBook' && <button className="button grow" onClick={() => { const session = open; setOpen(null); replay(session); }}><RotateCcw size={18} />再听一遍</button>}
            <button className="button danger grow" onClick={() => { setUndoing(open); setOpen(null); }}><Undo2 size={18} />撤销这次</button>
          </div>
        </Sheet>
      )}
      {undoing && (
        <ArithmeticConfirm title="撤销这次记录？" confirmLabel="撤销"
          message={<p>将删除 {formatTime(undoing.at)} 这次{sessionLabel(undoing)}的 {undoing.total} 条记录。只在这次出现过的{undoing.kind === 'errorBook' ? '题' : '词'}会一并删除。删除前的数据会保存在 data/history 里。</p>}
          onConfirm={() => onChange(undoing.kind, {
            dictation: file => dictation.undoSession(file, undoing.id),
            errorBook: file => errorBook.undoSession(file, undoing.id),
          }, '已撤销')}
          onClose={() => setUndoing(null)} />
      )}
    </>
  );
}

function ItemList({ files, range, onChange }: { files: Files; range: { from: number; to: number; label: string }; onChange: Change }) {
  const [kind, setKind] = useState<RecordKind>('chinese');
  const [shown, setShown] = useState(PAGE);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<ItemSummary | null>(null);
  const [deleting, setDeleting] = useState(false);
  useBackHandler(selecting, () => { setSelecting(false); setSelected(new Set()); });

  const all = useMemo(() => summarizeItems(files, range.from, range.to), [files.chinese, files.english, files.errorBook, range.from, range.to]);
  const counts = useMemo(() => ({ chinese: all.filter(item => item.kind === 'chinese').length, english: all.filter(item => item.kind === 'english').length, errorBook: all.filter(item => item.kind === 'errorBook').length }), [all]);
  const rows = useMemo(() => all.filter(item => item.kind === kind), [all, kind]);
  const unit = kind === 'errorBook' ? '道题' : '个词';
  const attempts = rows.filter(row => selected.has(row.id)).reduce((count, row) => count + row.total, 0);
  const toggle = (id: string) => setSelected(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  const switchKind = (next: RecordKind) => { setKind(next); setSelected(new Set()); setShown(PAGE); };

  const word = editing && editing.kind !== 'errorBook' ? files[editing.kind]?.words.find(item => item.id === editing.id) : undefined;
  const question = editing?.kind === 'errorBook' ? files.errorBook?.questions.find(item => item.id === editing.id) : undefined;

  return (
    <>
      <div className="row wrap">
        <div className="chips grow">
          {(['chinese', 'english', 'errorBook'] as const).map(value => (
            <button key={value} className={`chip${kind === value ? ' selected' : ''}`} onClick={() => switchKind(value)}>{KIND_LABELS[value]} {counts[value]}</button>
          ))}
        </div>
        {rows.length > 0 && <button className="button text" onClick={() => { setSelecting(!selecting); setSelected(new Set()); }}>{selecting ? '完成' : '选择'}</button>}
      </div>
      <p className="muted small">显示{range.label}里做过的{kind === 'errorBook' ? '题' : '词'}，"错 / 次"只算这段时间。点一下可以修改。</p>
      {!rows.length && <p className="muted small">这段时间没有{KIND_LABELS[kind]}的记录。</p>}
      {!!rows.length && (
        <div className="list">
          {rows.slice(0, shown).map(row => (
            <button key={row.id} className="list-row" aria-pressed={selecting ? selected.has(row.id) : undefined} onClick={() => (selecting ? toggle(row.id) : setEditing(row))}>
              {selecting && (selected.has(row.id) ? <CheckSquare size={22} className="ok" /> : <Square size={22} className="muted" />)}
              <span className="grow"><span className="ellipsis">{row.text}</span>{row.detail && <span className="muted small ellipsis">{row.detail}</span>}</span>
              <span className={`score${row.wrong ? ' bad' : ''}`}>错 {row.wrong} / {row.total}</span>
            </button>
          ))}
        </div>
      )}
      {rows.length > shown && <button className="button" onClick={() => setShown(shown + PAGE)}>再显示 {Math.min(PAGE, rows.length - shown)} 个</button>}
      {selecting && (
        <div className="row">
          <button className="button grow" onClick={() => setSelected(selected.size === rows.length ? new Set() : new Set(rows.map(row => row.id)))}>{selected.size === rows.length ? '取消全选' : '全选'}</button>
          <button className="button danger solid grow" disabled={!selected.size} onClick={() => setDeleting(true)}><Trash2 size={18} />删除这段时间的记录</button>
        </div>
      )}

      {word && editing && (
        <EditWord word={word} english={editing.kind === 'english'} onClose={() => setEditing(null)}
          onSave={async value => {
            const language = editing.kind as 'chinese' | 'english';
            const merging = files[language]!.words.some(item => item.id !== word.id && wordKey(item.text, language) === wordKey(value.text, language));
            await onChange(language, { dictation: file => dictation.editWord(file, word.id, value) }, merging ? `已和"${value.text}"合并` : '已保存');
          }} />
      )}
      {question && (
        <QuestionEditor title="修改错题" text={question.text} answer={question.answer} onClose={() => setEditing(null)}
          onSave={value => onChange('errorBook', { errorBook: file => errorBook.editQuestion(file, question.id, value) }, '已保存')} />
      )}
      {deleting && (
        <ArithmeticConfirm title={`删除 ${selected.size} ${unit}在${range.label}的记录？`} confirmLabel="删除"
          message={<p>将删除这些{unit.slice(1)}在{range.label}里的 {attempts} 条记录，这段时间以外的记录保留。没有其他记录的{unit.slice(1)}会从{kind === 'errorBook' ? '错题本' : '错词本'}里删除。删除前的数据会保存在 data/history 里。</p>}
          onConfirm={async () => {
            const ids = new Set(selected);
            await onChange(kind, {
              dictation: file => dictation.deleteAttemptsOf(file, ids, range.from, range.to),
              errorBook: file => errorBook.deleteAttemptsOf(file, ids, range.from, range.to),
            }, `已删除 ${attempts} 条记录`);
            setSelecting(false);
            setSelected(new Set());
          }}
          onClose={() => setDeleting(false)} />
      )}
    </>
  );
}

const percent = (rate: number | null) => (rate === null ? '—' : `${Math.round(rate * 100)}%`);
