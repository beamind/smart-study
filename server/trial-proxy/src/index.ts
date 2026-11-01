import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { chatWithUsage } from '../../../src/ai/dashscope';
import { modelRequest } from '../../../src/ai/tasks';
import { defaultSettings } from '../../../src/data/defaults';
import { createProxy, DEFAULT_LIMITS, MemoryCounters, RateLimiter, replyTokens, type Counters, type Limits } from './proxy';
import { TablestoreCounters } from './tablestore';

/**
 * Entry point for an Alibaba Cloud Function Compute web function (custom runtime, Node.js 20 or later): a plain HTTP server
 * on FC_SERVER_PORT (9000). Configuration comes from environment variables, see docs/trial-proxy.md.
 */

const env = process.env;
const number = (name: string, fallback: number) => {
  const value = env[name] === undefined || env[name] === '' ? fallback : Number(env[name]);
  if (!Number.isFinite(value) || value < 0) throw new Error(`环境变量 ${name} 应为非负数`);
  return value;
};
const pair = (name: string, fallback: [number, number]): [number, number] => {
  if (!env[name]) return fallback;
  const [input, output] = env[name]!.split(',').map(Number);
  if (!Number.isFinite(input) || !Number.isFinite(output)) throw new Error(`环境变量 ${name} 应为 "输入单价,输出单价"`);
  return [input, output];
};

const apiKey = env.DASHSCOPE_API_KEY ?? '';
if (!apiKey) throw new Error('缺少环境变量 DASHSCOPE_API_KEY');
// Without a salt the stored IP hashes could be reversed by trying every IPv4 address.
const ipSalt = env.IP_SALT ?? '';
if (ipSalt.length < 16) throw new Error('缺少环境变量 IP_SALT（至少 16 个字符的随机字符串，见 docs/trial-proxy.md）');

const limits: Limits = {
  devicePhoto: number('DEVICE_PHOTO', DEFAULT_LIMITS.devicePhoto),
  deviceTranslate: number('DEVICE_TRANSLATE', DEFAULT_LIMITS.deviceTranslate),
  deviceGenerate: number('DEVICE_GENERATE', DEFAULT_LIMITS.deviceGenerate),
  ipBasic: number('IP_BASIC', DEFAULT_LIMITS.ipBasic),
  ipGenerate: number('IP_GENERATE', DEFAULT_LIMITS.ipGenerate),
  budgetBasic: number('BUDGET_BASIC_YUAN', DEFAULT_LIMITS.budgetBasic),
  budgetGenerate: number('BUDGET_GENERATE_YUAN', DEFAULT_LIMITS.budgetGenerate),
  priceFlash: pair('PRICE_FLASH', DEFAULT_LIMITS.priceFlash),
  priceMax: pair('PRICE_MAX', DEFAULT_LIMITS.priceMax),
};

// TRIAL_STORE=memory only for trying the server on a PC: counts are lost on every restart.
const counters: Counters = env.TRIAL_STORE === 'memory' ? new MemoryCounters() : new TablestoreCounters({
  endpoint: env.TABLESTORE_ENDPOINT ?? '',
  instance: env.TABLESTORE_INSTANCE ?? '',
  table: env.TABLESTORE_TABLE || 'trial_counters',
  // Function Compute sets these from the function's role.
  accessKeyId: env.ALIBABA_CLOUD_ACCESS_KEY_ID ?? '',
  accessKeySecret: env.ALIBABA_CLOUD_ACCESS_KEY_SECRET ?? '',
  securityToken: env.ALIBABA_CLOUD_SECURITY_TOKEN,
});

// Models and prompts are the app's defaults; the request cannot choose them.
const settings = defaultSettings();
// One attempt, finishing well before the app gives up (TRIAL_TIMEOUT_MS, 55 s, counted from when the app starts
// sending; it also covers uploading the photo, a cold start and the counter reads and writes): the app retries, and
// each retry is a new, counted request. A server-side retry would keep spending after the app has given up.
// Function Compute's own limit for the function is 60 s (docs/trial-proxy.md, step 5).
const MODEL_TIMEOUT_MS = 40_000;
/**
 * Model calls running on this instance at once (requests waiting for budget do not count); more get a 503 the app
 * retries. Function Compute's "单实例并发度" is set a little higher (10, docs/trial-proxy.md, step 5), so requests
 * waiting for budget do not take the places of others.
 */
const MAX_IN_FLIGHT = 8;
const proxy = createProxy({
  counters, limits, now: Date.now, ipSalt, maxInFlight: MAX_IN_FLIGHT,
  model: task => chatWithUsage({
    ...modelRequest(settings, task), apiKey, maxTokens: replyTokens(task),
    timeoutMs: MODEL_TIMEOUT_MS, maxAttempts: 1,
  }),
});

const MAX_BODY = 2_500_000;
/** Requests one IP may make per minute on this instance, of any kind. The app needs far fewer, even translating a list. */
const PER_IP_PER_MINUTE = 60;
const limiter = new RateLimiter(PER_IP_PER_MINUTE, 60_000);

function send(response: ServerResponse, status: number, body: unknown, close = false) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    // The app runs at https://localhost (Android) or a PC browser page; it sends no cookies.
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age': '86400',
    ...(close ? { Connection: 'close' } : {}),
  });
  response.end(body === undefined ? '' : JSON.stringify(body));
}

/** The body, or null when it is larger than MAX_BODY: the rest is read and dropped, so the 413 reaches the app. */
function readBody(request: IncomingMessage): Promise<string | null> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    request.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size <= MAX_BODY) chunks.push(chunk);
    });
    request.on('end', () => resolve(size > MAX_BODY ? null : Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

/**
 * The address the request came from. Function Compute's gateway appends it to X-Forwarded-For, so the last entry
 * is the one the gateway saw; earlier entries can be written by the client (checked after deploying, docs/trial-proxy.md).
 */
function clientIp(request: IncomingMessage): string {
  const forwarded = String(request.headers['x-forwarded-for'] ?? '').split(',').map(item => item.trim()).filter(Boolean);
  return forwarded.at(-1) ?? request.socket.remoteAddress ?? 'unknown';
}

createServer(async (request, response) => {
  try {
    if (request.method === 'OPTIONS') { send(response, 204, undefined); return; }
    // Before anything that reads the table: requests that cost no model money still cost function and table time.
    if (!limiter.allow(clientIp(request), Date.now())) {
      send(response, 429, { error: 'rate', message: '请求太频繁，请稍后再试' });
      return;
    }
    if (request.method === 'GET') {
      const device = new URL(request.url ?? '/', 'http://localhost').searchParams.get('device');
      const result = await proxy.status(device);
      send(response, result.status, result.body);
      return;
    }
    if (request.method !== 'POST') { send(response, 405, { error: 'method', message: '只支持 GET 和 POST' }); return; }
    const text = await readBody(request);
    if (text === null) { send(response, 413, { error: 'too-large', message: '请求太大' }, true); return; }
    let body: unknown;
    try { body = JSON.parse(text); } catch { send(response, 400, { error: 'bad-request', message: '请求不是 JSON' }); return; }
    // The app may give up (cancel, leave the page) while this waits for budget: then nothing is reserved for it.
    const gone = new AbortController();
    response.on('close', () => { if (!response.writableFinished) gone.abort(); });
    const result = await proxy.call(body, clientIp(request), gone.signal);
    send(response, result.status, result.body);
  } catch (error) {
    console.error(error);
    send(response, 500, { error: 'server', message: '试用服务出错了' });
  }
}).listen(Number(env.FC_SERVER_PORT ?? 9000), () => console.log('trial proxy listening'));
