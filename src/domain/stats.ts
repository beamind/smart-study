import type { Attempt, DictationFile, ErrorBookFile } from '../data/schema';
import { plainQuestion } from './underline';

export interface ItemStats {
  total: number;
  wrong: number;
  /** Time of the latest attempt, or 0. */
  lastMs: number;
}

/** Counts for one word or question; session times come from the file's sessions. */
export function itemStats(attempts: Attempt[], sessionTime: Map<string, number>): ItemStats {
  let wrong = 0;
  let lastMs = 0;
  for (const attempt of attempts) {
    if (!attempt.correct) wrong++;
    lastMs = Math.max(lastMs, sessionTime.get(attempt.sessionId) ?? 0);
  }
  return { total: attempts.length, wrong, lastMs };
}

export const sessionTimes = (file: DictationFile | ErrorBookFile) =>
  new Map(file.sessions.map(session => [session.id, Date.parse(session.at)]));

export type RecordKind = 'chinese' | 'english' | 'errorBook';

export interface SessionSummary {
  kind: RecordKind;
  id: string;
  sessionKind: string;
  at: string;
  ms: number;
  total: number;
  wrong: number;
  items: { id: string; text: string; detail?: string; correct: boolean }[];
}

/** One row per session ("一次学习"), newest first. */
export function summarizeSessions(files: { chinese?: DictationFile; english?: DictationFile; errorBook?: ErrorBookFile }): SessionSummary[] {
  const result: SessionSummary[] = [];
  const add = (kind: RecordKind, sessions: { id: string; kind: string; at: string }[], items: { id: string; text: string; detail?: string; attempts: Attempt[] }[]) => {
    const bySession = new Map<string, SessionSummary>();
    for (const session of sessions) {
      bySession.set(session.id, { kind, id: session.id, sessionKind: session.kind, at: session.at, ms: Date.parse(session.at), total: 0, wrong: 0, items: [] });
    }
    for (const item of items) {
      for (const attempt of item.attempts) {
        const summary = bySession.get(attempt.sessionId);
        if (!summary) continue;
        summary.total++;
        if (!attempt.correct) summary.wrong++;
        summary.items.push({ id: item.id, text: item.text, detail: item.detail, correct: attempt.correct });
      }
    }
    result.push(...[...bySession.values()].filter(summary => summary.total > 0));
  };
  if (files.chinese) add('chinese', files.chinese.sessions, files.chinese.words);
  if (files.english) add('english', files.english.sessions, files.english.words.map(word => ({ ...word, detail: word.translation })));
  if (files.errorBook) add('errorBook', files.errorBook.sessions, files.errorBook.questions.map(question => ({ ...question, text: plainQuestion(question.text), detail: question.answer })));
  return result.sort((a, b) => b.ms - a.ms);
}

/**
 * Totals for the learning-record header: words or questions, sessions and accuracy. Only sessions where
 * something was answered count: capturing questions, adding words and saving 举一反三 questions record a
 * wrong attempt by design, which would pull the accuracy down.
 */
export function totals(sessions: SessionSummary[]) {
  const answered: Record<RecordKind, string[]> = { chinese: ['dictation', 'review', 'legacy'], english: ['dictation', 'review', 'legacy'], errorBook: ['practice', 'legacy'] };
  const sum = (kinds: RecordKind[]) => {
    const picked = sessions.filter(session => kinds.includes(session.kind) && answered[session.kind].includes(session.sessionKind));
    const total = picked.reduce((count, session) => count + session.total, 0);
    const wrong = picked.reduce((count, session) => count + session.wrong, 0);
    return { sessions: picked.length, total, wrong, rate: total ? (total - wrong) / total : null };
  };
  return { dictation: sum(['chinese', 'english']), errorBook: sum(['errorBook']) };
}

export interface ItemSummary {
  kind: RecordKind;
  id: string;
  text: string;
  detail?: string;
  /** Attempts in the range only. */
  total: number;
  wrong: number;
  lastMs: number;
}

/**
 * The learning record's second view: every word or question answered in [fromMs, toMs), with its
 * counts in that range only. Most errors first, then most recent.
 */
export function summarizeItems(files: { chinese?: DictationFile; english?: DictationFile; errorBook?: ErrorBookFile }, fromMs: number, toMs: number): ItemSummary[] {
  const result: ItemSummary[] = [];
  const add = (kind: RecordKind, file: DictationFile | ErrorBookFile, items: { id: string; text: string; detail?: string; attempts: Attempt[] }[]) => {
    const times = sessionTimes(file);
    for (const item of items) {
      const picked = item.attempts.filter(attempt => { const ms = times.get(attempt.sessionId) ?? 0; return ms >= fromMs && ms < toMs; });
      if (!picked.length) continue;
      const stats = itemStats(picked, times);
      result.push({ kind, id: item.id, text: item.text, detail: item.detail, ...stats });
    }
  };
  if (files.chinese) add('chinese', files.chinese, files.chinese.words);
  if (files.english) add('english', files.english, files.english.words.map(word => ({ ...word, detail: word.translation })));
  if (files.errorBook) add('errorBook', files.errorBook, files.errorBook.questions.map(question => ({ ...question, text: plainQuestion(question.text), detail: question.answer })));
  return result.sort((a, b) => b.wrong - a.wrong || b.lastMs - a.lastMs);
}
