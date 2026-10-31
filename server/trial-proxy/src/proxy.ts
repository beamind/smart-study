import { createHash } from 'node:crypto';
import { cleanTranslation, parseGenerated, parseQuestions, parseWords } from '../../../src/ai/parse';
import type { Usage } from '../../../src/ai/dashscope';
import { MAX_REPLY_TOKENS, modelRequest, parseUnderlineHits, type ModelTask } from '../../../src/ai/tasks';
import { defaultSettings } from '../../../src/data/defaults';
import type { DictationLanguage } from '../../../src/data/schema';

/**
 * The trial proxy (docs/decisions/004-trial-proxy.md): checks a request, reserves its device and IP allowance and the
 * most it can cost from the day's budget, calls the model with the server's key, then settles the cost to what was
 * really used. Reserving before the call means requests running at the same time cannot all pass a limit that only
 * one of them fits under. No platform code here, so it is tested with in-memory counters and a fake model.
 */

/** Counters that survive between requests (Tablestore in production). Amounts are whole numbers. */
export interface Counters {
  get(keys: string[]): Promise<number[]>;
  /** Adds `amount` (may be negative). */
  add(key: string, amount: number): Promise<void>;
  /** Adds `amount` only if the result stays at or below `limit`; returns whether it did. */
  addWithin(key: string, amount: number, limit: number): Promise<boolean>;
}

export interface Limits {
  devicePhoto: number;
  deviceTranslate: number;
  deviceGenerate: number;
  ipBasic: number;
  ipGenerate: number;
  /** Yuan per day for photos and translations, and for 举一反三. */
  budgetBasic: number;
  budgetGenerate: number;
  /** Yuan per million tokens, input and output, for the flash model (photos, translation) and the max model (举一反三). */
  priceFlash: [number, number];
  priceMax: [number, number];
}

export const DEFAULT_LIMITS: Limits = {
  devicePhoto: 20, deviceTranslate: 100, deviceGenerate: 10,
  ipBasic: 60, ipGenerate: 10,
  budgetBasic: 0.7, budgetGenerate: 0.3,
  priceFlash: [0.8, 2.7], priceMax: [12, 36],
};

/** The model call failed; `usage` says what it cost when DashScope told (an empty reply), null when it did not. */
export interface ModelFailure { kind?: string; message?: string; usage?: Usage | null }

export interface Dependencies {
  counters: Counters;
  /** Calls the model with the server's key; returns the reply and the tokens used (null: not reported). */
  model(task: ModelTask): Promise<{ reply: string; usage: Usage | null }>;
  limits: Limits;
  now(): number;
  /** Mixed into IP hashes, so the stored keys do not reveal addresses. */
  ipSalt: string;
  /** For counter failures after the model call, which must not fail the request. */
  log?(entry: Record<string, unknown>): void;
}

export interface Remaining { photo: number; translate: number; generate: number }

export type Result =
  | { status: 200; body: { reply: string; remaining?: Remaining } | { remaining: Remaining } }
  | { status: 400 | 429 | 502; body: { error: string; message: string } };

type Kind = 'photo' | 'translate' | 'generate';
const kindOf = (task: ModelTask['task']): Kind => (task === 'translate' ? 'translate' : task === 'generate' ? 'generate' : 'photo');

/** Photo recognition limits: a 1280px JPEG is about 0.3 MB; 2 MB of base64 leaves room and stops abuse. */
const MAX_IMAGE_CHARS = 2_000_000;
const MAX_TEXT = 2000;
const MAX_ANSWER = 500;
const MAX_WORD = 100;
/** Underline recognition sends every question found on the page. */
const MAX_QUESTIONS = 50;
/** All question text of one underline request together: plenty for one photographed page. */
const MAX_UNDERLINE_TEXT = 8000;

/**
 * Most input tokens one image may count for. A 1280px photo from the app is about 1,400; a larger image sent by
 * something else counts more, up to the model's own limit per image. Only used to reserve budget before the call.
 */
const IMAGE_TOKENS = 16_384;

class BadRequest extends Error {}
const bad = (message: string): never => { throw new BadRequest(message); };

function string(value: unknown, what: string, max: number): string {
  if (typeof value !== 'string' || !value.trim()) return bad(`缺少${what}`);
  if (value.length > max) return bad(`${what}太长（最多 ${max} 个字）`);
  return value;
}

function image(value: unknown): string {
  const text = string(value, '照片', MAX_IMAGE_CHARS);
  if (!/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(text)) bad('照片格式不对');
  return text;
}

function question(value: unknown): { text: string; answer: string } {
  const record = (value && typeof value === 'object' ? value : bad('题目格式不对')) as Record<string, unknown>;
  return {
    text: string(record.text, '题目', MAX_TEXT),
    answer: typeof record.answer === 'string' && record.answer.length <= MAX_ANSWER ? record.answer : bad(`答案格式不对，或超过 ${MAX_ANSWER} 个字`),
  };
}

/** Only the five tasks the app needs, with their own fields; anything else is refused. */
export function parseTask(body: Record<string, unknown>): ModelTask {
  switch (body.task) {
    case 'words': {
      const language = body.language === 'chinese' || body.language === 'english' ? body.language as DictationLanguage : bad('语言不对');
      return { task: 'words', image: image(body.image), language };
    }
    case 'questions': return { task: 'questions', image: image(body.image) };
    case 'underlines': {
      const list = Array.isArray(body.questions) && body.questions.length ? body.questions : bad('缺少题目');
      if (list.length > MAX_QUESTIONS) bad(`试用时一次最多识别 ${MAX_QUESTIONS} 道题的划线，可以先删掉几道`);
      const questions = list.map(question);
      if (questions.reduce((sum, item) => sum + item.text.length, 0) > MAX_UNDERLINE_TEXT) bad(`题目文字太多（最多 ${MAX_UNDERLINE_TEXT} 个字），可以先删掉几道`);
      return { task: 'underlines', image: image(body.image), questions };
    }
    case 'translate': return { task: 'translate', text: string(body.text, '要翻译的词', MAX_WORD) };
    case 'generate': return { task: 'generate', source: question(body.source) };
    default: return bad('不支持的任务');
  }
}

/**
 * Whether a reply is what the task asks for, checked with the app's own parsers. A reply that is not (for example
 * because the material told the model to do something else) is not passed on: the endpoint stays useless as a
 * general model. The app's parsers make the same check, so a real user loses nothing.
 */
export function usableReply(task: ModelTask, reply: string): string | null {
  try {
    switch (task.task) {
      case 'words': return parseWords(reply, task.language).length ? reply : null;
      case 'questions': return parseQuestions(reply).length ? reply : null;
      case 'underlines': parseUnderlineHits(reply); return reply;
      case 'translate': { const text = cleanTranslation(reply); return text && text.length <= MAX_WORD ? text : null; }
      case 'generate': return parseGenerated(reply) ? reply : null;
    }
  } catch {
    return null;
  }
}

/** Tokens of the text in a request, counted high: about one per Chinese character, fewer for English. */
const textTokens = (text: string) => Math.ceil(text.length * 1.2) + 50;

/** The most a task can cost, in millionths of a yuan: its input counted high plus the longest reply allowed. */
export function maxCost(task: ModelTask, limits: Limits): number {
  const [inPrice, outPrice] = kindOf(task.task) === 'generate' ? limits.priceMax : limits.priceFlash;
  let input = 0;
  for (const message of modelRequest(defaultSettings(), task).messages) {
    const parts = typeof message.content === 'string' ? [{ type: 'text' as const, text: message.content }] : message.content;
    for (const part of parts) input += part.type === 'text' ? textTokens(part.text) : IMAGE_TOKENS;
  }
  return Math.ceil(input * inPrice + MAX_REPLY_TOKENS[task.task] * outPrice);
}

const DEVICE = /^[A-Za-z0-9-]{8,64}$/;

/** "2026-10-31" in Beijing time: the day allowances reset at midnight there. */
export const beijingDay = (ms: number) => new Date(ms + 8 * 3_600_000).toISOString().slice(0, 10);

/** Errors after which DashScope may still have done (and billed) the work: the request went out, no answer came back. */
const MAYBE_BILLED = new Set(['timeout', 'network']);
/** Errors worth trying again; the others (the server's key, a refused request) fail the same way every time. */
const RETRYABLE = new Set(['timeout', 'network', 'server', 'rate']);

export function createProxy(deps: Dependencies) {
  const { counters, limits } = deps;
  const log = deps.log ?? (entry => console.error(JSON.stringify(entry)));
  const deviceKeys = (device: string) => (['photo', 'translate', 'generate'] as Kind[]).map(kind => `device:${device}:${kind}`);
  const deviceLimit: Record<Kind, number> = { photo: limits.devicePhoto, translate: limits.deviceTranslate, generate: limits.deviceGenerate };

  const remaining = async (device: string): Promise<Remaining> => {
    const [photo, translate, generate] = await counters.get(deviceKeys(device));
    return {
      photo: Math.max(0, limits.devicePhoto - photo),
      translate: Math.max(0, limits.deviceTranslate - translate),
      generate: Math.max(0, limits.deviceGenerate - generate),
    };
  };

  /** Counter changes after the model was called: a failure is logged, never turned into a failed request. */
  const quietly = async (what: string, change: () => Promise<void>) => {
    try { await change(); } catch (error) { log({ counter: what, failed: (error as Error)?.message ?? String(error) }); }
  };

  /** GET ?device=…: what is left for this device. */
  async function status(device: unknown): Promise<Result> {
    if (typeof device !== 'string' || !DEVICE.test(device)) return { status: 400, body: { error: 'bad-request', message: '设备标识不对' } };
    return { status: 200, body: { remaining: await remaining(device) } };
  }

  /** POST: one model call. */
  async function call(body: unknown, ip: string): Promise<Result> {
    let task: ModelTask;
    const record = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
    const device = record.device;
    try {
      if (typeof device !== 'string' || !DEVICE.test(device)) bad('设备标识不对');
      task = parseTask(record);
    } catch (error) {
      if (error instanceof BadRequest) return { status: 400, body: { error: 'bad-request', message: error.message } };
      throw error;
    }
    const id = device as string;
    const kind = kindOf(task.task);
    const group = kind === 'generate' ? 'generate' : 'basic';
    const day = beijingDay(deps.now());
    const ipHash = createHash('sha256').update(`${deps.ipSalt}:${ip}`).digest('hex').slice(0, 24);
    const keys = { device: `device:${id}:${kind}`, ip: `ip:${day}:${ipHash}:${group}`, cost: `cost:${day}:${group}` };
    const ipLimit = group === 'generate' ? limits.ipGenerate : limits.ipBasic;
    const budget = Math.round((group === 'generate' ? limits.budgetGenerate : limits.budgetBasic) * 1_000_000);
    const reserved = maxCost(task, limits);
    const what = { photo: '拍照识别', translate: '翻译', generate: '举一反三' }[kind];
    const today = { status: 429 as const, body: { error: 'quota', message: `今天的${what}试用名额用完了，明天再试，或在设置里填写自己的千问 API Key` } };

    // Reserve in this order, giving back what was taken when a later one does not fit.
    if (!await counters.addWithin(keys.device, 1, deviceLimit[kind])) {
      return { status: 429, body: { error: 'quota', message: `这台设备的${what}试用次数用完了。在设置里填写自己的千问 API Key，就可以继续使用` } };
    }
    if (!await counters.addWithin(keys.ip, 1, ipLimit)) {
      await quietly(keys.device, () => counters.add(keys.device, -1));
      return today;
    }
    if (!await counters.addWithin(keys.cost, reserved, budget)) {
      await quietly(keys.device, () => counters.add(keys.device, -1));
      await quietly(keys.ip, () => counters.add(keys.ip, -1));
      return today;
    }

    const [inPrice, outPrice] = kind === 'generate' ? limits.priceMax : limits.priceFlash;
    const costOf = (usage: Usage) => Math.ceil(usage.input * inPrice + usage.output * outPrice);
    let reply: string;
    let usage: Usage | null;
    try {
      ({ reply, usage } = await deps.model(task));
    } catch (error) {
      const failure = (error ?? {}) as ModelFailure;
      // The use is given back; the money only when nothing can have been spent.
      const spent = failure.usage ? costOf(failure.usage) : MAYBE_BILLED.has(failure.kind ?? '') ? reserved : 0;
      await quietly(keys.device, () => counters.add(keys.device, -1));
      await quietly(keys.ip, () => counters.add(keys.ip, -1));
      await quietly(keys.cost, () => counters.add(keys.cost, spent - reserved));
      log({ task: task.task, failed: failure.kind ?? 'unknown', message: failure.message, cost: spent });
      const retry = RETRYABLE.has(failure.kind ?? '');
      // The server's own key or model being wrong, or DashScope refusing the request, is not something the user can
      // fix; its message (in English, about the server's account) stays in the log.
      return {
        status: 502,
        body: retry
          ? { error: 'upstream', message: '千问暂时没有返回结果，请稍后再试' }
          : { error: 'upstream-fatal', message: '试用服务暂时不能处理这个请求，可以稍后再试，或在设置里填写自己的千问 API Key' },
      };
    }

    const cost = usage ? costOf(usage) : reserved;
    await quietly(keys.cost, () => counters.add(keys.cost, cost - reserved));
    console.log(JSON.stringify({ task: task.task, device: id.slice(0, 8), input: usage?.input, output: usage?.output, cost }));
    // A reply the app could not use either is replaced by an empty one: the app then shows its usual
    // "nothing recognised" message, and the use counts, as it would with the user's own key.
    const usable = usableReply(task, reply) ?? '';
    let left: Remaining | undefined;
    try { left = await remaining(id); } catch { /* the reply matters more than the count */ }
    return { status: 200, body: { reply: usable, ...(left ? { remaining: left } : {}) } };
  }

  return { status, call };
}

/** For tests and local runs. */
export class MemoryCounters implements Counters {
  readonly values = new Map<string, number>();
  async get(keys: string[]) { return keys.map(key => this.values.get(key) ?? 0); }
  async add(key: string, amount: number) { this.values.set(key, (this.values.get(key) ?? 0) + amount); }
  async addWithin(key: string, amount: number, limit: number) {
    const current = this.values.get(key) ?? 0;
    if (current + amount > limit) return false;
    this.values.set(key, current + amount);
    return true;
  }
}

/**
 * At most `limit` requests per key (an IP) in any `windowMs`, kept in this instance's memory. It runs before any
 * table access, so a flood of requests that never reach the model cannot run up function and table costs either.
 */
export class RateLimiter {
  private readonly hits = new Map<string, number[]>();

  constructor(private readonly limit: number, private readonly windowMs: number) {}

  allow(key: string, now: number): boolean {
    const recent = (this.hits.get(key) ?? []).filter(time => now - time < this.windowMs);
    const allowed = recent.length < this.limit;
    if (allowed) recent.push(now);
    this.hits.set(key, recent);
    // Forget quiet keys now and then, so memory does not grow with every address ever seen.
    if (this.hits.size > 10_000) for (const [other, times] of this.hits) if (!times.some(time => now - time < this.windowMs)) this.hits.delete(other);
    return allowed;
  }
}
