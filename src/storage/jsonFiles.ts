import { fileStamp, parseFileStamp } from '../core/time';
import { stringifyData } from '../data/serialize';
import type { Checked } from '../data/validate';
import { splitPath, UndecodableTextError, type Folder } from './folder';

export const HISTORY_KEEP_LATEST = 10;
export const HISTORY_KEEP_DAYS = 30;

export type LoadResult<T> =
  | { status: 'missing' }
  | { status: 'ok'; value: T; recoveredFrom?: string; problem?: string; /** A damaged main file was kept as a .broken copy. */ keptBroken?: boolean }
  /** The file exists but cannot be used. It is left untouched. */
  | { status: 'unusable'; reason: 'invalid' | 'newer'; error: string };

/** "data/users/u1/error-book.json" -> "data/history/users/u1/error-book" */
export function historyDir(path: string): string {
  const relative = path.startsWith('data/') ? path.slice('data/'.length) : path;
  return `data/history/${relative.replace(/\.json$/, '')}`;
}

interface Read<T> {
  /** null when the file is missing or its bytes are not UTF-8. */
  text: string | null;
  /** null when the file is missing. */
  checked: Checked<T> | null;
  undecodable: boolean;
}

/**
 * Reads and writes JSON data files so that an interrupted write never loses data:
 * write "<file>.tmp" and read it back, add the new version to history/, then swap the files.
 * Writes are serialised. See docs/decisions/002-public-documents-storage-via-saf.md.
 */
export class JsonFiles {
  private queue: Promise<unknown> = Promise.resolve();
  /** Last history stamp used, so two saves in the same millisecond never share a history file name. */
  private lastStampMs = 0;
  /**
   * The text each file had when this instance last loaded or saved it (null: missing), or 'unusable'. A save is refused
   * when the file changed since (another browser tab, a copy over USB), so data written elsewhere is never replaced by
   * an older copy, and a file that could not be used is never overwritten.
   */
  private readonly known = new Map<string, { text: string | null } | 'unusable'>();

  constructor(readonly folder: Folder, private readonly now: () => number = Date.now) {}

  load<T>(path: string, check: (input: unknown) => Checked<T>): Promise<LoadResult<T>> {
    return this.enqueue(async () => {
      const result = await this.loadNow(path, check);
      if (result.status === 'unusable') this.known.set(path, 'unusable');
      else if (result.status === 'missing') this.known.set(path, { text: null });
      return result;
    });
  }

  save(path: string, value: unknown): Promise<void> {
    const text = stringifyData(value);
    return this.enqueue(() => this.saveNow(path, text));
  }

  /** History versions of a file, newest first. */
  async history(path: string): Promise<string[]> {
    const dir = historyDir(path);
    const names = (await this.folder.list(dir)).filter(entry => !entry.isDirectory && entry.name.endsWith('.json')).map(entry => entry.name);
    return names.sort().reverse().map(name => `${dir}/${name}`);
  }

  private enqueue<R>(task: () => Promise<R>): Promise<R> {
    const exclusive = this.folder.exclusive?.bind(this.folder);
    const run = this.queue.catch(() => undefined).then(() => (exclusive ? exclusive(task) : task()));
    this.queue = run;
    return run;
  }

  private async read<T>(path: string, check: (input: unknown) => Checked<T>): Promise<Read<T>> {
    let text: string | null;
    try { text = await this.folder.readText(path); } catch (error) {
      if (!(error instanceof UndecodableTextError)) throw error;
      return { text: null, checked: { ok: false, reason: 'invalid', error: '文件不是 UTF-8 文本，可能在电脑上用别的编码保存过' }, undecodable: true };
    }
    if (text === null) return { text, checked: null, undecodable: false };
    // Editors on a PC may add a byte-order mark; readText keeps it so the bytes can be written back unchanged.
    try { return { text, checked: check(JSON.parse(text.replace(/^\uFEFF/, ''))), undecodable: false }; } catch (error) {
      return { text, checked: { ok: false, reason: 'invalid', error: `JSON 无法解析：${(error as Error).message}` }, undecodable: false };
    }
  }

  private async loadNow<T>(path: string, check: (input: unknown) => Checked<T>): Promise<LoadResult<T>> {
    const main = await this.read(path, check);
    if (main.checked?.ok) { this.known.set(path, { text: main.text }); return { status: 'ok', value: main.checked.value }; }
    if (main.checked?.reason === 'newer') return { status: 'unusable', reason: 'newer', error: main.checked.error };

    // The main file is missing or broken. Use a complete temp file left by an interrupted save, or the
    // newest valid history version, and make that the main file again, so the recovery happens once.
    // A broken main file is kept first: that copy is not a history version, so pruning never deletes it,
    // and it may be the only copy of a hand edit worth repairing. Text is copied to
    // history/<file>/<stamp>.broken; a file that is not UTF-8 is renamed in place to
    // <file>.<stamp>.broken, so its bytes stay exactly as they were.
    const { name } = splitPath(path);
    const tempPath = `${path}.tmp`;
    const restore = async (text: string, fromTemp: boolean) => {
      if (main.undecodable) {
        await this.folder.rename(path, `${name}.${await this.nextStamp(path)}.broken`);
      } else if (main.text !== null) {
        await this.folder.writeText(`${historyDir(path)}/${await this.nextStamp(path)}.broken`, main.text);
        await this.folder.remove(path);
      }
      if (!fromTemp) {
        await this.folder.writeText(tempPath, text);
        if (await this.folder.readText(tempPath) !== text) throw new Error(`写入校验失败：${tempPath}`);
      }
      await this.folder.rename(tempPath, name);
      this.known.set(path, { text });
    };

    const temp = await this.read(tempPath, check);
    const keptBroken = main.undecodable || main.text !== null;
    // Data from a newer app version is never replaced by an older copy.
    if (temp.checked?.ok === false && temp.checked.reason === 'newer') return { status: 'unusable', reason: 'newer', error: temp.checked.error };
    if (temp.checked?.ok) {
      await restore(temp.text!, true);
      return { status: 'ok', value: temp.checked.value, recoveredFrom: tempPath, keptBroken, problem: main.checked ? main.checked.error : '上次保存被中断，已用临时文件恢复' };
    }

    for (const version of await this.history(path)) {
      const old = await this.read(version, check);
      if (old.checked?.ok === false && old.checked.reason === 'newer') return { status: 'unusable', reason: 'newer', error: old.checked.error };
      if (old.checked?.ok) {
        if (temp.checked) await this.folder.remove(tempPath); // an unusable temp file would block the rename
        await restore(old.text!, false);
        return { status: 'ok', value: old.checked.value, recoveredFrom: version, keptBroken, problem: main.checked ? main.checked.error : '数据文件缺失' };
      }
    }
    return main.checked ? { status: 'unusable', reason: 'invalid', error: main.checked.error } : { status: 'missing' };
  }

  /**
   * A stamp for a new history name of this file: later than every name already there, so the names
   * sort in save order even after the device clock went back, and an existing file is never overwritten.
   */
  private async nextStamp(path: string): Promise<string> {
    let newest = 0;
    for (const entry of await this.folder.list(historyDir(path))) newest = Math.max(newest, parseFileStamp(entry.name) ?? 0);
    this.lastStampMs = Math.max(this.now(), this.lastStampMs + 1, newest + 1);
    return fileStamp(this.lastStampMs);
  }

  private async saveNow(path: string, text: string) {
    const { name } = splitPath(path);
    const tempPath = `${path}.tmp`;
    const previous = await this.folder.readText(path);
    const known = this.known.get(path);
    if (known === 'unusable') throw new Error(`${path} 无法使用，没有保存`);
    if (known && previous !== known.text) throw new Error(`${path} 在别处被改过（例如另一个浏览器窗口，或从电脑拷了进来），没有保存。请重新打开 App 再试`);
    if (previous === text) { this.known.set(path, { text }); return; }
    await this.folder.writeText(tempPath, text);
    if (await this.folder.readText(tempPath) !== text) throw new Error(`写入校验失败：${tempPath}`);

    // History holds every saved version, the one being saved included, so if the main file is damaged later
    // (for example edited on a PC) the newest history version is the last save, not the one before it.
    // The current main file is added first when history does not have it yet: files from before this rule,
    // or a file restored from a temp file.
    if (previous !== null && !(await this.isNewestHistory(path, previous))) {
      await this.folder.writeText(`${historyDir(path)}/${await this.nextStamp(path)}.json`, previous);
    }
    await this.folder.writeText(`${historyDir(path)}/${await this.nextStamp(path)}.json`, text);
    if (previous !== null) await this.folder.remove(path);
    try {
      await this.folder.rename(tempPath, name);
    } catch (error) {
      // The main file is gone and the temp file holds this save, as after a crash: the next load restores it.
      // Within this run the file is now missing, so the next save is not refused as "changed elsewhere".
      this.known.set(path, { text: null });
      throw error;
    }
    this.known.set(path, { text });
    // The new data is in place now; failing to delete old history versions must not report the save as failed.
    await this.prune(path).catch(() => undefined);
  }

  private async isNewestHistory(path: string, text: string): Promise<boolean> {
    const newest = (await this.history(path))[0];
    if (!newest) return false;
    try { return await this.folder.readText(newest) === text; } catch { return false; }
  }

  /**
   * Keep the newest HISTORY_KEEP_LATEST versions plus the last version of each of the HISTORY_KEEP_DAYS most recent
   * days that have a version (UTC days, as in the file names). The days come from the names, not from the clock,
   * so a device clock set far ahead for one save does not make every older day look expired.
   */
  private async prune(path: string) {
    const versions = await this.history(path);
    const keep = new Set(versions.slice(0, HISTORY_KEEP_LATEST));
    const seenDays = new Set<string>();
    for (const version of versions) {
      const day = splitPath(version).name.slice(0, 8);
      if (seenDays.has(day) || seenDays.size >= HISTORY_KEEP_DAYS) continue;
      seenDays.add(day);
      keep.add(version);
    }
    for (const version of versions) if (!keep.has(version)) await this.folder.remove(version);
  }
}
