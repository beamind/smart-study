import { AiError, withRetries } from './dashscope';
import type { ModelCall, ModelTask } from './tasks';

/**
 * The trial proxy's address in release builds (docs/trial-proxy.md, step 8). Empty until it is deployed.
 * Development (`npm run dev`), tests and Playwright never use it, so they spend nothing.
 */
const RELEASE_TRIAL_URL = '';

/**
 * The trial proxy (docs/decisions/004): without the user's own API key, model calls go through it, a fixed number
 * of times per device. Empty when this build has no trial; then a key is needed. VITE_TRIAL_URL at build time
 * overrides it, also in development; `off` builds without a trial.
 */
const override = import.meta.env.VITE_TRIAL_URL?.trim();
export const TRIAL_URL: string = override === 'off' ? '' : override || (import.meta.env.PROD ? RELEASE_TRIAL_URL : '');

/** What is left of this device's trial. */
export interface TrialRemaining { photo: number; translate: number; generate: number }

export interface TrialDependencies {
  fetch: typeof fetch;
  /** A random id kept in the app's private storage (src/services/trialDevice.ts). */
  deviceId(): Promise<string>;
  /** Wait between retries; tests pass a fake. */
  sleep?(ms: number, signal?: AbortSignal): Promise<void>;
}

type TrialBody = { reply?: string; remaining?: TrialRemaining; error?: string; message?: string };

/** One request to the proxy, with a timeout; errors are mapped like DashScope's, so retries work the same way. */
async function post(url: string, deps: TrialDependencies, task: ModelTask, timeoutMs: number, signal?: AbortSignal): Promise<string> {
  if (signal?.aborted) throw new AiError('cancelled', '已取消');
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  signal?.addEventListener('abort', onAbort, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  const stopped = () => (signal?.aborted ? new AiError('cancelled', '已取消')
    : timedOut ? new AiError('timeout', `等了 ${Math.round(timeoutMs / 1000)} 秒还没有结果`) : null);
  try {
    let response: Response;
    let text: string;
    try {
      response = await deps.fetch(url, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: await deps.deviceId(), ...task }),
      });
      text = await response.text();
    } catch {
      throw stopped() ?? new AiError('network', '网络连接失败，请检查网络');
    }
    let body: TrialBody | null = null;
    try { body = JSON.parse(text); } catch { /* not JSON */ }
    if (response.ok && typeof body?.reply === 'string' && body.reply.trim()) return body.reply;
    const message = body?.message ?? '';
    if (response.status === 429) throw new AiError('quota', message || '试用额度用完了。在设置里填写自己的千问 API Key，就可以继续使用');
    if (response.status === 400 || response.status === 413) throw new AiError('bad-request', `请求被拒绝：${message || response.status}`);
    if (response.ok) throw new AiError('bad-response', '千问没有返回内容');
    throw new AiError('server', message || `试用服务暂时出错（${response.status}）`);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

/** Model calls through the trial proxy instead of the user's own key. */
export function trialCall(url: string, deps: TrialDependencies, timeoutMs: number, maxAttempts: number): ModelCall {
  return (task, signal) => withRetries(() => post(url, deps, task, timeoutMs, signal), maxAttempts, signal, deps.sleep);
}

/** This device's remaining trial, or null when the proxy cannot be reached. */
export async function trialRemaining(url: string, deps: TrialDependencies): Promise<TrialRemaining | null> {
  try {
    const response = await deps.fetch(`${url}?device=${encodeURIComponent(await deps.deviceId())}`);
    const body = await response.json() as TrialBody;
    return response.ok && body.remaining ? body.remaining : null;
  } catch {
    return null;
  }
}
