import { describe, expect, it } from 'vitest';
import { emptyErrorBookFile } from '../data/defaults';
import { checkErrorBookFile } from '../data/validate';
import { addQuestions, cleanQuestionText, deleteAttemptsBetween, deleteAttemptsOf, deleteQuestions, editQuestion, findDuplicate, questionRows, recordPractice, sortForBook, undoSession } from './errorBook';

const valid = (file: unknown) => expect(checkErrorBookFile(file)).toMatchObject({ ok: true });
const photo = (text: string, answer = 'A') => ({ text, answer, origin: 'photo' as const });

describe('error book changes', () => {
  it('capture creates questions with one wrong attempt and reuses equal questions', () => {
    const one = addQuestions(emptyErrorBookFile(), 'capture', [photo('It was __foggy__ yesterday.', 'What was the weather like?'), photo('Tom ______ (go) to school.', 'goes')], '2026-10-01T19:00:00+08:00');
    expect(one).toMatchObject({ created: 2, existing: 0 });
    const two = addQuestions(one.file, 'capture', [photo('it was foggy yesterday'), photo('It was foggy yesterday!'), photo('New one.')], '2026-10-02T19:00:00+08:00');
    expect(two).toMatchObject({ created: 1, existing: 1 });
    valid(two.file);
    const foggy = two.file.questions[0];
    expect(foggy).toMatchObject({ text: 'It was __foggy__ yesterday.', answer: 'What was the weather like?' });
    expect(foggy.attempts).toEqual([{ sessionId: one.sessionId, correct: false }, { sessionId: two.sessionId, correct: false }]);
    expect(two.file.sessions.at(-1)).toMatchObject({ kind: 'capture', questionIds: [foggy.id, two.file.questions[2].id] });
    expect(one.file.questions[0].attempts).toHaveLength(1); // input untouched
    expect(() => addQuestions(one.file, 'capture', [photo('  ')])).toThrow();
  });

  it('variations keep their source question', () => {
    const base = addQuestions(emptyErrorBookFile(), 'capture', [photo('Q1')]);
    const source = base.file.questions[0].id;
    const result = addQuestions(base.file, 'variation', [{ text: 'Q1 new', answer: 'B', origin: 'generated', sourceQuestionId: source }]);
    valid(result.file);
    expect(result.file.questions[1]).toMatchObject({ origin: 'generated', sourceQuestionId: source });
    expect(result.file.sessions[1].kind).toBe('variation');
  });

  it('practice records one attempt per question and rejects deleted questions', () => {
    const base = addQuestions(emptyErrorBookFile(), 'capture', [photo('Q1'), photo('Q2')]).file;
    const [q1, q2] = base.questions;
    const practised = recordPractice(base, [{ questionId: q2.id, correct: true }, { questionId: q1.id, correct: false }]);
    valid(practised.file);
    expect(practised.file.sessions.at(-1)).toMatchObject({ kind: 'practice', questionIds: [q2.id, q1.id] });
    expect(practised.file.questions.map(question => question.attempts.map(attempt => attempt.correct))).toEqual([[false, false], [false, true]]);
    expect(() => recordPractice(base, [{ questionId: 'gone', correct: true }])).toThrow(/不在错题本/);
  });

  it('undo restores the file before the session', () => {
    const one = addQuestions(emptyErrorBookFile(), 'capture', [photo('Q1')]);
    const two = addQuestions(one.file, 'capture', [photo('Q1'), photo('Q2')]);
    expect(undoSession(two.file, two.sessionId)).toEqual(one.file);
    const practised = recordPractice(one.file, [{ questionId: one.file.questions[0].id, correct: true }]);
    expect(undoSession(practised.file, practised.sessionId)).toEqual(one.file);
    expect(() => undoSession(one.file, 'nope')).toThrow();
  });

  it('edit and delete', () => {
    const base = addQuestions(emptyErrorBookFile(), 'capture', [photo('Q1'), photo('Q2')]).file;
    const edited = editQuestion(base, base.questions[0].id, { text: '  Q1 edited \r\nA. x  \n\n', answer: ' B ' });
    expect(edited.questions[0]).toMatchObject({ text: 'Q1 edited\nA. x', answer: 'B' });
    expect(() => editQuestion(base, base.questions[0].id, { text: ' ' })).toThrow();
    const deleted = deleteQuestions(edited, new Set([base.questions[0].id]));
    valid(deleted);
    expect(deleted.questions.map(question => question.text)).toEqual(['Q2']);
    expect(deleted.sessions[0].questionIds).toEqual([base.questions[1].id]);
    expect(deleteQuestions(deleted, new Set([base.questions[1].id]))).toEqual(emptyErrorBookFile());
  });

  it('deletes attempts within a date range, for chosen questions or all', () => {
    let file = addQuestions(emptyErrorBookFile(), 'capture', [photo('A'), photo('B')], '2026-10-01T10:00:00+08:00').file;
    const [a, b] = file.questions;
    file = recordPractice(file, [{ questionId: a.id, correct: true }, { questionId: b.id, correct: true }], '2026-10-05T10:00:00+08:00').file;
    const from = Date.parse('2026-10-04T00:00:00+08:00');
    const to = Date.parse('2026-10-06T00:00:00+08:00');
    const onlyA = deleteAttemptsOf(file, new Set([a.id]), from, to);
    valid(onlyA);
    expect(onlyA.questions.map(question => question.attempts.length)).toEqual([1, 2]);
    expect(onlyA.sessions[1].questionIds).toEqual([b.id]);
    const all = deleteAttemptsBetween(file, Date.parse('2026-09-01T00:00:00+08:00'), from);
    expect(all).toEqual({ ...emptyErrorBookFile(), sessions: [file.sessions[1]], questions: file.questions.map(question => ({ ...question, attempts: question.attempts.slice(1) })) });
  });

  it('sorts by error rate, then by the latest attempt', () => {
    let file = addQuestions(emptyErrorBookFile(), 'capture', [photo('A'), photo('B'), photo('C')], '2026-10-01T10:00:00+08:00').file;
    const [a, b] = file.questions;
    file = recordPractice(file, [{ questionId: a.id, correct: true }], '2026-10-02T10:00:00+08:00').file;
    file = recordPractice(file, [{ questionId: b.id, correct: false }], '2026-10-03T10:00:00+08:00').file;
    expect(sortForBook(questionRows(file)).map(row => row.question.text)).toEqual(['B', 'C', 'A']);
  });
});

describe('duplicates', () => {
  const book = addQuestions(emptyErrorBookFile(), 'capture', [
    photo('There __are__ some apples on the table. (对画线部分提问)'),
    photo('Which word has a different sound?\nA. cake B. map C. name'),
  ]).file.questions;

  it('marks equal questions, ignoring marks, spaces, punctuation and case', () => {
    expect(findDuplicate('there are some apples on the table（对画线部分提问）', book)).toMatchObject({ kind: 'same', question: book[0] });
  });

  it('marks very similar questions as possible duplicates', () => {
    expect(findDuplicate('Which word has a different sound? A. cake B. map C. game', book)).toMatchObject({ kind: 'similar', question: book[1] });
    expect(findDuplicate('Which word has the same sound? A. cat B. map', book)).toBeNull();
    expect(findDuplicate('', book)).toBeNull();
  });

  it('cleans question text', () => {
    expect(cleanQuestionText('\n  Line 1  \n  Line 2\n\n')).toBe('Line 1\n  Line 2');
  });

  it('cleans text with thousands of blank lines quickly', () => {
    const started = Date.now();
    expect(cleanQuestionText(`a${'\n'.repeat(8000)}b${'\n'.repeat(8000)}`)).toBe(`a${'\n'.repeat(8000)}b`);
    expect(cleanQuestionText(`a${' '.repeat(40000)}b`)).toBe(`a${' '.repeat(40000)}b`);
    expect(Date.now() - started).toBeLessThan(500);
  });
});
