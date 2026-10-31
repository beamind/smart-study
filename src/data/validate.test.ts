import { describe, expect, it } from 'vitest';
import { defaultSettings } from './defaults';
import { checkAppFile, checkDictationFile, checkErrorBookFile, checkSettings } from './validate';

const at = '2026-02-01T19:30:12+08:00';

describe('checkDictationFile', () => {
  const file = () => ({
    schema: 'smart-study.dictation/1',
    language: 'english',
    sessions: [{ id: 's1', kind: 'dictation', at, wordIds: ['w1'], readMode: 'translation' }],
    words: [{ id: 'w1', text: 'take care', translation: '保重', createdAt: at, attempts: [{ sessionId: 's1', correct: false }] }],
  });

  it('accepts a valid file', () => {
    expect(checkDictationFile(file())).toMatchObject({ ok: true });
  });

  it('rejects English words that differ only in case', () => {
    const data = file();
    data.words.push({ ...data.words[0], id: 'w2', text: 'Take  Care' });
    expect(checkDictationFile(data)).toMatchObject({ ok: false, reason: 'invalid' });
  });

  it('rejects attempts that point to a missing session', () => {
    const data = file();
    data.words[0].attempts[0].sessionId = 'nope';
    expect(checkDictationFile(data)).toMatchObject({ ok: false, error: expect.stringContaining('nope') });
  });

  it('rejects times without a time zone', () => {
    const data = file();
    data.words[0].createdAt = '2026-02-01 19:30:12';
    expect(checkDictationFile(data)).toMatchObject({ ok: false });
  });

  it('flags a newer schema separately', () => {
    expect(checkDictationFile({ ...file(), schema: 'smart-study.dictation/3' })).toMatchObject({ ok: false, reason: 'newer' });
  });

  it('treats an unknown schema like a newer one (never overwritten), and a missing one as damaged', () => {
    expect(checkDictationFile({ ...file(), schema: 'other-app.words/1' })).toMatchObject({ ok: false, reason: 'newer', error: expect.stringContaining('无法识别') });
    expect(checkDictationFile({ ...file(), schema: 'smart-study.dictation/2-beta' })).toMatchObject({ ok: false, reason: 'newer' });
    expect(checkDictationFile({ ...file(), schema: 'smart-study.dictation/0' })).toMatchObject({ ok: false, reason: 'invalid' });
    expect(checkDictationFile({ ...file(), schema: undefined })).toMatchObject({ ok: false, reason: 'invalid' });
  });

  it('allows a read mode only in English files', () => {
    expect(checkDictationFile({ ...file(), language: 'chinese', words: [{ ...file().words[0], translation: undefined }] })).toMatchObject({ ok: false, error: expect.stringContaining('readMode') });
  });
});

describe('checkAppFile', () => {
  const app = (id: string) => ({ schema: 'smart-study.app/1', users: [{ id, name: '小明', createdAt: at }], currentUserId: id });
  it('accepts UUID ids and rejects ids that are not a safe folder name', () => {
    expect(checkAppFile(app('3f2c0a9e-22b9-4237-91af-fc8f971650dd'))).toMatchObject({ ok: true });
    for (const id of ['..', 'a/b', '..\\x', 'c:d', 'a b']) expect(checkAppFile(app(id))).toMatchObject({ ok: false, reason: 'invalid' });
  });

  it('rejects two user ids that differ only in case (one folder on Android and Windows)', () => {
    const users = [{ id: 'user-a', name: '一', createdAt: at }, { id: 'User-A', name: '二', createdAt: at }];
    expect(checkAppFile({ schema: 'smart-study.app/1', users, currentUserId: 'user-a' })).toMatchObject({ ok: false, reason: 'invalid' });
  });
});

describe('checkErrorBookFile', () => {
  it('accepts an empty answer and rejects an unknown origin', () => {
    const data = {
      schema: 'smart-study.error-book/1',
      sessions: [{ id: 's1', kind: 'capture', at, questionIds: ['q1'] }],
      questions: [{ id: 'q1', text: 'c__a__ke', answer: '', origin: 'photo', createdAt: at, attempts: [{ sessionId: 's1', correct: false }] }],
    };
    expect(checkErrorBookFile(data)).toMatchObject({ ok: true });
    expect(checkErrorBookFile({ ...data, questions: [{ ...data.questions[0], origin: 'scan' }] })).toMatchObject({ ok: false });
  });
});

describe('checkSettings', () => {
  it('keeps valid values and replaces invalid ones with defaults', () => {
    const result = checkSettings({ schema: 'smart-study.settings/1', tts: { rate: 0.8, pitch: 'high' }, prompts: { translation: '只翻译', unknown: 'x' } });
    expect(result).toMatchObject({ ok: true, value: { tts: { rate: 0.8, pitch: defaultSettings().tts.pitch }, prompts: { translation: '只翻译' } } });
    expect(result.ok && 'unknown' in result.value.prompts).toBe(false);
  });
});
