import { describe, expect, it } from 'vitest';
import { emptyDictationFile } from '../data/defaults';
import { checkDictationFile } from '../data/validate';
import { addWords, deleteAttemptsBetween, deleteAttemptsOf, deleteWords, editWord, submit, undoSession } from './dictation';
import { sample } from './sampling';

const valid = (file: unknown) => expect(checkDictationFile(file)).toMatchObject({ ok: true });

describe('dictation file changes', () => {
  it('submit creates words, reuses known words case-insensitively and keeps reading order', () => {
    const first = submit(emptyDictationFile('english'), { kind: 'dictation', readMode: 'translation', at: '2026-10-01T19:00:00+08:00', words: [
      { text: 'apple', translation: '苹果', correct: false }, { text: 'take care', translation: '保重', correct: true },
    ] });
    const second = submit(first.file, { kind: 'review', at: '2026-10-02T19:00:00+08:00', words: [
      { text: 'Take  Care', correct: false }, { text: 'apple', correct: true }, { text: 'APPLE', correct: false },
    ] });
    const file = second.file;
    valid(file);
    expect(file.words.map(word => [word.text, word.translation, word.attempts.map(attempt => attempt.correct)])).toEqual([
      ['apple', '苹果', [false, false]], ['take care', '保重', [true, false]],
    ]);
    expect(file.sessions.at(-1)!.wordIds).toEqual([file.words[1].id, file.words[0].id]);
    expect(first.file.words[0].attempts).toHaveLength(1); // input untouched
  });

  it('undo removes the session and words that only it created', () => {
    const one = submit(emptyDictationFile('chinese'), { kind: 'dictation', words: [{ text: '春天', correct: true }] });
    const two = submit(one.file, { kind: 'dictation', words: [{ text: '春天', correct: false }, { text: '夏天', correct: false }] });
    const undone = undoSession(two.file, two.sessionId);
    valid(undone);
    expect(undone).toEqual(one.file);
    expect(() => undoSession(undone, two.sessionId)).toThrow();
  });

  it('addWords skips known words and records one wrong attempt for new ones', () => {
    const base = submit(emptyDictationFile('chinese'), { kind: 'dictation', words: [{ text: '春天', correct: true }] }).file;
    const result = addWords(base, [{ text: '春天' }, { text: '温暖' }, { text: '温暖' }]);
    expect(result).toMatchObject({ added: 1, skipped: ['春天', '温暖'] });
    expect(result.file.sessions.at(-1)).toMatchObject({ kind: 'added' });
    expect(result.file.words.find(word => word.text === '温暖')!.attempts).toEqual([{ sessionId: result.file.sessions.at(-1)!.id, correct: false }]);
    valid(result.file);
  });

  it('editWord renames, edits translations and merges into an existing word in time order', () => {
    let file = submit(emptyDictationFile('english'), { kind: 'dictation', at: '2026-10-01T10:00:00+08:00', words: [{ text: 'colour', correct: false }] }).file;
    file = submit(file, { kind: 'dictation', at: '2026-10-02T10:00:00+08:00', words: [{ text: 'color', translation: '颜色', correct: true }] }).file;
    file = submit(file, { kind: 'dictation', at: '2026-10-03T10:00:00+08:00', words: [{ text: 'colour', correct: false }] }).file;
    const colour = file.words.find(word => word.text === 'colour')!;
    const merged = editWord(file, colour.id, { text: 'Color' });
    valid(merged);
    expect(merged.words).toHaveLength(1);
    expect(merged.words[0]).toMatchObject({ text: 'Color', translation: '颜色', createdAt: '2026-10-01T10:00:00+08:00' });
    expect(merged.words[0].attempts.map(attempt => attempt.correct)).toEqual([false, true, false]);
    const renamed = editWord(file, colour.id, { translation: '颜色（英式）' });
    expect(renamed.words.find(word => word.id === colour.id)!.translation).toBe('颜色（英式）');
  });

  it('merging two words from the same dictation keeps one attempt for it', () => {
    const file = submit(emptyDictationFile('english'), { kind: 'dictation', words: [{ text: 'colour', correct: false }, { text: 'color', correct: true }] }).file;
    const merged = editWord(file, file.words[0].id, { text: 'color' });
    valid(merged);
    expect(merged.words).toHaveLength(1);
    expect(merged.words[0].attempts).toEqual([{ sessionId: file.sessions[0].id, correct: false }]);
  });

  it('addWords treats extra inner spaces as the same word', () => {
    const base = submit(emptyDictationFile('chinese'), { kind: 'dictation', words: [{ text: '春 天', correct: true }] }).file;
    expect(addWords(base, [{ text: '春  天' }])).toMatchObject({ added: 0, skipped: ['春 天'] });
  });

  it('deleteWords and deleteAttemptsBetween prune empty words and sessions', () => {
    const a = submit(emptyDictationFile('chinese'), { kind: 'dictation', at: '2026-10-01T10:00:00+08:00', words: [{ text: '春', correct: true }, { text: '夏', correct: true }] }).file;
    const b = submit(a, { kind: 'dictation', at: '2026-10-05T10:00:00+08:00', words: [{ text: '春', correct: false }] }).file;
    const withoutSpring = deleteWords(b, new Set([b.words[0].id]));
    expect(withoutSpring.words.map(word => word.text)).toEqual(['夏']);
    expect(withoutSpring.sessions).toHaveLength(1);
    const oct1Removed = deleteAttemptsBetween(b, Date.parse('2026-10-01T00:00:00+08:00'), Date.parse('2026-10-02T00:00:00+08:00'));
    expect(oct1Removed.words.map(word => [word.text, word.attempts.length])).toEqual([['春', 1]]);
    valid(oct1Removed);
  });

  it('deleteAttemptsOf only touches the chosen words in the chosen range', () => {
    let file = submit(emptyDictationFile('chinese'), { kind: 'dictation', at: '2026-10-01T10:00:00+08:00', words: [{ text: '春', correct: false }, { text: '夏', correct: true }] }).file;
    file = submit(file, { kind: 'review', at: '2026-10-05T10:00:00+08:00', words: [{ text: '春', correct: true }, { text: '夏', correct: false }] }).file;
    const spring = file.words.find(word => word.text === '春')!.id;
    const result = deleteAttemptsOf(file, new Set([spring]), Date.parse('2026-10-04T00:00:00+08:00'), Date.parse('2026-10-06T00:00:00+08:00'));
    valid(result);
    expect(result.words.map(word => [word.text, word.attempts.map(attempt => attempt.correct)])).toEqual([['春', [false]], ['夏', [true, false]]]);
    // The 10-05 session no longer lists 春, which has no attempt in it any more.
    expect(result.sessions[1].wordIds).toEqual([file.words.find(word => word.text === '夏')!.id]);
  });
});

describe('sample', () => {
  const now = Date.parse('2026-10-31T12:00:00+08:00');
  it('always picks certain items first and fills up to the count', () => {
    const candidates = [
      { item: 'never', total: 3, wrong: 0, lastMs: now },
      { item: 'always', total: 1, wrong: 1, lastMs: now },
      { item: 'old', total: 3, wrong: 0, lastMs: now - 200 * 86_400_000 },
    ];
    let seed = 7;
    const random = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    for (let i = 0; i < 200; i++) expect(new Set(sample(candidates, 2, now, random))).toEqual(new Set(['always', 'old']));
    expect(sample(candidates, 10, now)).toHaveLength(3);
  });

  it('does not favour items near the start of the list', () => {
    const candidates = Array.from({ length: 40 }, (_, i) => ({ item: i, total: 2, wrong: 1, lastMs: now }));
    const counts = new Array<number>(40).fill(0);
    let seed = 3;
    const random = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    for (let run = 0; run < 4000; run++) for (const item of sample(candidates, 5, now, random)) counts[item]++;
    const first = counts.slice(0, 10).reduce((a, b) => a + b, 0);
    const last = counts.slice(30).reduce((a, b) => a + b, 0);
    expect(last / first).toBeGreaterThan(0.8);
  });

  it('gives items not practised for a long time a higher chance', () => {
    const fresh = { item: 'fresh', total: 2, wrong: 0, lastMs: now };
    const stale = { item: 'stale', total: 2, wrong: 0, lastMs: now - 60 * 86_400_000 };
    const counts = { fresh: 0, stale: 0 };
    let seed = 1;
    const random = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    for (let i = 0; i < 2000; i++) counts[sample([fresh, stale], 1, now, random)[0] as 'fresh' | 'stale']++;
    expect(counts.stale).toBeGreaterThan(counts.fresh * 3);
  });
});

describe('session ids chosen in advance', () => {
  it('saves a dictation under the id it was given, and refuses that id once it is in the file', () => {
    const first = submit(emptyDictationFile('chinese'), { kind: 'dictation', words: [{ text: '春天', correct: true }], sessionId: 's_draft1' });
    expect(first.sessionId).toBe('s_draft1');
    expect(first.file.sessions.map(session => session.id)).toEqual(['s_draft1']);
    expect(() => submit(first.file, { kind: 'dictation', words: [{ text: '春天', correct: false }], sessionId: 's_draft1' })).toThrow('已经保存过了');
    // After an undo, the same id may be used again (the draft still holds it).
    const undone = undoSession(first.file, 's_draft1');
    expect(submit(undone, { kind: 'dictation', words: [{ text: '春天', correct: false }], sessionId: 's_draft1' }).sessionId).toBe('s_draft1');
  });
});
