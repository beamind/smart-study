/**
 * DashScope (阿里云百炼) through its OpenAI-compatible endpoint. Plain fetch, so the same code runs
 * in the Android WebView, a PC browser and Node scripts. The endpoint allows CORS (checked 2026-10-31).
 */
export const DASHSCOPE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions';

export type ContentPart = { type: 'text'; text: string } | { type: 'image_url'; image_url: { url: string } };
export interface ChatMessage { role: 'system' | 'user'; content: string | ContentPart[] }

export interface ChatRequest {
  apiKey: string;
  model: string;
  messages: ChatMessage[];
  temperature?: number;
  /**
   * Qwen 3.5+ models think step by step by default, which makes simple tasks (reading a word list,
   * translating one phrase) 5–20× slower. false turns it off; undefined leaves the model's default.
   */
  thinking?: boolean;
  /** Upper limit for the reply length (the trial proxy sets it, so one request cannot cost much). */
  maxTokens?: number;
  timeoutMs: number;
  /** Total attempts including the first one. */
  maxAttempts: number;
  signal?: AbortSignal;
}

export type AiErrorKind = 'no-key' | 'auth' | 'model' | 'rate' | 'timeout' | 'network' | 'server' | 'bad-request' | 'bad-response' | 'cancelled'
  /** The trial allowance is used up (docs/decisions/004); retrying does not help, the user's own key does. */
  | 'quota';

export class AiError extends Error {
  /** Tokens DashScope reported for a reply that came back but could not be used (the trial proxy still counts them). */
  usage?: Usage | null;
  /** How long to wait before trying again, when the other side said (the trial proxy when it is busy). */
  retryAfterMs?: number;
  constructor(readonly kind: AiErrorKind, message: string) {
    super(message);
  }
  get retryable() {
    return this.kind === 'rate' || this.kind === 'timeout' || this.kind === 'network' || this.kind === 'server';
  }
}

interface Dependencies {
  fetch: typeof fetch;
  /** Waits `ms`; stops early (and clears its timer) when `signal` aborts. */
  sleep(ms: number, signal?: AbortSignal): Promise<void>;
}
const defaults: Dependencies = {
  fetch: (...args) => fetch(...args),
  sleep: (ms, signal) => new Promise(resolve => {
    const timer = setTimeout(done, ms);
    function done() { clearTimeout(timer); signal?.removeEventListener('abort', done); resolve(); }
    signal?.addEventListener('abort', done, { once: true });
  }),
};

/** Tokens the request used, as DashScope reports them. */
export interface Usage { input: number; output: number }

async function once(request: ChatRequest, deps: Dependencies): Promise<{ reply: string; usage: Usage | null }> {
  // An abort listener never fires for a signal that is already aborted, so check first: no request, no cost.
  if (request.signal?.aborted) throw new AiError('cancelled', '已取消');
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  request.signal?.addEventListener('abort', onAbort, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, request.timeoutMs);
  const stopped = () => {
    if (request.signal?.aborted) return new AiError('cancelled', '已取消');
    if (timedOut) return new AiError('timeout', `等了 ${Math.round(request.timeoutMs / 1000)} 秒还没有结果`);
    return null;
  };
  try {
    let response: Response;
    try {
      response = await deps.fetch(DASHSCOPE_URL, {
        method: 'POST',
        signal: controller.signal,
        headers: { Authorization: `Bearer ${request.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: request.model,
          messages: request.messages,
          ...(request.temperature !== undefined ? { temperature: request.temperature } : {}),
          ...(request.thinking !== undefined ? { enable_thinking: request.thinking } : {}),
          ...(request.maxTokens !== undefined ? { max_tokens: request.maxTokens } : {}),
        }),
      });
    } catch {
      throw stopped() ?? new AiError('network', '网络连接失败，请检查网络');
    }
    let text: string;
    try {
      text = await response.text();
    } catch {
      // Cancelled or timed out while the reply was arriving: report that, not an empty reply.
      throw stopped() ?? new AiError('network', '接收结果时网络中断，请重试');
    }
    let body: {
      choices?: { message?: { content?: unknown } }[]; usage?: { prompt_tokens?: number; completion_tokens?: number };
      error?: { code?: string; message?: string }; code?: string; message?: string;
    } | null = null;
    try { body = JSON.parse(text); } catch { /* not JSON */ }
    const code = body?.error?.code ?? body?.code ?? '';
    if (!response.ok) {
      const detail = body?.error?.message ?? body?.message ?? text.slice(0, 120);
      if (response.status === 401 || response.status === 403) throw new AiError('auth', 'API Key 无效或没有权限，请在设置里检查');
      if (response.status === 404 || code === 'model_not_found') throw new AiError('model', `模型 ${request.model} 不可用，请在设置里换一个模型`);
      if (response.status === 429) throw new AiError('rate', '请求太频繁或额度不足，请稍后再试');
      if (response.status >= 500) throw new AiError('server', `千问服务暂时出错（${response.status}）`);
      throw new AiError('bad-request', `请求被拒绝（${response.status}）：${detail}`);
    }
    const content = body?.choices?.[0]?.message?.content;
    const reply = typeof content === 'string' ? content
      : Array.isArray(content) ? content.map(part => (part as { text?: string }).text ?? '').join('\n') : '';
    const reported = body?.usage;
    const usage = reported && (typeof reported.prompt_tokens === 'number' || typeof reported.completion_tokens === 'number')
      ? { input: reported.prompt_tokens ?? 0, output: reported.completion_tokens ?? 0 } : null;
    if (!reply.trim()) {
      const empty = new AiError('bad-response', '千问没有返回内容');
      empty.usage = usage;
      throw empty;
    }
    return { reply: reply.trim(), usage };
  } finally {
    clearTimeout(timer);
    request.signal?.removeEventListener('abort', onAbort);
  }
}

/** Chat completion with retries (1 s, 2 s, ... up to 5 s apart) for network, timeout, rate-limit and server errors. */
export async function chat(request: ChatRequest, deps: Dependencies = defaults): Promise<string> {
  return (await chatWithUsage(request, deps)).reply;
}

/** Like chat, also returning the tokens used by the attempt that succeeded (null when DashScope did not say); the trial proxy counts cost with it. */
export async function chatWithUsage(request: ChatRequest, deps: Dependencies = defaults): Promise<{ reply: string; usage: Usage | null }> {
  if (!request.apiKey.trim()) throw new AiError('no-key', '还没有填写千问 API Key，请在设置里填写');
  return withRetries(() => once(request, deps), request.maxAttempts, request.signal, deps.sleep);
}

/**
 * Run ttempt until it succeeds, a non-retryable error comes, or maxAttempts is reached; waits 1 s, 2 s, ... up to 5 s
 * between attempts and stops at once when signal aborts.
 */
export async function withRetries<T>(attempt: () => Promise<T>, maxAttempts: number, signal: AbortSignal | undefined, sleep: Dependencies['sleep'] = defaults.sleep): Promise<T> {
  let delay = 1000;
  for (let tries = 1; ; tries++) {
    try {
      return await attempt();
    } catch (error) {
      const failure = error instanceof AiError ? error : new AiError('network', String(error));
      if (!failure.retryable || tries >= maxAttempts || signal?.aborted) throw failure;
      await waitOrCancel(sleep(failure.retryAfterMs ?? delay, signal), signal);
      if (signal?.aborted) throw new AiError('cancelled', '已取消');
      delay = Math.min(delay * 2, 5000);
    }
  }
}

/** Resolve when `wait` does, or as soon as `signal` aborts, so a cancel between retries takes effect at once. */
function waitOrCancel(wait: Promise<void>, signal?: AbortSignal): Promise<void> {
  if (!signal) return wait;
  if (signal.aborted) return Promise.resolve();
  return new Promise(resolve => {
    const done = () => { signal.removeEventListener('abort', done); resolve(); };
    signal.addEventListener('abort', done, { once: true });
    void wait.then(done);
  });
}
