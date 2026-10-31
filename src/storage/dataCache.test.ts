import { describe, expect, it } from 'vitest';
import { emptyDictationFile } from '../data/defaults';
import type { DictationFile } from '../data/schema';
import { checkDictationFile } from '../data/validate';
import { submit, undoSession } from '../domain/dictation';
import { DataCache } from './dataCache';
import { JsonFiles } from './jsonFiles';
import { MemoryFolder } from './memoryFolder';

const path = 'data/users/u1/dictation-chinese.json';
const empty = () => emptyDictationFile('chinese');
const add = (text: string) => (file: DictationFile) => submit(file, { kind: 'dictation', words: [{ text, correct: true }] }).file;

describe('DataCache', () => {
  it('serialises concurrent updates so none is lost', async () => {
    const folder = new MemoryFolder();
    const cache = new DataCache(new JsonFiles(folder));
    await Promise.all(['一', '二', '三'].map(text => cache.set(path, checkDictationFile, empty, add(text))));
    const fresh = new DataCache(new JsonFiles(folder));
    const entry = await fresh.get(path, checkDictationFile, empty);
    expect(entry.status === 'ok' && entry.value.words.map(word => word.text)).toEqual(['一', '二', '三']);
  });

  it('keeps the previous value in memory when saving fails', async () => {
    const folder = new MemoryFolder();
    const cache = new DataCache(new JsonFiles(folder));
    await cache.set(path, checkDictationFile, empty, add('一'));
    const write = folder.writeText.bind(folder);
    folder.writeText = async () => { throw new Error('disk full'); };
    await expect(cache.set(path, checkDictationFile, empty, add('二'))).rejects.toThrow('disk full');
    folder.writeText = write;
    const entry = cache.peek<DictationFile>(path);
    expect(entry?.status === 'ok' && entry.value.words.map(word => word.text)).toEqual(['一']);
  });

  it('restores the same bytes when a submission is undone after reloading', async () => {
    // Found on the Pad: new English words got "translation" as the last key, loading moved it after "text",
    // so the file written by undo differed from the one before the dictation although the content was equal.
    const english = 'data/users/u1/dictation-english.json';
    const emptyEnglish = () => emptyDictationFile('english');
    const folder = new MemoryFolder();
    await new DataCache(new JsonFiles(folder)).set(english, checkDictationFile, emptyEnglish,
      file => submit(file, { kind: 'dictation', words: [{ text: 'intel core', translation: '英特尔酷睿', correct: true }] }).file);
    const before = folder.files.get(english);

    const cache = new DataCache(new JsonFiles(folder));
    const sessionId = await cache.update(english, checkDictationFile, emptyEnglish, file => {
      const done = submit(file, { kind: 'dictation', words: [{ text: 'kangaroo', translation: '袋鼠', correct: true }] });
      return { value: done.file, result: done.sessionId };
    });
    await cache.set(english, checkDictationFile, emptyEnglish, file => undoSession(file, sessionId));
    expect(folder.files.get(english)).toBe(before);
  });

  it('does not write a change that would not pass the check', async () => {
    const folder = new MemoryFolder();
    const cache = new DataCache(new JsonFiles(folder));
    await cache.set(path, checkDictationFile, empty, add('一'));
    const before = folder.files.get(path);
    await expect(cache.set(path, checkDictationFile, empty, file => ({ ...file, sessions: [] }))).rejects.toThrow('未保存');
    expect(folder.files.get(path)).toBe(before);
  });

  it('refuses to write a file that could not be read', async () => {
    const folder = new MemoryFolder('test', { [path]: '{"schema":"smart-study.dictation/9"}' });
    const cache = new DataCache(new JsonFiles(folder));
    expect(await cache.get(path, checkDictationFile, empty)).toMatchObject({ status: 'unusable' });
    await expect(cache.set(path, checkDictationFile, empty, add('一'))).rejects.toThrow();
    expect(folder.files.get(path)).toBe('{"schema":"smart-study.dictation/9"}');
  });
});

describe('DataCache read failures', () => {
  it('refuses to write over an invalid file', async () => {
    const folder = new MemoryFolder('test', { [path]: '{"schema":"smart-study.dictation/1","language":"chinese"}' });
    const cache = new DataCache(new JsonFiles(folder));
    expect(await cache.get(path, checkDictationFile, empty)).toMatchObject({ status: 'unusable' });
    await expect(cache.set(path, checkDictationFile, empty, add('一'))).rejects.toThrow();
    expect(folder.files.get(path)).toBe('{"schema":"smart-study.dictation/1","language":"chinese"}');
  });

  it('reports a read that throws and reads again next time', async () => {
    const folder = new MemoryFolder();
    const cache = new DataCache(new JsonFiles(folder));
    const read = folder.readText.bind(folder);
    folder.readText = async () => { throw new Error('permission lost'); };
    expect(await cache.get(path, checkDictationFile, empty)).toMatchObject({ status: 'unusable', error: expect.stringContaining('permission lost') });
    folder.readText = read;
    expect(await cache.get(path, checkDictationFile, empty)).toMatchObject({ status: 'ok' });
  });
});
