import { describe, expect, it } from 'vitest';
import { WordReader, type SpeechEngine } from './tts';

function fakeEngine() {
  const log: string[] = [];
  let finish: (() => void) | null = null;
  let fail: ((error: Error) => void) | null = null;
  const engine: SpeechEngine = {
    prepare: async () => undefined,
    speak: text => new Promise((resolve, reject) => { log.push(`speak ${text}`); finish = resolve; fail = reject; }),
    stop: async () => { log.push('stop'); fail?.(new Error('interrupted')); finish = fail = null; },
    check: async () => null,
  };
  return { engine, log, end: () => { const done = finish; finish = fail = null; done?.(); } };
}
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const options = { rate: 0.5, pitch: 1, gapMs: 1000 };

describe('WordReader', () => {
  it('reads twice with the gap in between', async () => {
    const { engine, log, end } = fakeEngine();
    const gaps: number[] = [];
    const reader = new WordReader(engine, async ms => { gaps.push(ms); });
    const done = reader.read('春天', 'zh-CN', options);
    await tick(); end(); await tick(); await tick(); end();
    expect(await done).toBe(true);
    expect(log).toEqual(['stop', 'speak 春天', 'speak 春天']);
    expect(gaps).toEqual([1000]);
  });

  it('a new word cancels the previous reading', async () => {
    const { engine, log, end } = fakeEngine();
    const reader = new WordReader(engine, async () => undefined);
    const first = reader.read('春天', 'zh-CN', options);
    await tick();
    const second = reader.read('夏天', 'zh-CN', options);
    expect(await first).toBe(false);
    await tick(); end(); await tick(); await tick(); end();
    expect(await second).toBe(true);
    expect(log.filter(line => line.startsWith('speak'))).toEqual(['speak 春天', 'speak 夏天', 'speak 夏天']);
  });

  it('cancel stops speaking and does not start the second reading', async () => {
    const { engine, log } = fakeEngine();
    const reader = new WordReader(engine, async () => undefined);
    const reading = reader.read('春天', 'zh-CN', options);
    await tick();
    await reader.cancel();
    expect(await reading).toBe(false);
    expect(log.filter(line => line.startsWith('speak'))).toEqual(['speak 春天']);
  });

  it('an older call whose preparation is slower never speaks after a newer one', async () => {
    const { engine, log, end } = fakeEngine();
    let release: (() => void) | null = null;
    let slow = true;
    engine.prepare = () => (slow ? (slow = false, new Promise<void>(resolve => { release = resolve; })) : Promise.resolve());
    const reader = new WordReader(engine, async () => undefined);
    const first = reader.read('春天', 'zh-CN', { ...options, times: 1 });
    await tick();
    const second = reader.read('春天', 'zh-CN', { ...options, times: 1 });
    await tick();
    release!();
    await tick(); await tick();
    end();
    expect(await first).toBe(false);
    expect(await second).toBe(true);
    expect(log.filter(line => line.startsWith('speak'))).toEqual(['speak 春天']);
  });

  it('retries once when the engine reports an interruption it did not get from us, then gives up with an error', async () => {
    let calls = 0;
    const reader = new WordReader({
      prepare: async () => undefined, stop: async () => undefined, check: async () => null,
      speak: async text => { calls++; if (text === 'a' && calls === 1) throw Object.assign(new Error('x'), { interrupted: true }); if (text === 'b') throw Object.assign(new Error('b'), { interrupted: true }); },
    }, async () => undefined);
    expect(await reader.read('a', 'zh-CN', options)).toBe(true);
    expect(calls).toBe(3);
    await expect(reader.read('b', 'zh-CN', options)).rejects.toThrow('b');
  });
});
