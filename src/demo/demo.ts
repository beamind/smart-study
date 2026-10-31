import { calendarDaysBetween, isoToMs, toIso, type IsoTime } from '../core/time';
import { paths, type AppFile, type DictationFile, type ErrorBookFile, type User } from '../data/schema';
import { checkAppFile, checkDictationFile, checkErrorBookFile, type Checked } from '../data/validate';
import type { JsonFiles } from '../storage/jsonFiles';
import chineseTemplate from './xiaohua-chinese.json';
import englishTemplate from './xiaohua-english.json';
import errorBookTemplate from './xiaohua-error-book.json';

/**
 * The example child shipped with the app, so a new user sees what the pages look like with some records:
 * a few dictations, word-book entries, error-book questions and a 举一反三 question. The words and questions
 * are ordinary school material; the right/wrong answers are made up.
 */
export const DEMO_NAME = '小华';

export interface DemoData {
  chinese: DictationFile;
  english: DictationFile;
  errorBook: ErrorBookFile;
}

const DAY = 86_400_000;
const offsetOf = (time: IsoTime) => {
  const match = /([+-])(\d{2}):(\d{2})$/.exec(time);
  return match ? (match[1] === '-' ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3])) : 0;
};

function valid<T>(checked: Checked<T>, what: string): T {
  if (!checked.ok) throw new Error(`示例数据（${what}）没有通过校验：${checked.error}`);
  return checked.value;
}

/**
 * The example files with every time moved by whole days, so the newest record is from yesterday evening
 * and the learning record's "最近 7 天" shows them. Times keep their time of day and UTC offset.
 */
export function demoData(nowMs: number): DemoData {
  const times: IsoTime[] = [
    ...chineseTemplate.sessions.map(session => session.at),
    ...englishTemplate.sessions.map(session => session.at),
    ...errorBookTemplate.sessions.map(session => session.at),
  ];
  const newest = Math.max(...times.map(isoToMs));
  const shiftMs = (calendarDaysBetween(newest, nowMs) - 1) * DAY;
  const shift = (time: IsoTime) => toIso(isoToMs(time) + shiftMs, offsetOf(time));
  const dictation = (file: typeof chineseTemplate | typeof englishTemplate) => ({
    ...file,
    sessions: file.sessions.map(session => ({ ...session, at: shift(session.at) })),
    words: file.words.map(word => ({ ...word, createdAt: shift(word.createdAt) })),
  });
  return {
    chinese: valid(checkDictationFile(dictation(chineseTemplate)), '语文听写'),
    english: valid(checkDictationFile(dictation(englishTemplate)), '英语听写'),
    errorBook: valid(checkErrorBookFile({
      ...errorBookTemplate,
      sessions: errorBookTemplate.sessions.map(session => ({ ...session, at: shift(session.at) })),
      questions: errorBookTemplate.questions.map(question => ({ ...question, createdAt: shift(question.createdAt) })),
    }), '错题本'),
  };
}

/**
 * Add the example child to `app` and make it the current user. The child's three data files are written
 * first and app.json last, so an interrupted add never lists a child without data. Each file is written once.
 * The id is new every time: deleting a child keeps its folder, and a re-added example must not reuse it.
 */
export async function addDemoUser(files: JsonFiles, app: AppFile, nowMs = Date.now()): Promise<{ app: AppFile; user: User }> {
  if (app.users.some(user => user.name === DEMO_NAME)) throw new Error(`已经有"${DEMO_NAME}"了`);
  const data = demoData(nowMs);
  const user: User = { id: crypto.randomUUID(), name: DEMO_NAME, createdAt: toIso(nowMs) };
  const next = valid(checkAppFile({ ...app, users: [...app.users, user], currentUserId: user.id }), '用户列表');
  await files.save(paths.dictation(user.id, 'chinese'), data.chinese);
  await files.save(paths.dictation(user.id, 'english'), data.english);
  await files.save(paths.errorBook(user.id), data.errorBook);
  await files.save(paths.app, next);
  return { app: next, user };
}
