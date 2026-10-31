import { isIsoTime } from '../core/time';
import { defaultSettings } from './defaults';
import { wordKey } from './normalize';
import {
  DICTATION_SOURCES, ERROR_BOOK_SOURCES, PROMPT_KEYS, QUESTION_ORIGINS, SCHEMAS,
  type AppFile, type Attempt, type DictationFile, type ErrorBookFile, type Settings,
} from './schema';

/** Result of checking a data file read from disk. Invalid files are never overwritten silently. */
export type Checked<T> =
  | { ok: true; value: T }
  | { ok: false; reason: 'invalid' | 'newer'; error: string };

class Invalid extends Error {}

const fail = (where: string, message: string): never => { throw new Invalid(`${where}：${message}`); };
const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function object(value: unknown, where: string): Record<string, unknown> {
  return isObject(value) ? value : fail(where, '应为对象');
}
function array(value: unknown, where: string): unknown[] {
  return Array.isArray(value) ? value : fail(where, '应为数组');
}
function text(value: unknown, where: string, { allowEmpty = false } = {}): string {
  if (typeof value !== 'string') return fail(where, '应为字符串');
  if (!allowEmpty && !value.trim()) fail(where, '不能为空');
  return value;
}
function time(value: unknown, where: string): string {
  return isIsoTime(value) ? value : fail(where, '应为带时区的 ISO 8601 时间');
}
function oneOf<T extends string>(value: unknown, options: readonly T[], where: string): T {
  return options.includes(value as T) ? value as T : fail(where, `应为 ${options.join(' / ')} 之一`);
}
function unique(ids: string[], where: string) {
  const seen = new Set<string>();
  for (const id of ids) {
    if (seen.has(id)) fail(where, `ID 重复：${id}`);
    seen.add(id);
  }
}

/**
 * Accept the expected schema. Any other schema string (a newer version, or a name this app does not know)
 * is reported as 'newer': the file is neither loaded nor overwritten, and no older version is used instead.
 * Only a missing or non-string schema counts as a damaged file.
 */
function schema(value: unknown, expected: string) {
  if (value === expected) return;
  if (typeof value !== 'string' || !value.trim()) fail('schema', `应为 ${expected}，实际为 ${String(value)}`);
  const [name, version] = (value as string).split('/');
  const [expectedName, expectedVersion] = expected.split('/');
  if (name === expectedName && Number(version) < Number(expectedVersion)) fail('schema', `应为 ${expected}，实际为 ${String(value)}`);
  throw Object.assign(new Invalid(name === expectedName
    ? `数据格式 ${String(value)} 比当前 App 支持的 ${expected} 新，请升级 App`
    : `无法识别的数据格式 ${String(value)}（应为 ${expected}），文件没有被修改`), { newer: true });
}

function run<T>(input: unknown, check: (input: unknown) => T): Checked<T> {
  try {
    return { ok: true, value: check(input) };
  } catch (error) {
    if (error instanceof Invalid) {
      return { ok: false, reason: (error as Invalid & { newer?: boolean }).newer ? 'newer' : 'invalid', error: error.message };
    }
    throw error;
  }
}

function attempts(value: unknown, sessionIds: Set<string>, where: string): Attempt[] {
  return array(value, where).map((item, index) => {
    const at = `${where}[${index}]`;
    const record = object(item, at);
    if (typeof record.correct !== 'boolean') fail(`${at}.correct`, '应为 true / false');
    const sessionId = text(record.sessionId, `${at}.sessionId`);
    if (!sessionIds.has(sessionId)) fail(`${at}.sessionId`, `找不到对应的学习记录：${sessionId}`);
    return { sessionId, correct: record.correct as boolean };
  });
}

export const checkAppFile = (input: unknown): Checked<AppFile> => run(input, value => {
  const root = object(value, '根节点');
  schema(root.schema, SCHEMAS.app);
  const users = array(root.users, 'users').map((item, index) => {
    const user = object(item, `users[${index}]`);
    // The id names the user's data folder (data/users/<id>/), so it must be a safe single path segment.
    const id = text(user.id, `users[${index}].id`);
    if (!/^[A-Za-z0-9_-]+$/.test(id)) fail(`users[${index}].id`, `只能包含字母、数字、- 和 _：${id}`);
    return { id, name: text(user.name, `users[${index}].name`).trim(), createdAt: time(user.createdAt, `users[${index}].createdAt`) };
  });
  // Ids are directory names, and Android shared storage and Windows ignore case: "User-A" and "user-a" are one folder.
  unique(users.map(user => user.id.toLowerCase()), 'users');
  unique(users.map(user => user.name), 'users 的名字');
  const currentUserId = root.currentUserId === null ? null : text(root.currentUserId, 'currentUserId');
  if (currentUserId !== null && !users.some(user => user.id === currentUserId)) fail('currentUserId', '不在用户列表中');
  return { schema: SCHEMAS.app, users, currentUserId };
});

export const checkDictationFile = (input: unknown): Checked<DictationFile> => run(input, value => {
  const root = object(value, '根节点');
  schema(root.schema, SCHEMAS.dictation);
  const language = oneOf(root.language, ['chinese', 'english'] as const, 'language');
  const rawSessions = array(root.sessions, 'sessions').map((item, index) => object(item, `sessions[${index}]`));
  const sessionIds = new Set(rawSessions.map((session, index) => text(session.id, `sessions[${index}].id`)));
  unique(rawSessions.map(session => session.id as string), 'sessions');
  const words = array(root.words, 'words').map((item, index) => {
    const where = `words[${index}]`;
    const word = object(item, where);
    const translation = word.translation === undefined ? undefined : text(word.translation, `${where}.translation`, { allowEmpty: true });
    if (translation !== undefined && language !== 'english') fail(`${where}.translation`, '只有英语词语可以有译文');
    return {
      id: text(word.id, `${where}.id`),
      text: text(word.text, `${where}.text`),
      ...(translation ? { translation } : {}),
      createdAt: time(word.createdAt, `${where}.createdAt`),
      attempts: attempts(word.attempts, sessionIds, `${where}.attempts`),
    };
  });
  unique(words.map(word => word.id), 'words');
  unique(words.map(word => wordKey(word.text, language)), 'words 的词语');
  const wordIds = new Set(words.map(word => word.id));
  const sessions = rawSessions.map((session, index) => {
    const where = `sessions[${index}]`;
    const ids = array(session.wordIds, `${where}.wordIds`).map((id, i) => text(id, `${where}.wordIds[${i}]`));
    for (const id of ids) if (!wordIds.has(id)) fail(`${where}.wordIds`, `找不到词语：${id}`);
    const readMode = session.readMode === undefined ? undefined : oneOf(session.readMode, ['translation', 'original'] as const, `${where}.readMode`);
    if (readMode && language !== 'english') fail(`${where}.readMode`, '只有英语听写有朗读方式');
    return {
      id: session.id as string,
      kind: oneOf(session.kind, DICTATION_SOURCES, `${where}.kind`),
      at: time(session.at, `${where}.at`),
      wordIds: ids,
      ...(readMode ? { readMode } : {}),
    };
  });
  return { schema: SCHEMAS.dictation, language, sessions, words };
});

export const checkErrorBookFile = (input: unknown): Checked<ErrorBookFile> => run(input, value => {
  const root = object(value, '根节点');
  schema(root.schema, SCHEMAS.errorBook);
  const rawSessions = array(root.sessions, 'sessions').map((item, index) => object(item, `sessions[${index}]`));
  const sessionIds = new Set(rawSessions.map((session, index) => text(session.id, `sessions[${index}].id`)));
  unique(rawSessions.map(session => session.id as string), 'sessions');
  const questions = array(root.questions, 'questions').map((item, index) => {
    const where = `questions[${index}]`;
    const question = object(item, where);
    const sourceQuestionId = question.sourceQuestionId === undefined ? undefined : text(question.sourceQuestionId, `${where}.sourceQuestionId`);
    return {
      id: text(question.id, `${where}.id`),
      text: text(question.text, `${where}.text`),
      answer: text(question.answer, `${where}.answer`, { allowEmpty: true }),
      origin: oneOf(question.origin, QUESTION_ORIGINS, `${where}.origin`),
      ...(sourceQuestionId ? { sourceQuestionId } : {}),
      createdAt: time(question.createdAt, `${where}.createdAt`),
      attempts: attempts(question.attempts, sessionIds, `${where}.attempts`),
    };
  });
  unique(questions.map(question => question.id), 'questions');
  const questionIds = new Set(questions.map(question => question.id));
  const sessions = rawSessions.map((session, index) => {
    const where = `sessions[${index}]`;
    const ids = array(session.questionIds, `${where}.questionIds`).map((id, i) => text(id, `${where}.questionIds[${i}]`));
    for (const id of ids) if (!questionIds.has(id)) fail(`${where}.questionIds`, `找不到题目：${id}`);
    return { id: session.id as string, kind: oneOf(session.kind, ERROR_BOOK_SOURCES, `${where}.kind`), at: time(session.at, `${where}.at`), questionIds: ids };
  });
  return { schema: SCHEMAS.errorBook, sessions, questions };
});

/**
 * Settings are merged field by field with the defaults, so a hand-edited file with one bad value
 * keeps every other value. Only a wrong or newer schema is rejected.
 */
export const checkSettings = (input: unknown): Checked<Settings> => run(input, value => {
  const root = object(value, '根节点');
  schema(root.schema, SCHEMAS.settings);
  const base = defaultSettings();
  const number = (source: unknown, fallback: number, min: number, max: number) =>
    typeof source === 'number' && Number.isFinite(source) && source >= min && source <= max ? source : fallback;
  const name = (source: unknown, fallback: string) => typeof source === 'string' && source.trim() ? source.trim() : fallback;
  const tts = isObject(root.tts) ? root.tts : {};
  const gap = isObject(tts.repeatGapMs) ? tts.repeatGapMs : {};
  const limits = isObject(root.timeLimitSeconds) ? root.timeLimitSeconds : {};
  const models = isObject(root.models) ? root.models : {};
  const prompts = isObject(root.prompts) ? root.prompts : {};
  return {
    schema: SCHEMAS.settings,
    tts: {
      rate: number(tts.rate, base.tts.rate, 0.1, 2),
      pitch: number(tts.pitch, base.tts.pitch, 0.5, 2),
      repeatGapMs: {
        chinese: number(gap.chinese, base.tts.repeatGapMs.chinese, 0, 10_000),
        english: number(gap.english, base.tts.repeatGapMs.english, 0, 10_000),
      },
    },
    timeLimitSeconds: {
      dictation: number(limits.dictation, base.timeLimitSeconds.dictation, 0, 3600),
      errorBook: number(limits.errorBook, base.timeLimitSeconds.errorBook, 0, 3600),
    },
    models: {
      vision: name(models.vision, base.models.vision),
      translation: name(models.translation, base.models.translation),
      generation: name(models.generation, base.models.generation),
      generationTemperature: number(models.generationTemperature, base.models.generationTemperature, 0, 2),
      requestTimeoutSeconds: number(models.requestTimeoutSeconds, base.models.requestTimeoutSeconds, 5, 300),
      maxAttempts: number(models.maxAttempts, base.models.maxAttempts, 1, 5),
    },
    prompts: Object.fromEntries(PROMPT_KEYS
      .filter(key => typeof prompts[key] === 'string' && (prompts[key] as string).trim())
      .map(key => [key, prompts[key] as string])),
  };
});
