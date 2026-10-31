import { describe, expect, it } from 'vitest';
import { calendarDaysBetween, isoToMs } from '../core/time';
import { emptyAppFile } from '../data/defaults';
import { paths } from '../data/schema';
import { checkAppFile, checkDictationFile, checkErrorBookFile } from '../data/validate';
import { JsonFiles } from '../storage/jsonFiles';
import { MemoryFolder } from '../storage/memoryFolder';
import { addDemoUser, DEMO_NAME, demoData } from './demo';

describe('example child', () => {
  it('has valid files whose newest record is from yesterday, whatever day it is', () => {
    for (const now of [Date.parse('2026-11-05T09:00:00+08:00'), Date.parse('2027-03-01T22:00:00+08:00')]) {
      const data = demoData(now);
      const times = [...data.chinese.sessions, ...data.english.sessions, ...data.errorBook.sessions].map(session => isoToMs(session.at));
      expect(calendarDaysBetween(Math.max(...times), now)).toBe(1);
      expect(times.every(time => time < now)).toBe(true);
      expect(data.chinese.words.length).toBeGreaterThan(10);
      expect(data.errorBook.questions.some(question => question.origin === 'generated')).toBe(true);
    }
  });

  it('writes the three data files and then app.json, once each, and makes the example child current', async () => {
    const folder = new MemoryFolder();
    const files = new JsonFiles(folder);
    const { app, user } = await addDemoUser(files, emptyAppFile(), Date.parse('2026-11-05T09:00:00+08:00'));
    expect(app.currentUserId).toBe(user.id);
    expect(user.name).toBe(DEMO_NAME);
    expect(checkAppFile(JSON.parse(folder.files.get(paths.app)!))).toMatchObject({ ok: true });
    expect(checkDictationFile(JSON.parse(folder.files.get(paths.dictation(user.id, 'chinese'))!))).toMatchObject({ ok: true });
    expect(checkDictationFile(JSON.parse(folder.files.get(paths.dictation(user.id, 'english'))!))).toMatchObject({ ok: true });
    expect(checkErrorBookFile(JSON.parse(folder.files.get(paths.errorBook(user.id))!))).toMatchObject({ ok: true });
    expect([...folder.files.keys()].filter(name => name.includes('history'))).toEqual([]);
    await expect(addDemoUser(files, app)).rejects.toThrow(DEMO_NAME);
  });
});
