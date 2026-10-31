import type { Checked } from '../data/validate';
import type { JsonFiles } from './jsonFiles';

/**
 * In-memory copy of the data files, as described in docs/decisions/002: each file is read once,
 * every change is applied to the latest in-memory value and written once, and changes to the
 * same file run one after another. When saving fails, memory keeps the previous value, so the
 * screen never shows data that is not on disk. A file that could not be read is never written.
 */
export type Entry<T> =
  | { status: 'ok'; value: T; problem?: string }
  | { status: 'unusable'; error: string };

type Listener = () => void;

export class DataCache {
  private entries = new Map<string, Promise<Entry<unknown>>>();
  private latest = new Map<string, Entry<unknown>>();
  private queues = new Map<string, Promise<unknown>>();
  private listeners = new Map<string, Set<Listener>>();

  constructor(private readonly files: JsonFiles) {}

  get<T>(path: string, check: (input: unknown) => Checked<T>, empty: () => T): Promise<Entry<T>> {
    let entry = this.entries.get(path) as Promise<Entry<T>> | undefined;
    if (!entry) {
      entry = this.files.load(path, check).then((result): Entry<T> => {
        if (result.status === 'missing') return { status: 'ok', value: empty() };
        if (result.status === 'unusable') {
          return { status: 'unusable', error: result.reason === 'newer' ? result.error : `数据文件无法读取，已停止写入以免覆盖：${result.error}` };
        }
        return { status: 'ok', value: result.value, ...(result.problem ? { problem: `已从历史版本恢复：${result.problem}` } : {}) };
      }, (error): Entry<T> => {
        // Reading itself failed (e.g. the folder permission was lost). Show it, and read again next time.
        this.entries.delete(path);
        return { status: 'unusable', error: `读取数据文件失败：${(error as Error)?.message ?? String(error)}` };
      });
      const loading = entry;
      void entry.then(value => { if (this.entries.get(path) === loading || value.status === 'unusable') this.latest.set(path, value); });
      this.entries.set(path, entry);
    }
    return entry;
  }

  /** Synchronous peek at a loaded file (undefined while loading). */
  peek<T>(path: string): Entry<T> | undefined {
    return this.latest.get(path) as Entry<T> | undefined;
  }

  /** Apply a change and save it. */
  set<T>(path: string, check: (input: unknown) => Checked<T>, empty: () => T, change: (value: T) => T): Promise<void> {
    return this.update(path, check, empty, value => ({ value: change(value), result: undefined }));
  }

  /** Apply a change that also produces a result (e.g. the new session id), save, and return the result. */
  update<T, R>(path: string, check: (input: unknown) => Checked<T>, empty: () => T, change: (value: T) => { value: T; result: R }): Promise<R> {
    const run = (this.queues.get(path) ?? Promise.resolve()).catch(() => undefined).then(async () => {
      const entry = await this.get(path, check, empty);
      if (entry.status !== 'ok') throw new Error(entry.error);
      const { value: changed, result } = change(entry.value);
      // Save the checked value: a change that would not load again is never written, and the
      // field order matches what loading produces, so undoing a change restores the same bytes.
      const checked = check(changed);
      if (!checked.ok) throw new Error(`修改后的数据没有通过校验，未保存：${checked.error}`);
      const next = checked.value;
      await this.files.save(path, next);
      const saved: Entry<T> = { status: 'ok', value: next };
      this.entries.set(path, Promise.resolve(saved));
      this.latest.set(path, saved);
      this.listeners.get(path)?.forEach(listener => listener());
      return result;
    });
    this.queues.set(path, run);
    return run;
  }

  subscribe(path: string, listener: Listener): () => void {
    if (!this.listeners.has(path)) this.listeners.set(path, new Set());
    this.listeners.get(path)!.add(listener);
    return () => { this.listeners.get(path)?.delete(listener); };
  }
}
