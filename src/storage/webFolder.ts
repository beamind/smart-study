import { assertSafeName, assertSafePath, decodeUtf8, splitPath, type Folder, type FolderEntry } from './folder';

/**
 * Folder on a PC through the File System Access API (Chrome / Edge). The chosen directory
 * handle is kept in IndexedDB so the same folder opens again after a reload; the browser
 * may ask once more for permission.
 */

declare global {
  interface Window {
    showDirectoryPicker?: (options?: { id?: string; mode?: 'read' | 'readwrite'; startIn?: string }) => Promise<FileSystemDirectoryHandle>;
  }
  interface FileSystemHandle {
    queryPermission?(descriptor: { mode: 'readwrite' }): Promise<PermissionState>;
    requestPermission?(descriptor: { mode: 'readwrite' }): Promise<PermissionState>;
  }
  interface FileSystemDirectoryHandle {
    entries(): AsyncIterableIterator<[string, FileSystemHandle]>;
  }
}

export const webFolderSupported = () => typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function';

export class WebFolder implements Folder {
  readonly label: string;

  constructor(private readonly root: FileSystemDirectoryHandle) {
    this.label = root.name;
  }

  private async directory(dir: string, create: boolean): Promise<FileSystemDirectoryHandle | null> {
    let current = this.root;
    for (const part of dir ? dir.split('/') : []) {
      try { current = await current.getDirectoryHandle(part, { create }); } catch (error) {
        if ((error as DOMException).name !== 'NotFoundError') throw error;
        // Creating fails this way only when the chosen folder itself is gone (moved, renamed or deleted).
        // Reading must not mistake that for a missing file either, or the data would look empty.
        if (create || !await webFolderExists(this.root)) throw new Error(`数据文件夹 ${this.label} 不存在了，请重新选择`);
        return null;
      }
    }
    return current;
  }

  private async file(path: string, create: boolean): Promise<FileSystemFileHandle | null> {
    assertSafePath(path);
    const { dir, name } = splitPath(path);
    const parent = await this.directory(dir, create);
    if (!parent) return null;
    try { return await parent.getFileHandle(name, { create }); } catch (error) {
      if ((error as DOMException).name === 'NotFoundError') return null;
      throw error;
    }
  }

  async readText(path: string) {
    const handle = await this.file(path, false);
    return handle ? decodeUtf8(path, await (await handle.getFile()).arrayBuffer()) : null;
  }

  async writeText(path: string, text: string) {
    await this.writeData(path, text);
  }

  private async writeData(path: string, data: string | Blob) {
    const handle = (await this.file(path, true))!;
    const writable = await handle.createWritable();
    try {
      await writable.write(data);
    } catch (error) {
      await writable.abort().catch(() => undefined); // drop the browser's swap file; the old content stays
      throw error;
    }
    await writable.close();
  }

  /**
   * Two tabs on the same folder would otherwise both write "<file>.tmp" at once, and one tab's change could be
   * renamed into place as the other's. Web Locks are shared by all tabs of this site.
   */
  exclusive<T>(task: () => Promise<T>): Promise<T> {
    return navigator.locks ? navigator.locks.request('smart-study-data-folder', task) as Promise<T> : task();
  }

  async list(dir: string): Promise<FolderEntry[]> {
    if (dir) assertSafePath(dir);
    const handle = await this.directory(dir, false);
    if (!handle) return [];
    const entries: FolderEntry[] = [];
    for await (const [name, child] of handle.entries()) entries.push({ name, isDirectory: child.kind === 'directory' });
    return entries;
  }

  async remove(path: string) {
    assertSafePath(path);
    const { dir, name } = splitPath(path);
    const parent = await this.directory(dir, false);
    try { await parent?.removeEntry(name); } catch (error) {
      if ((error as DOMException).name !== 'NotFoundError') throw error;
    }
  }

  async rename(path: string, newName: string) {
    assertSafeName(newName);
    const { dir } = splitPath(path);
    const target = dir ? `${dir}/${newName}` : newName;
    if (await this.file(target, false)) throw new Error(`目标已存在：${target}`);
    const source = await this.file(path, false);
    if (!source) throw new Error(`文件不存在：${path}`);
    // Copy the bytes, not decoded text, so a file that is not UTF-8 can be moved aside unchanged.
    await this.writeData(target, await source.getFile());
    await this.remove(path);
  }
}

// ---------- remembering the chosen folder ----------

const DB = 'smart-study';
const STORE = 'handles';
const KEY = 'dataFolder';

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await database();
  return new Promise((resolve, reject) => {
    const request = action(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export const rememberedWebFolder = () => withStore<FileSystemDirectoryHandle | undefined>('readonly', store => store.get(KEY));
export const rememberWebFolder = (handle: FileSystemDirectoryHandle) => withStore('readwrite', store => store.put(handle, KEY));
export const forgetWebFolder = () => withStore('readwrite', store => store.delete(KEY));

/** False when the remembered folder was moved, renamed or deleted (needs read permission). */
export async function webFolderExists(handle: FileSystemDirectoryHandle): Promise<boolean> {
  try {
    await handle.entries().next();
    return true;
  } catch (error) {
    if ((error as DOMException).name === 'NotFoundError') return false;
    throw error;
  }
}

export async function pickWebFolder(): Promise<FileSystemDirectoryHandle> {
  const handle = await window.showDirectoryPicker!({ id: 'smart-study-data', mode: 'readwrite', startIn: 'documents' });
  await rememberWebFolder(handle);
  return handle;
}

/** 'granted' when usable now; 'prompt' needs a click (requestPermission must run in a user gesture). */
export async function webFolderPermission(handle: FileSystemDirectoryHandle, request = false): Promise<PermissionState> {
  const descriptor = { mode: 'readwrite' as const };
  const state = await handle.queryPermission?.(descriptor) ?? 'granted';
  if (state === 'granted' || !request) return state;
  return await handle.requestPermission?.(descriptor) ?? 'denied';
}
