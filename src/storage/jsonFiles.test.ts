import { describe, expect, it } from 'vitest';
import { checkAppFile } from '../data/validate';
import { SCHEMAS, type AppFile } from '../data/schema';
import { MemoryFolder } from './memoryFolder';
import { HISTORY_KEEP_LATEST, historyDir, JsonFiles } from './jsonFiles';

const path = 'data/app.json';
const app = (name: string): AppFile => ({
  schema: SCHEMAS.app,
  users: [{ id: 'u1', name, createdAt: '2026-01-01T08:00:00+08:00' }],
  currentUserId: 'u1',
});

function setup(start = Date.parse('2026-10-31T12:00:00+08:00')) {
  let clock = start;
  const folder = new MemoryFolder();
  const files = new JsonFiles(folder, () => clock);
  return { folder, files, tick: (ms = 1000) => { clock += ms; } };
}

describe('JsonFiles', () => {
  it('reports a missing file', async () => {
    const { files } = setup();
    expect(await files.load(path, checkAppFile)).toEqual({ status: 'missing' });
  });

  it('saves, loads and keeps the previous version in history', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    const loaded = await files.load(path, checkAppFile);
    expect(loaded).toMatchObject({ status: 'ok', value: { users: [{ name: '小红' }] } });
    const history = await files.history(path);
    expect(history).toHaveLength(1);
    expect(JSON.parse(folder.files.get(history[0])!).users[0].name).toBe('小明');
    expect(folder.files.has(`${path}.tmp`)).toBe(false);
  });

  it('does not create a history version when nothing changed', async () => {
    const { files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小明'));
    expect(await files.history(path)).toHaveLength(0);
  });

  it('recovers from an interrupted save that left only the temp file', async () => {
    const { folder, files } = setup();
    folder.files.set(`${path}.tmp`, JSON.stringify(app('小明')));
    const loaded = await files.load(path, checkAppFile);
    expect(loaded).toMatchObject({ status: 'ok', recoveredFrom: `${path}.tmp` });
    expect(folder.files.has(path)).toBe(true);
    expect(folder.files.has(`${path}.tmp`)).toBe(false);
  });

  it('recovers from the newest valid history version once, keeping the broken file as a .broken copy', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    const broken = '{"schema": "smart-study.app/1", "users": [';
    folder.files.set(path, broken);
    const loaded = await files.load(path, checkAppFile);
    expect(loaded).toMatchObject({ status: 'ok', value: { users: [{ name: '小明' }] } });
    expect(loaded.status === 'ok' && loaded.problem).toContain('JSON 无法解析');
    expect(JSON.parse(folder.files.get(path)!).users[0].name).toBe('小明');
    expect([...folder.files].filter(([name]) => name.endsWith('.broken')).map(([, text]) => text)).toEqual([broken]);
    // The next load is normal: no second copy, no repeated notice.
    expect(await files.load(path, checkAppFile)).toEqual({ status: 'ok', value: expect.anything() });
    expect([...folder.files.keys()].filter(name => name.endsWith('.broken'))).toHaveLength(1);
  });

  it('never replaces a newer temp file or history version with an older copy', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    folder.files.delete(path);
    const newer = JSON.stringify({ ...app('新版'), schema: 'smart-study.app/2' });
    folder.files.set(`${path}.tmp`, newer);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'unusable', reason: 'newer' });
    expect(folder.files.get(`${path}.tmp`)).toBe(newer);
    expect(folder.files.has(path)).toBe(false);
  });

  it('never restores an older history version past a newer one', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    const newerPath = `${historyDir(path)}/29991231-000000-000.json`;
    const newer = JSON.stringify({ ...app('新版'), schema: 'smart-study.app/2' });
    folder.files.set(newerPath, newer);
    const broken = '{"schema": "smart-study.app/1", "users": [';
    folder.files.set(path, broken);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'unusable', reason: 'newer' });
    expect(folder.files.get(path)).toBe(broken);
    expect(folder.files.get(newerPath)).toBe(newer);
    expect([...folder.files.keys()].some(name => name.endsWith('.broken'))).toBe(false);
  });

  it('keeps a copy of the broken file that later saves and pruning never delete', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    tick();
    const broken = '{"schema": "smart-study.app/1", "users": [ hand edit';
    folder.files.set(path, broken);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'ok' });
    for (let i = 0; i < HISTORY_KEEP_LATEST + 5; i++) { tick(); await files.save(path, app(`用户${i}`)); }
    const copies = [...folder.files].filter(([name]) => name.startsWith(historyDir(path)) && name.endsWith('.broken'));
    expect(copies.map(([, text]) => text)).toEqual([broken]);
    expect(await files.history(path)).toHaveLength(HISTORY_KEEP_LATEST); // .broken is not a history version
  });

  it('moves a main file that is not UTF-8 aside unchanged before recovering from history', async () => {
    const { folder, files, tick } = setup();
    await files.save(path, app('小明'));
    tick();
    await files.save(path, app('小红'));
    const undecodable = '{"schema": "smart-study.app/1", "users": [\uD800]}'; // a lone surrogate stands for non-UTF-8 bytes
    folder.files.set(path, undecodable);
    const loaded = await files.load(path, checkAppFile);
    expect(loaded).toMatchObject({ status: 'ok', value: { users: [{ name: '小明' }] }, problem: expect.stringContaining('UTF-8') });
    const aside = [...folder.files].filter(([name]) => /^data\/app\.json\.\d{8}-\d{6}-\d{3}\.broken$/.test(name));
    expect(aside.map(([, text]) => text)).toEqual([undecodable]);
    expect(await files.load(path, checkAppFile)).toEqual({ status: 'ok', value: expect.anything() });
  });

  it('leaves a main file that is not UTF-8 untouched when there is nothing to recover from', async () => {
    const { folder, files } = setup();
    const undecodable = '{"schema": "smart-study.app/1"\uDC00}';
    folder.files.set(path, undecodable);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'unusable', reason: 'invalid' });
    expect([...folder.files]).toEqual([[path, undecodable]]);
    await expect(files.save(path, app('小明'))).rejects.toThrow('UTF-8');
    expect(folder.files.get(path)).toBe(undecodable);
  });

  it('keeps history names in save order when the clock goes back', async () => {
    const { folder, files, tick } = setup();
    folder.files.set(`${historyDir(path)}/20261031-235959-999.json`, JSON.stringify(app('更早')));
    await files.save(path, app('一'));
    tick();
    await files.save(path, app('二'));
    tick(-86_400_000);
    await files.save(path, app('三'));
    const names = (await files.history(path)).map(version => JSON.parse(folder.files.get(version)!).users[0].name);
    expect(names).toEqual(['二', '一', '更早']);
  });

  it('reads files that start with a byte-order mark', async () => {
    const { folder, files } = setup();
    folder.files.set(path, `\uFEFF${JSON.stringify(app('小明'))}`);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'ok', value: { users: [{ name: '小明' }] } });
  });

  it('gives two saves in the same millisecond different history names', async () => {
    const { files } = setup();
    for (const name of ['一', '二', '三']) await files.save(path, app(name));
    expect(new Set(await files.history(path)).size).toBe(2);
  });

  it('reports the save as done when only deleting old history versions fails', async () => {
    const { folder, files, tick } = setup();
    for (let i = 0; i < HISTORY_KEEP_LATEST + 1; i++) { await files.save(path, app(`用户${i}`)); tick(); }
    const remove = folder.remove.bind(folder);
    folder.remove = async name => { if (name.startsWith(historyDir(path))) throw new Error('busy'); return remove(name); };
    await expect(files.save(path, app('最后'))).resolves.toBeUndefined();
    expect(await files.load(path, checkAppFile)).toMatchObject({ value: { users: [{ name: '最后' }] } });
  });

  it('refuses a file written by a newer app version and leaves it untouched', async () => {
    const { folder, files } = setup();
    const newer = JSON.stringify({ ...app('小明'), schema: 'smart-study.app/2' });
    folder.files.set(path, newer);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'unusable', reason: 'newer' });
    expect(folder.files.get(path)).toBe(newer);
  });

  it('keeps the newest versions plus one version per day for the last 30 days', async () => {
    const { files, tick } = setup(Date.parse('2026-09-01T12:00:00+08:00'));
    for (let day = 0; day < 40; day++) {
      for (let i = 0; i < 3; i++) {
        await files.save(path, app(`用户${day}-${i}`));
        tick(60_000);
      }
      tick(86_400_000 - 3 * 60_000);
    }
    const history = await files.history(path);
    const days = new Set(history.map(version => version.split('/').at(-1)!.slice(0, 8)));
    expect(history.length).toBeGreaterThanOrEqual(HISTORY_KEEP_LATEST);
    expect(history.length).toBeLessThan(HISTORY_KEEP_LATEST + 31);
    expect(days.size).toBeGreaterThanOrEqual(30);
    expect(history.every(version => version.startsWith(historyDir(path)))).toBe(true);
  });

  it('keeps the daily versions when one save happens with the clock set far ahead', async () => {
    const { files, tick } = setup(Date.parse('2026-09-01T12:00:00+08:00'));
    for (let day = 0; day < 35; day++) { await files.save(path, app(`用户${day}`)); tick(86_400_000); }
    tick(365 * 86_400_000);
    await files.save(path, app('未来'));
    const days = new Set((await files.history(path)).map(version => version.split('/').at(-1)!.slice(0, 8)));
    expect(days.size).toBe(30);
  });

  it('refuses to save over a file that changed since it was loaded, or that could not be used', async () => {
    const { folder, files } = setup();
    await files.save(path, app('小明'));
    const other = new JsonFiles(folder);
    expect(await other.load(path, checkAppFile)).toMatchObject({ status: 'ok' });
    await files.save(path, app('小红'));
    await expect(other.save(path, app('旧窗口'))).rejects.toThrow('在别处被改过');
    expect(JSON.parse(folder.files.get(path)!).users[0].name).toBe('小红');

    const newer = JSON.stringify({ ...app('新版'), schema: 'smart-study.app/2' });
    folder.files.set('data/settings.json', newer);
    expect(await files.load('data/settings.json', checkAppFile)).toMatchObject({ status: 'unusable' });
    await expect(files.save('data/settings.json', app('小明'))).rejects.toThrow('无法使用');
    expect(folder.files.get('data/settings.json')).toBe(newer);
  });

  it('runs every load and save through the folder\'s exclusive section when it has one', async () => {
    const { folder } = setup();
    let active = 0;
    let most = 0;
    const calls: string[] = [];
    (folder as MemoryFolder & { exclusive<T>(task: () => Promise<T>): Promise<T> }).exclusive = async task => {
      active++; most = Math.max(most, active); calls.push('in');
      try { return await task(); } finally { active--; }
    };
    const files = new JsonFiles(folder);
    await Promise.all([files.save(path, app('一')), files.load(path, checkAppFile), files.save(path, app('二'))]);
    expect(calls).toHaveLength(3);
    expect(most).toBe(1);
  });

  it('serialises concurrent saves so the last call wins', async () => {
    const { files } = setup();
    await Promise.all([files.save(path, app('一')), files.save(path, app('二')), files.save(path, app('三'))]);
    expect(await files.load(path, checkAppFile)).toMatchObject({ status: 'ok', value: { users: [{ name: '三' }] } });
  });
});
