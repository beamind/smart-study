import { nowIso } from '../core/time';
import { wordKey } from '../data/normalize';
import type { Attempt, DictationFile, DictationSource, DictationWord, ReadMode } from '../data/schema';
import { itemStats, sessionTimes, type ItemStats } from './stats';

/**
 * Changes to one dictation file. Every function returns a new file and leaves the input untouched,
 * so the caller can save the result in one write and keep the old value if saving fails.
 */

const newId = (prefix: string) => `${prefix}_${crypto.randomUUID().replace(/-/g, '').slice(0, 20)}`;

export interface DictatedWord {
  text: string;
  translation?: string;
  correct: boolean;
}

export interface Submission {
  kind: Exclude<DictationSource, 'legacy'>;
  /** In reading order. */
  words: DictatedWord[];
  readMode?: ReadMode;
  at?: string;
}

/** Index of words by their comparison key. */
export const wordIndex = (file: DictationFile) => new Map(file.words.map(word => [wordKey(word.text, file.language), word]));

/** Record one dictation: one session, one attempt per word; new words are created, known words get the attempt. */
export function submit(file: DictationFile, submission: Submission): { file: DictationFile; sessionId: string } {
  if (!submission.words.length) throw new Error('没有词语，不能保存');
  const at = submission.at ?? nowIso();
  const sessionId = newId('s');
  const byKey = wordIndex(file);
  const words = file.words.map(word => ({ ...word, attempts: [...word.attempts] }));
  const byId = new Map(words.map(word => [word.id, word]));
  const order: string[] = [];
  /** The attempt this dictation added, per word key; a word listed twice counts once. */
  const added = new Map<string, Attempt>();

  for (const entry of submission.words) {
    const text = entry.text.trim().replace(/\s+/g, ' ');
    if (!text) continue;
    const key = wordKey(text, file.language);
    const already = added.get(key);
    if (already) {
      // The same word twice in one dictation counts once, as wrong if either was written wrong.
      if (!entry.correct) already.correct = false;
      continue;
    }
    const translation = file.language === 'english' ? entry.translation?.trim() : undefined;
    let word = byKey.get(key) && byId.get(byKey.get(key)!.id);
    if (!word) {
      word = { id: newId('w'), text, createdAt: at, attempts: [] };
      words.push(word);
      byId.set(word.id, word);
    }
    if (translation) word.translation = translation;
    const attempt: Attempt = { sessionId, correct: entry.correct };
    word.attempts.push(attempt);
    added.set(key, attempt);
    order.push(word.id);
  }
  if (!order.length) throw new Error('没有词语，不能保存');

  return {
    sessionId,
    file: {
      ...file,
      words,
      sessions: [...file.sessions, {
        id: sessionId, kind: submission.kind, at, wordIds: order,
        ...(file.language === 'english' && submission.readMode ? { readMode: submission.readMode } : {}),
      }],
    },
  };
}

/** Remove a session and its attempts ("撤销这次"); words left without attempts are removed too. */
export function undoSession(file: DictationFile, sessionId: string): DictationFile {
  if (!file.sessions.some(session => session.id === sessionId)) throw new Error('找不到这次记录，可能已经撤销过了');
  return prune({
    ...file,
    sessions: file.sessions.filter(session => session.id !== sessionId),
    words: file.words.map(word => ({ ...word, attempts: word.attempts.filter(attempt => attempt.sessionId !== sessionId) })),
  });
}

/** Delete whole words with all their history. */
export function deleteWords(file: DictationFile, ids: Set<string>): DictationFile {
  return prune({
    ...file,
    words: file.words.filter(word => !ids.has(word.id)),
    sessions: file.sessions.map(session => ({ ...session, wordIds: session.wordIds.filter(id => !ids.has(id)) })),
  });
}

/** Delete attempts in [fromMs, toMs) (学习记录按日期删除). */
export function deleteAttemptsBetween(file: DictationFile, fromMs: number, toMs: number): DictationFile {
  return deleteAttemptsOf(file, null, fromMs, toMs);
}

/**
 * Delete the attempts of some words (null: all words) made in [fromMs, toMs). Used by the learning
 * record's per-word view, where deleting only touches the chosen date range.
 */
export function deleteAttemptsOf(file: DictationFile, ids: Set<string> | null, fromMs: number, toMs: number): DictationFile {
  const inRange = new Set(file.sessions.filter(session => { const ms = Date.parse(session.at); return ms >= fromMs && ms < toMs; }).map(session => session.id));
  return prune({
    ...file,
    words: file.words.map(word => (ids && !ids.has(word.id) ? word : { ...word, attempts: word.attempts.filter(attempt => !inRange.has(attempt.sessionId)) })),
  });
}

/**
 * Manually add words to the word book (错词本"添加"): each new word gets one wrong attempt in an "added"
 * session, so it takes part in review. Words already in the book are skipped.
 */
export function addWords(file: DictationFile, entries: { text: string; translation?: string }[]): { file: DictationFile; added: number; skipped: string[] } {
  const known = wordIndex(file);
  const fresh: DictatedWord[] = [];
  const skipped: string[] = [];
  const seen = new Set<string>();
  for (const entry of entries) {
    const text = entry.text.trim().replace(/\s+/g, ' '); // same normalisation as submit, so the key matches
    if (!text) continue;
    const key = wordKey(text, file.language);
    if (known.has(key) || seen.has(key)) { skipped.push(text); continue; }
    seen.add(key);
    fresh.push({ text, translation: entry.translation, correct: false });
  }
  if (!fresh.length) return { file, added: 0, skipped };
  return { file: submit(file, { kind: 'added', words: fresh }).file, added: fresh.length, skipped };
}

/**
 * Edit a word's text and/or translation. When the new text equals another word (after normalising),
 * the two are merged: attempts are combined and sessions point to the remaining word.
 */
export function editWord(file: DictationFile, id: string, change: { text?: string; translation?: string }): DictationFile {
  const word = file.words.find(item => item.id === id);
  if (!word) throw new Error('找不到这个词');
  const text = (change.text ?? word.text).trim().replace(/\s+/g, ' ');
  if (!text) throw new Error('词语不能为空');
  const translation = change.translation === undefined ? word.translation : change.translation.trim() || undefined;
  const other = file.words.find(item => item.id !== id && wordKey(item.text, file.language) === wordKey(text, file.language));

  if (!other) {
    return { ...file, words: file.words.map(item => item.id === id ? withTranslation({ ...item, text }, file, translation) : item) };
  }
  // Merge into the other word, keeping attempts in time order. When both words were in the same dictation,
  // that dictation keeps one attempt (wrong if either was wrong), as submit counts a word once per dictation.
  const times = sessionTimes(file);
  const bySession = new Map<string, Attempt>();
  for (const attempt of [...other.attempts, ...word.attempts]) {
    const seen = bySession.get(attempt.sessionId);
    bySession.set(attempt.sessionId, seen ? { ...seen, correct: seen.correct && attempt.correct } : attempt);
  }
  const attempts = [...bySession.values()].sort((a, b) => (times.get(a.sessionId) ?? 0) - (times.get(b.sessionId) ?? 0));
  const merged = withTranslation({
    ...other, text, attempts,
    createdAt: Date.parse(word.createdAt) < Date.parse(other.createdAt) ? word.createdAt : other.createdAt,
  }, file, translation ?? other.translation);
  return {
    ...file,
    words: file.words.filter(item => item.id !== id).map(item => item.id === other.id ? merged : item),
    sessions: file.sessions.map(session => ({ ...session, wordIds: dedupe(session.wordIds.map(wordId => wordId === id ? other.id : wordId)) })),
  };
}

function withTranslation(word: DictationWord, file: DictationFile, translation: string | undefined): DictationWord {
  const { translation: _old, ...rest } = word;
  return file.language === 'english' && translation ? { ...rest, translation } : rest;
}

const dedupe = (ids: string[]) => [...new Set(ids)];

/** Drop words without attempts, then sessions without attempts; a session lists only words answered in it. */
function prune(file: DictationFile): DictationFile {
  const words = file.words.filter(word => word.attempts.length > 0);
  const answered = new Map<string, Set<string>>();
  for (const word of words) for (const attempt of word.attempts) (answered.get(attempt.sessionId) ?? answered.set(attempt.sessionId, new Set()).get(attempt.sessionId)!).add(word.id);
  return {
    ...file,
    words,
    sessions: file.sessions.filter(session => answered.has(session.id)).map(session => ({ ...session, wordIds: session.wordIds.filter(id => answered.get(session.id)!.has(id)) })),
  };
}

export interface WordRow { word: DictationWord; stats: ItemStats }

export const wordRows = (file: DictationFile): WordRow[] => {
  const times = sessionTimes(file);
  return file.words.map(word => ({ word, stats: itemStats(word.attempts, times) }));
};

/** 错词本 order: words practised in the last hour go last, then by error rate, then most recent first. */
export function sortForBook(rows: WordRow[], nowMs: number): WordRow[] {
  const recent = (row: WordRow) => nowMs - row.stats.lastMs < 3_600_000;
  const rate = (row: WordRow) => (row.stats.total ? row.stats.wrong / row.stats.total : 0);
  return [...rows].sort((a, b) => Number(recent(a)) - Number(recent(b)) || rate(b) - rate(a) || b.stats.lastMs - a.stats.lastMs);
}
