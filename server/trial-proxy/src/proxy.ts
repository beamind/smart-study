import { createHash } from 'node:crypto';
import type { Usage } from '../../../src/ai/dashscope';
import type { ModelTask } from '../../../src/ai/tasks';
import type { DictationLanguage } from '../../../src/data/schema';

/**
 * The trial proxy (docs/decisions/004-trial-proxy.md): checks a request, its device and IP allowance and the day's
 * budget, calls the model with the server's key, and counts what was used. No platform code here, so it is tested
 * with in-memory counters and a fake model.
 */

/** Counters that survive between requests (Tablestore in production). */
export interface Counters {
  get(keys: string[]): Promise<number[]>;
  add(key: string, amount: number): Promise<void>;
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

export interface Dependencies {
  counters: Counters;
  /** Calls the model with the server's key; returns the reply and the tokens used. */
  model(task: ModelTask): Promise<{ reply: string; usage: Usage }>;
  limits: Limits;
  now(): number;
  /** Mixed into IP hashes, so the stored keys do not reveal addresses. */
  ipSalt: string;
}

export interface Remaining { photo: number; translate: number; generate: number }

export type Result =
  | { status: 200; body: { reply: string; remaining: Remaining } | { remaining: Remaining } }
  | { status: 400 | 429 | 502; body: { error: string; message: string } };

type Kind = 'photo' | 'translate' | 'generate';
const kindOf = (task: ModelTask['task']): Kind => (task === 'translate' ? 'translate' : task === 'generate' ? 'generate' : 'photo');

/** Photo recognition limits: a 1280px JPEG is about 0.3 MB; 2 MB of base64 leaves room and stops abuse. */
const MAX_IMAGE_CHARS = 2_000_000;
const MAX_TEXT = 2000;
const MAX_ANSWER = 500;
const MAX_WORD = 100;
const MAX_QUESTIONS = 30;

class BadRequest extends Error {}
const bad = (message: string): never => { throw new BadRequest(message); };

function string(value: unknown, what: string, max: number): string {
  if (typeof value !== 'string' || !value.trim()) return bad(`缺少${what}`);
  if (value.length > max) return bad(`${what}太长`);
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
    answer: typeof record.answer === 'string' && record.answer.length <= MAX_ANSWER ? record.answer : bad('答案格式不对'),
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
      const list = Array.isArray(body.questions) && body.questions.length && body.questions.length <= MAX_QUESTIONS ? body.questions : bad('题目数量不对');
      return { task: 'underlines', image: image(body.image), questions: list.map(question) };
    }
    case 'translate': return { task: 'translate', text: string(body.text, '要翻译的词', MAX_WORD) };
    case 'generate': return { task: 'generate', source: question(body.source) };
    default: return bad('不支持的任务');
  }
}

const DEVICE = /^[A-Za-z0-9-]{8,64}$/;

/** "2026-10-31" in Beijing time: the day allowances reset at midnight there. */
export const beijingDay = (ms: number) => new Date(ms + 8 * 3_600_000).toISOString().slice(0, 10);

export function createProxy(deps: Dependencies) {
  const { counters, limits } = deps;
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
    const [used, ipUsed, spent] = await counters.get([keys.device, keys.ip, keys.cost]);

    const ipLimit = group === 'generate' ? limits.ipGenerate : limits.ipBasic;
    const budget = Math.round((group === 'generate' ? limits.budgetGenerate : limits.budgetBasic) * 1_000_000);
    const what = { photo: '拍照识别', translate: '翻译', generate: '举一反三' }[kind];
    if (used >= deviceLimit[kind]) {
      return { status: 429, body: { error: 'quota', message: `这台设备的${what}试用次数用完了。在设置里填写自己的千问 API Key，就可以继续使用` } };
    }
    if (ipUsed >= ipLimit || spent >= budget) {
      return { status: 429, body: { error: 'quota', message: `今天的${what}试用名额用完了，明天再试，或在设置里填写自己的千问 API Key` } };
    }

    let reply: string;
    let usage: Usage;
    try {
      ({ reply, usage } = await deps.model(task));
    } catch (error) {
      // The server's own key or model being wrong is not something the user can fix; their message says "check settings".
      const kind = (error as { kind?: string }).kind;
      console.error(JSON.stringify({ task: task.task, failed: kind ?? 'unknown', message: (error as Error).message }));
      const message = kind === 'auth' || kind === 'model' || kind === 'no-key' ? '试用服务暂时不可用，可以稍后再试，或在设置里填写自己的千问 API Key'
        : `千问暂时没有返回结果：${(error as Error).message}`;
      return { status: 502, body: { error: 'upstream', message } };
    }
    // Cost in millionths of a yuan: price per million tokens times tokens.
    const [inPrice, outPrice] = kind === 'generate' ? limits.priceMax : limits.priceFlash;
    const cost = Math.ceil(usage.input * inPrice + usage.output * outPrice);
    await Promise.all([counters.add(keys.device, 1), counters.add(keys.ip, 1), counters.add(keys.cost, cost)]);
    console.log(JSON.stringify({ task: task.task, device: id.slice(0, 8), input: usage.input, output: usage.output, cost }));
    return { status: 200, body: { reply, remaining: await remaining(id) } };
  }

  return { status, call };
}

/** For tests and local runs. */
export class MemoryCounters implements Counters {
  readonly values = new Map<string, number>();
  async get(keys: string[]) { return keys.map(key => this.values.get(key) ?? 0); }
  async add(key: string, amount: number) { this.values.set(key, (this.values.get(key) ?? 0) + amount); }
}
