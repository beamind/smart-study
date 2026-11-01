import { CheckSquare, Plus, Search, Square, Trash2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { wordKey } from '../../data/normalize';
import type { DictationLanguage, DictationWord } from '../../data/schema';
import { addWords, deleteWords, editWord, sortForBook, wordRows } from '../../domain/dictation';
import { SaveInterruptedError } from '../../storage/jsonFiles';
import { ArithmeticConfirm, errorText, notSavedText, Page, Sheet, useToast } from '../../ui/components';
import { formatRelative } from '../../ui/format';
import { useDictationFile } from '../useUserData';

type Filter = 'all' | 'wrong' | 'recent';
const PAGE = 200;

/** 错词本: every word ever dictated, including those never written wrong, with how often it was written wrong. */
export function WordBook({ language }: { language: DictationLanguage }) {
  const toast = useToast();
  const { entry, update } = useDictationFile(language);
  const file = entry?.status === 'ok' ? entry.value : undefined;
  const english = language === 'english';
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [shown, setShown] = useState(PAGE);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<DictationWord | null>(null);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<Set<string> | null>(null);
  useBackHandler(selecting, () => { setSelecting(false); setSelected(new Set()); });

  const rows = useMemo(() => {
    if (!file) return [];
    const needle = query.trim() ? wordKey(query, language) : '';
    const filtered = wordRows(file)
      .filter(row => !needle || wordKey(row.word.text, language).includes(needle) || (row.word.translation ?? '').includes(query.trim()))
      .filter(row => filter !== 'wrong' || row.stats.wrong > 0);
    return filter === 'recent'
      ? filtered.sort((a, b) => Date.parse(b.word.createdAt) - Date.parse(a.word.createdAt))
      : sortForBook(filtered, Date.now());
  }, [file, query, filter, language]);

  // An interrupted save is kept and written by the next save or start: it counts as done, with its own message.
  const save = async (change: Parameters<typeof update>[0], done: string) => {
    try { await update(change); toast.show(done); } catch (error) { toast.show(notSavedText(error)); if (!(error instanceof SaveInterruptedError)) throw error; }
  };
  const toggle = (id: string) => setSelected(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });

  return (
    <Page title={`${english ? '英语' : '语文'}错词本`}
      actions={file && file.words.length > 0 && (
        <button className="button text" onClick={() => { setSelecting(!selecting); setSelected(new Set()); }}>{selecting ? '完成' : '选择'}</button>
      )}
      footer={selecting
        ? <>
            <button className="button grow" onClick={() => setSelected(selected.size === rows.length ? new Set() : new Set(rows.map(row => row.word.id)))}>
              {selected.size === rows.length && rows.length ? '取消全选' : '全选'}
            </button>
            <button className="button danger solid grow" disabled={!selected.size} onClick={() => setDeleting(selected)}><Trash2 size={18} />删除 {selected.size} 个</button>
          </>
        : <button className="button primary grow" disabled={!file} onClick={() => setAdding(true)}><Plus size={20} />添加词语</button>}>
      {entry?.status === 'unusable' && <div className="notice error">{entry.error}</div>}
      <div className="search">
        <Search size={18} className="muted" />
        <input placeholder={english ? '搜索单词或译文' : '搜索词语'} value={query} onChange={event => { setQuery(event.target.value); setShown(PAGE); setSelected(new Set()); }} />
      </div>
      <div className="chips">
        {([['all', '全部'], ['wrong', '错过的'], ['recent', '最近添加']] as const).map(([value, label]) => (
          <button key={value} className={`chip${filter === value ? ' selected' : ''}`} onClick={() => { setFilter(value); setShown(PAGE); setSelected(new Set()); }}>{label}</button>
        ))}
        {file && <span className="muted small" style={{ alignSelf: 'center' }}>{rows.length} 个</span>}
      </div>
      {!entry && <p className="muted small">正在读取…</p>}
      {file && !rows.length && <p className="muted small">{file.words.length ? '没有符合条件的词。' : '还没有词语。听写提交后会自动记录，也可以手动添加。'}</p>}
      {!!rows.length && (
        <div className="list">
          {rows.slice(0, shown).map(({ word, stats }) => (
            <button key={word.id} className="list-row" onClick={() => (selecting ? toggle(word.id) : setEditing(word))} aria-pressed={selecting ? selected.has(word.id) : undefined}>
              {selecting && (selected.has(word.id) ? <CheckSquare size={22} className="ok" /> : <Square size={22} className="muted" />)}
              <span className="grow">
                <b>{word.text}</b>{word.translation && <span className="muted">　{word.translation}</span>}<br />
                <span className="muted small">{stats.lastMs ? `最近 ${formatRelative(new Date(stats.lastMs).toISOString())}` : ''}</span>
              </span>
              <span className={`score${stats.wrong ? ' bad' : ''}`}>错 {stats.wrong} / {stats.total}</span>
            </button>
          ))}
        </div>
      )}
      {rows.length > shown && <button className="button" onClick={() => setShown(shown + PAGE)}>再显示 {Math.min(PAGE, rows.length - shown)} 个</button>}

      {editing && (
        <EditWord word={editing} english={english} onClose={() => setEditing(null)}
          onSave={async change => {
            const merging = file!.words.some(item => item.id !== editing.id && wordKey(item.text, language) === wordKey(change.text, language));
            await save(value => ({ value: editWord(value, editing.id, change), result: undefined }), merging ? `已和"${change.text}"合并` : '已保存');
          }}
          onDelete={() => { setDeleting(new Set([editing.id])); setEditing(null); }} />
      )}
      {adding && (
        <AddWords english={english} onClose={() => setAdding(false)} onAdd={async entries => {
          try {
            const outcome = await update(value => { const result = addWords(value, entries); return { value: result.file, result }; });
            toast.show(outcome.skipped.length ? `添加了 ${outcome.added} 个，${outcome.skipped.length} 个已在错词本里` : `添加了 ${outcome.added} 个`);
          } catch (error) {
            if (!(error instanceof SaveInterruptedError)) throw error;
            toast.show(errorText(error));
          }
        }} />
      )}
      {deleting && (
        <ArithmeticConfirm title={`删除 ${deleting.size} 个词？`} confirmLabel="删除"
          message={<p>这些词和它们的全部听写记录都会删除。删除前的数据会保存在 data/history 里。</p>}
          onConfirm={async () => { await save(value => ({ value: deleteWords(value, deleting), result: undefined }), `已删除 ${deleting.size} 个词`); setSelecting(false); setSelected(new Set()); }}
          onClose={() => setDeleting(null)} />
      )}
    </Page>
  );
}

export function EditWord({ word, english, onSave, onDelete, onClose }: {
  word: DictationWord; english: boolean; onSave(change: { text: string; translation?: string }): Promise<void>; onDelete?(): void; onClose(): void;
}) {
  const [text, setText] = useState(word.text);
  const [translation, setTranslation] = useState(word.translation ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <Sheet title="修改词语" onClose={onClose} keepOnOutsideTap>
      <label className="field"><span>{english ? '单词或短语' : '词语'}</span>
        <input className="input" value={text} onChange={event => setText(event.target.value)} autoFocus /></label>
      {english && <label className="field"><span>译文</span><input className="input" value={translation} onChange={event => setTranslation(event.target.value)} /></label>}
      <p className="muted small">改成错词本里已有的词时，两者的记录会合并。</p>
      {error && <div className="notice error">{error}</div>}
      <div className="row">
        {onDelete && <button className="button danger" onClick={onDelete}><Trash2 size={18} />删除</button>}
        <span style={{ flex: 1 }} />
        <button className="button" onClick={onClose}>取消</button>
        <button className="button primary" disabled={!text.trim() || busy} onClick={async () => {
          setBusy(true);
          try { await onSave({ text, ...(english ? { translation } : {}) }); onClose(); } catch (problem) { setError(errorText(problem)); setBusy(false); }
        }}>保存</button>
      </div>
    </Sheet>
  );
}

function AddWords({ english, onAdd, onClose }: { english: boolean; onAdd(entries: { text: string; translation?: string }[]): Promise<void>; onClose(): void }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // English lines may carry a translation after "=", e.g. "take care = 保重".
  const entries = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean).flatMap(line => {
    if (english) { const [word, translation] = line.split('='); return [{ text: word.trim(), translation: translation?.trim() }]; }
    return line.split(/[，,、；;\s]+/).filter(Boolean).map(word => ({ text: word }));
  }).filter(entry => entry.text);
  return (
    <Sheet title="添加词语" onClose={onClose} keepOnOutsideTap>
      <p className="muted small">每行一个。添加的词记为错过一次，会参与错词听写。{english && '可以用"=" 写上译文，例如 take care = 保重。'}</p>
      <textarea className="input" autoFocus value={text} onChange={event => setText(event.target.value)} />
      {error && <div className="notice error">{error}</div>}
      <button className="button primary" disabled={!entries.length || busy} onClick={async () => {
        setBusy(true);
        setError('');
        try { await onAdd(entries); onClose(); } catch (problem) { setError(`没有保存：${errorText(problem)}`); setBusy(false); }
      }}>添加 {entries.length} 个</button>
    </Sheet>
  );
}
