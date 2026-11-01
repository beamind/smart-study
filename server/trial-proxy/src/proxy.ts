import { createHash } from 'node:crypto';
import { cleanTranslation, parseGenerated, parseQuestions, parseWords } from '../../../src/ai/parse';
import type { Usage } from '../../../src/ai/dashscope';
import { containsWord, hitWord, MAX_REPLY_TOKENS, modelRequest, parseUnderlineHits, type ModelTask } from '../../../src/ai/tasks';
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
  | { status: 400 | 429 | 502 | 503; body: { error: string; message: string } };

type Kind = 'photo' | 'translate' | 'generate';
type Group = 'basic' | 'generate';
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

/** A dictation list from one photo is never this long. */
const MAX_WORDS = 100;
/** A character no question contains: an underline hit that cannot be found keeps counting as not found. */
const NOT_FOUND = '\uE000';

/**
 * The reply rebuilt from what the app's own parsers find in it, or null when they find nothing. The raw text is never
 * passed on: words, questions and hits are written back as JSON the app parses to the same result, so text around
 * them (for example an essay the material asked for) goes nowhere. An underline hit goes back only when the app would
 * find its word in a question sent, so hits carry nothing new. Question text itself stays free text, bounded by the
 * reply length.
 */
export function usableReply(task: ModelTask, reply: string): string | null {
  const asJson = (item: { text: string; answer: string }) => ({ question: item.text, answer: item.answer });
  try {
    switch (task.task) {
      case 'words': {
        const words = parseWords(reply, task.language).slice(0, MAX_WORDS);
        return words.length ? JSON.stringify(words) : null;
      }
      case 'questions': {
        const questions = parseQuestions(reply);
        return questions.length ? JSON.stringify(questions.map(asJson)) : null;
      }
      case 'underlines': {
        // Kept as the app would use them; a hit the app would not find in any question is replaced by a marker that
        // matches nothing, so the app still counts it as "not found" and nothing else of it goes back.
        const hits = parseUnderlineHits(reply).slice(0, 200).map(hit => (
          task.questions.some(item => containsWord(item.text, hitWord(hit)))
            ? { question: hit.question === null ? '' : String(hit.question + 1), underlined: hit.underlined, word: hit.word }
            : { question: '', underlined: NOT_FOUND, word: NOT_FOUND }));
        return JSON.stringify(hits);
      }
      case 'translate': { const text = cleanTranslation(reply); return text && text.length <= MAX_WORD ? text : null; }
      case 'generate': { const generated = parseGenerated(reply); return generated ? JSON.stringify(asJson(generated)) : null; }
    }
  } catch {
    return null;
  }
}

/**
 * Tokens of the text in a request, counted high: a common Chinese character or a few Latin letters are about one
 * token; any other character may be split into one token per UTF-8 byte (2–4).
 */
const encoder = new TextEncoder();
function textTokens(text: string): number {
  let tokens = 50;
  for (const char of text) {
    const code = char.codePointAt(0)!;
    tokens += code < 0x80 || (code >= 0x4E00 && code <= 0x9FFF) || (code >= 0x3000 && code <= 0x303F) || (code >= 0xFF00 && code <= 0xFFEF)
      ? 1.2 : encoder.encode(char).length;
  }
  return Math.ceil(tokens);
}

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
/** How long a request refused only by running calls waits for one to settle; within the app's 55 s with the 40 s call. */
const BUSY_WAIT_MS = 8000;

export function createProxy(deps: Dependencies) {
  const { counters, limits } = deps;
  const log = deps.log ?? (entry => console.error(JSON.stringify(entry)));
  const deviceKeys = (device: string) => (['photo', 'translate', 'generate'] as Kind[]).map(kind => `device:${device}:${kind}`);
  const deviceLimit: Record<Kind, number> = { photo: limits.devicePhoto, translate: limits.deviceTranslate, generate: limits.deviceGenerate };
  /** Budget reserved by model calls still running on this instance, by group, and requests waiting for one to finish. */
  const held: Record<Group, number> = { basic: 0, generate: 0 };
  const waiting: Record<Group, Array<() => void>> = { basic: [], generate: [] };

  const remaining = async (device: string): Promise<Remaining> => {
    const [photo, translate, generate] = await counters.get(deviceKeys(device));
    return {
      photo: Math.max(0, limits.devicePhoto - photo),
      translate: Math.max(0, limits.deviceTranslate - translate),
      generate: Math.max(0, limits.deviceGenerate - generate),
    };
  };

  /** Counter changes that must not fail the request (giving back, settling): a failure is logged. */
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
    const group: Group = kind === 'generate' ? 'generate' : 'basic';
    const day = beijingDay(deps.now());
    const ipHash = createHash('sha256').update(`${deps.ipSalt}:${ip}`).digest('hex').slice(0, 24);
    const keys = { device: `device:${id}:${kind}`, ip: `ip:${day}:${ipHash}:${group}`, cost: `cost:${day}:${group}` };
    const ipLimit = group === 'generate' ? limits.ipGenerate : limits.ipBasic;
    const budget = Math.round((group === 'generate' ? limits.budgetGenerate : limits.budgetBasic) * 1_000_000);
    const reserved = maxCost(task, limits);
    const what = { photo: '拍照识别', translate: '翻译', generate: '举一反三' }[kind];
    const deviceUsed: Result = { status: 429, body: { error: 'quota', message: `这台设备的${what}试用次数用完了。在设置里填写自己的千问 API Key，就可以继续使用` } };
    const today: Result = { status: 429, body: { error: 'quota', message: `今天的${what}试用名额用完了，明天再试，或在设置里填写自己的千问 API Key` } };
    // Requests still running hold their reservations, and settle to much less within seconds: try again soon.
    const busy: Result = { status: 503, body: { error: 'busy', message: '试用的人有点多，请过一会儿再试' } };
    /** Over the budget by what is already spent ("today"), or only because of what calls on this instance still hold. */
    const budgetRefusal = (current: number) => (current - held[group] + reserved <= budget ? busy : today);

    /**
     * Reserve the device use, the IP use and the most this can cost, in this order. A read first refuses what cannot
     * fit without writing anything; each reservation then holds on condition that it still fits, because other
     * requests may be doing the same. What was taken is given back when a later one does not fit or the table fails.
     */
    const reserve = async (): Promise<Result | null> => {
      const [deviceNow, ipNow, costNow] = await counters.get([keys.device, keys.ip, keys.cost]);
      if (deviceNow >= deviceLimit[kind]) return deviceUsed;
      if (ipNow >= ipLimit) return today;
      if (costNow + reserved > budget) return budgetRefusal(costNow);
      const taken: Array<[string, number]> = [];
      const giveBack = async () => { for (const [key, amount] of taken) await quietly(key, () => counters.add(key, -amount)); };
      // A write that failed without saying whether it happened: the uses count as taken, so they are given back (at
      // worst the user gets one more); the money does not, so the budget is never lowered by mistake.
      const take = async (key: string, amount: number, limit: number, giveBackIfUnsure: boolean) => {
        try {
          const ok = await counters.addWithin(key, amount, limit);
          if (ok) taken.push([key, amount]);
          return ok;
        } catch (error) {
          if (giveBackIfUnsure) taken.push([key, amount]);
          throw error;
        }
      };
      try {
        if (!await take(keys.device, 1, deviceLimit[kind], true)) return deviceUsed;
        if (!await take(keys.ip, 1, ipLimit, true)) { await giveBack(); return today; }
        if (!await take(keys.cost, reserved, budget, false)) {
          await giveBack();
          const [current] = await counters.get([keys.cost]).catch(() => [Number.NaN]);
          return Number.isNaN(current) ? busy : budgetRefusal(current);
        }
        return null;
      } catch (error) {
        await giveBack();
        log({ task: task.task, counter: 'reserve', failed: (error as Error)?.message ?? String(error) });
        return busy;
      }
    };

    let refused = await reserve();
    // Only calls on this instance that are still running hold the budget (often the other two of the same family's
    // three 举一反三): wait a few seconds for one to settle and try once more, rather than send the app away.
    if (refused === busy && held[group] > 0) {
      await settled(group, BUSY_WAIT_MS);
      refused = await reserve();
    }
    if (refused) return refused;

    held[group] += reserved;
    try {
      return await callModel(task, kind, id, keys, reserved);
    } finally {
      held[group] -= reserved;
      for (const wake of waiting[group].splice(0)) wake();
    }
  }

  /** Resolves when a call of this group on this instance finishes, or after `ms`. */
  function settled(group: Group, ms: number): Promise<void> {
    return new Promise(resolve => {
      const timer = setTimeout(done, ms);
      function done() { clearTimeout(timer); resolve(); }
      waiting[group].push(done);
    });
  }

  /** The model call after everything is reserved, and settling the reservation afterwards. */
  async function callModel(task: ModelTask, kind: Kind, id: string, keys: { device: string; ip: string; cost: string }, reserved: number): Promise<Result> {
    const [inPrice, outPrice] = kind === 'generate' ? limits.priceMax : limits.priceFlash;
    const costOf = (usage: Usage) => Math.ceil(usage.input * inPrice + usage.output * outPrice);
    let reply: string;
    let usage: Usage | null;
    try {
      ({ reply, usage } = await deps.model(task));
    } catch (error) {
      const failure = (error ?? {}) as ModelFailure;
      // The use is given back; the money only when nothing can have been spent. A reply that came back empty was
      // billed: by its reported usage, or the whole reservation when DashScope did not say.
      const spent = failure.usage ? costOf(failure.usage)
        : failure.usage === null || MAYBE_BILLED.has(failure.kind ?? '') ? reserved : 0;
      await quietly(keys.device, () => counters.add(keys.device, -1));
      await quietly(keys.ip, () => counters.add(keys.ip, -1));
      await quietly(keys.cost, () => counters.add(keys.cost, spent - reserved));
      log({ task: task.task, failed: failure.kind ?? 'unknown', message: failure.message, cost: spent });
      // The server's own key or model being wrong, or DashScope refusing the request, is not something the user can
      // fix; its message (in English, about the server's account) stays in the log.
      return {
        status: 502,
        body: RETRYABLE.has(failure.kind ?? '')
          ? { error: 'upstream', message: '千问暂时没有返回结果，请稍后再试' }
          : { error: 'upstream-fatal', message: '试用服务暂时不能处理这个请求，可以稍后再试，或在设置里填写自己的千问 API Key' },
      };
    }

    const cost = usage ? costOf(usage) : reserved;
    await quietly(keys.cost, () => counters.add(keys.cost, cost - reserved));
    console.log(JSON.stringify({ task: task.task, device: id.slice(0, 8), input: usage?.input, output: usage?.output, cost }));
    // A reply the app could not use either becomes an empty one: the app then shows its usual "nothing recognised"
    // message, and the use counts, as it would with the user's own key.
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
