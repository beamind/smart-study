import { describe, expect, it } from 'vitest';
import { createAi } from './tasks';
import { trialCall, trialRemaining, type TrialDependencies } from './trial';

const URL = 'https://trial.example/';
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const remaining = { photo: 19, translate: 100, generate: 10 };

function fake(responses: Array<Response | Error>) {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const deps: TrialDependencies = {
    fetch: async (url, init) => {
      requests.push({ url: String(url), init });
      const next = responses.shift()!;
      if (next instanceof Error) throw next;
      return next;
    },
    deviceId: async () => 'device-1234',
    sleep: async () => undefined,
  };
  return { deps, requests };
}

describe('trialCall', () => {
  it('sends the task with the device id and returns the reply', async () => {
    const { deps, requests } = fake([reply(200, { reply: '["春天","温暖"]', remaining })]);
    const words = await createAi(trialCall(URL, deps, 1000, 3)).recognizeWords('data:image/jpeg;base64,AAAA', 'chinese');
    expect(words).toEqual(['春天', '温暖']);
    expect(requests).toHaveLength(1);
    expect(requests[0].url).toBe(URL);
    expect(requests[0].init?.method).toBe('POST');
    expect(JSON.parse(String(requests[0].init?.body))).toEqual({ device: 'device-1234', task: 'words', image: 'data:image/jpeg;base64,AAAA', language: 'chinese' });
  });

  it('reports a used-up allowance as quota with the server message, without retrying', async () => {
    const { deps, requests } = fake([reply(429, { error: 'quota', message: '这台设备的翻译试用次数用完了' })]);
    await expect(trialCall(URL, deps, 1000, 3)({ task: 'translate', text: 'apple' })).rejects.toMatchObject({ kind: 'quota', message: '这台设备的翻译试用次数用完了' });
    expect(requests).toHaveLength(1);
  });

  it('does not retry a refused request', async () => {
    const { deps, requests } = fake([reply(400, { error: 'bad-request', message: '照片格式不对' }), reply(413, { error: 'too-large', message: '请求太大' })]);
    const call = trialCall(URL, deps, 1000, 3);
    await expect(call({ task: 'questions', image: 'x' })).rejects.toMatchObject({ kind: 'bad-request' });
    await expect(call({ task: 'questions', image: 'x' })).rejects.toMatchObject({ kind: 'bad-request' });
    expect(requests).toHaveLength(2);
  });

  it('retries server and network errors, then succeeds', async () => {
    const { deps, requests } = fake([reply(502, { error: 'upstream', message: '千问暂时没有返回结果' }), new TypeError('offline'), reply(200, { reply: '苹果', remaining })]);
    await expect(trialCall(URL, deps, 1000, 3)({ task: 'translate', text: 'apple' })).resolves.toBe('苹果');
    expect(requests).toHaveLength(3);
  });

  it('passes an empty reply on, so the parsers give their own message, and treats a non-JSON page as an error', async () => {
    const { deps } = fake([reply(200, { reply: '', remaining }), new Response('<html>blocked</html>', { status: 503 })]);
    await expect(trialCall(URL, deps, 1000, 1)({ task: 'translate', text: 'apple' })).resolves.toBe('');
    await expect(trialCall(URL, deps, 1000, 1)({ task: 'translate', text: 'apple' })).rejects.toMatchObject({ kind: 'rate' });
  });

  it('retries a platform 429 or a busy proxy, but not a used-up allowance or a request the proxy cannot do', async () => {
    const { deps, requests } = fake([
      new Response('{"ErrorCode":"ResourceThrottled"}', { status: 429 }), reply(503, { error: 'busy', message: '试用的人太多，请稍后再试' }), reply(200, { reply: '苹果' }),
      reply(502, { error: 'upstream-fatal', message: '试用服务暂时不能处理这个请求' }),
    ]);
    await expect(trialCall(URL, deps, 1000, 3)({ task: 'translate', text: 'apple' })).resolves.toBe('苹果');
    expect(requests).toHaveLength(3);
    await expect(trialCall(URL, deps, 1000, 3)({ task: 'translate', text: 'apple' })).rejects.toMatchObject({ kind: 'bad-response', message: '试用服务暂时不能处理这个请求' });
    expect(requests).toHaveLength(4);
  });

  it('sends nothing when already cancelled', async () => {
    const { deps, requests } = fake([]);
    const controller = new AbortController();
    controller.abort();
    await expect(trialCall(URL, deps, 1000, 3)({ task: 'translate', text: 'apple' }, controller.signal)).rejects.toMatchObject({ kind: 'cancelled' });
    expect(requests).toHaveLength(0);
  });
});

describe('trialRemaining', () => {
  it('asks with the device id and returns what is left', async () => {
    const { deps, requests } = fake([reply(200, { remaining })]);
    await expect(trialRemaining(URL, deps)).resolves.toEqual(remaining);
    expect(requests[0].url).toBe(`${URL}?device=device-1234`);
  });

  it('returns null when the proxy cannot be reached or answers with an error', async () => {
    expect(await trialRemaining(URL, fake([new TypeError('offline')]).deps)).toBeNull();
    expect(await trialRemaining(URL, fake([reply(400, { error: 'bad-request', message: '设备标识不对' })]).deps)).toBeNull();
    expect(await trialRemaining(URL, fake([new Response('not json', { status: 200 })]).deps)).toBeNull();
  });
});
