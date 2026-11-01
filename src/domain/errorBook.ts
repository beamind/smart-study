import { nowIso } from '../core/time';
import { questionKey, similarity } from '../data/normalize';
import type { ErrorBookFile, ErrorBookSource, Question, QuestionOrigin } from '../data/schema';
import { itemStats, sessionTimes, type ItemStats } from './stats';

/**
 * Changes to the error book file. Like domain/dictation.ts, every function returns a new file and
 * leaves the input untouched, so the caller saves the result in one write.
 */

const newId = (prefix: string) => `${prefix}_${crypto.randomUUID().replace(/-/g, '').slice(0, 20)}`;

/** Keeps line breaks, trims each line and drops empty lines at the start and end. */
export function cleanQuestionText(text: string): string {
  // Simple anchored patterns only: a pattern like /\n+\s*$/ backtracks badly on thousands of blank lines.
  return text.replace(/\r\n?/g, '\n').split('\n').map(line => line.trimEnd()).join('\n').replace(/^\n+/, '').replace(/\n+$/, '').trim();
}

export interface NewQuestion {
  text: string;
  answer: string;
  origin: QuestionOrigin;
  sourceQuestionId?: string;
}

/** A new session id, e.g. for a practice draft that will be submitted later. */
export const newSessionId = () => newId('s');

const unused = (file: ErrorBookFile, sessionId: string) => {
  if (file.sessions.some(session => session.id === sessionId)) throw new Error('这次已经保存过了');
  return sessionId;
};

/**
 * Record wrong questions in one session: "capture" (录入) or "variation" (举一反三 questions answered wrong).
 * A question equal to one already in the book (same questionKey) gets the attempt instead of a copy.
 * `sessionId` is given when it was chosen in advance (a draft), so a submission saved already is recognised.
 */
export function addQuestions(file: ErrorBookFile, kind: Extract<ErrorBookSource, 'capture' | 'variation'>, items: NewQuestion[], at = nowIso(), id?: string):
  { file: ErrorBookFile; sessionId: string; created: number; existing: number } {
  const sessionId = unused(file, id ?? newId('s'));
  const questions = file.questions.map(question => ({ ...question, attempts: [...question.attempts] }));
  const byKey = new Map(questions.map(question => [questionKey(question.text), question]));
  const order: string[] = [];
  let created = 0;
  let existing = 0;
  for (const item of items) {
    const text = cleanQuestionText(item.text);
    if (!text) continue;
    const key = questionKey(text);
    let question = byKey.get(key);
    if (question && order.includes(question.id)) continue; // the same question twice in one session counts once
    if (question) existing++;
    else {
      question = {
        id: newId('q'), text, answer: item.answer.trim(), origin: item.origin,
        ...(item.sourceQuestionId ? { sourceQuestionId: item.sourceQuestionId } : {}),
        createdAt: at, attempts: [],
      };
      questions.push(question);
      byKey.set(key, question);
      created++;
    }
    question.attempts.push({ sessionId, correct: false });
    order.push(question.id);
  }
  if (!order.length) throw new Error('没有题目，不能保存');
  return { sessionId, created, existing, file: { ...file, questions, sessions: [...file.sessions, { id: sessionId, kind, at, questionIds: order }] } };
}

/** Record one practice: one attempt per question, in the order shown. `id` as in addQuestions. */
export function recordPractice(file: ErrorBookFile, results: { questionId: string; correct: boolean }[], at = nowIso(), id?: string): { file: ErrorBookFile; sessionId: string } {
  const sessionId = unused(file, id ?? newId('s'));
  const outcome = new Map<string, boolean>();
  for (const result of results) if (!outcome.has(result.questionId)) outcome.set(result.questionId, result.correct);
  const known = new Set(file.questions.map(question => question.id));
  const missing = [...outcome.keys()].filter(id => !known.has(id));
  if (missing.length) throw new Error(`有 ${missing.length} 道题已经不在错题本里了，可能刚被删除`);
  if (!outcome.size) throw new Error('没有题目，不能保存');
  return {
    sessionId,
    file: {
      ...file,
      questions: file.questions.map(question => outcome.has(question.id)
        ? { ...question, attempts: [...question.attempts, { sessionId, correct: outcome.get(question.id)! }] }
        : question),
      sessions: [...file.sessions, { id: sessionId, kind: 'practice', at, questionIds: [...outcome.keys()] }],
    },
  };
}

/** Remove a session and its attempts ("撤销这次"); questions left without attempts are removed too. */
export function undoSession(file: ErrorBookFile, sessionId: string): ErrorBookFile {
  if (!file.sessions.some(session => session.id === sessionId)) throw new Error('找不到这次记录，可能已经撤销过了');
  return prune({
    ...file,
    sessions: file.sessions.filter(session => session.id !== sessionId),
    questions: file.questions.map(question => ({ ...question, attempts: question.attempts.filter(attempt => attempt.sessionId !== sessionId) })),
  });
}

/** Delete whole questions with all their history. */
export function deleteQuestions(file: ErrorBookFile, ids: Set<string>): ErrorBookFile {
  return prune({
    ...file,
    questions: file.questions.filter(question => !ids.has(question.id)),
    sessions: file.sessions.map(session => ({ ...session, questionIds: session.questionIds.filter(id => !ids.has(id)) })),
  });
}

/** Delete attempts in [fromMs, toMs) (学习记录按日期删除); questions and sessions left empty are removed. */
export function deleteAttemptsBetween(file: ErrorBookFile, fromMs: number, toMs: number): ErrorBookFile {
  return deleteAttemptsOf(file, null, fromMs, toMs);
}

/** Delete the attempts of some questions (null: all) made in [fromMs, toMs), for the learning record's per-question view. */
export function deleteAttemptsOf(file: ErrorBookFile, ids: Set<string> | null, fromMs: number, toMs: number): ErrorBookFile {
  const inRange = new Set(file.sessions.filter(session => { const ms = Date.parse(session.at); return ms >= fromMs && ms < toMs; }).map(session => session.id));
  return prune({
    ...file,
    questions: file.questions.map(question => (ids && !ids.has(question.id) ? question : { ...question, attempts: question.attempts.filter(attempt => !inRange.has(attempt.sessionId)) })),
  });
}

/** Edit a question's text and/or answer. */
export function editQuestion(file: ErrorBookFile, id: string, change: { text?: string; answer?: string }): ErrorBookFile {
  const question = file.questions.find(item => item.id === id);
  if (!question) throw new Error('找不到这道题，可能已经删除');
  const text = cleanQuestionText(change.text ?? question.text);
  if (!text) throw new Error('题目不能为空');
  const answer = (change.answer ?? question.answer).trim();
  return { ...file, questions: file.questions.map(item => (item.id === id ? { ...item, text, answer } : item)) };
}

/** Drop questions without attempts, then sessions without attempts; a session lists only questions answered in it. */
function prune(file: ErrorBookFile): ErrorBookFile {
  const questions = file.questions.filter(question => question.attempts.length > 0);
  const answered = new Map<string, Set<string>>();
  for (const question of questions) for (const attempt of question.attempts) (answered.get(attempt.sessionId) ?? answered.set(attempt.sessionId, new Set()).get(attempt.sessionId)!).add(question.id);
  return {
    ...file,
    questions,
    sessions: file.sessions.filter(session => answered.has(session.id)).map(session => ({ ...session, questionIds: session.questionIds.filter(id => answered.get(session.id)!.has(id)) })),
  };
}

export interface QuestionRow { question: Question; stats: ItemStats }

export const questionRows = (file: ErrorBookFile): QuestionRow[] => {
  const times = sessionTimes(file);
  return file.questions.map(question => ({ question, stats: itemStats(question.attempts, times) }));
};

/** 错题本 order: highest error rate first, then most recently practised. */
export function sortForBook(rows: QuestionRow[]): QuestionRow[] {
  const rate = (row: QuestionRow) => (row.stats.total ? row.stats.wrong / row.stats.total : 0);
  return [...rows].sort((a, b) => rate(b) - rate(a) || b.stats.lastMs - a.stats.lastMs);
}

export const DUPLICATE_SIMILARITY = 0.9;

export type DuplicateMark = { kind: 'same' | 'similar'; question: Question } | null;

/**
 * Duplicate check when capturing: compare a new question with the book, ignoring underline marks, spacing,
 * punctuation and case. Equal keys are "已在错题本"; similarity ≥ 0.9 is "可能重复".
 */
export function findDuplicate(text: string, questions: Question[]): DuplicateMark {
  const key = questionKey(text);
  if (!key) return null;
  let best: { question: Question; score: number } | null = null;
  for (const question of questions) {
    const other = questionKey(question.text);
    if (other === key) return { kind: 'same', question };
    // Similarity cannot reach the threshold when the lengths differ too much; skip the expensive part.
    if (Math.min(other.length, key.length) / Math.max(other.length, key.length) < DUPLICATE_SIMILARITY) continue;
    const score = similarity(key, other);
    if (score >= DUPLICATE_SIMILARITY && (!best || score > best.score)) best = { question, score };
  }
  return best ? { kind: 'similar', question: best.question } : null;
}
