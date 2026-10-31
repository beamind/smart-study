/**
 * A user-authorised folder (Documents/smartstudy on Android, any chosen folder on a PC).
 * Paths are relative, use "/" and never start with "/". Implementations:
 * - native: SAF plugin (android/.../SafFolderPlugin.java)
 * - web: File System Access API
 * - memory: tests
 */
export interface FolderEntry {
  name: string;
  isDirectory: boolean;
}

export interface Folder {
  /** Shown to the user, e.g. "Documents/smartstudy". */
  readonly label: string;
  /**
   * Returns null when the file does not exist. Decodes strictly as UTF-8 and keeps a leading
   * byte-order mark, so writing the text back gives the same bytes. Throws UndecodableTextError
   * when the bytes are not UTF-8 (e.g. a file saved as GBK on a PC).
   */
  readText(path: string): Promise<string | null>;
  /** Creates missing parent directories and replaces any existing content. */
  writeText(path: string, text: string): Promise<void>;
  /** Returns an empty list when the directory does not exist. */
  list(dir: string): Promise<FolderEntry[]>;
  /** Does nothing when the file does not exist. */
  remove(path: string): Promise<void>;
  /** Renames a file inside its own directory, keeping its bytes. The new name must not exist yet. */
  rename(path: string, newName: string): Promise<void>;
  /**
   * Runs one load or save so that no other copy of the app (another browser tab on the same folder) runs one at the
   * same time. Optional: where only one copy of the app can use the folder, tasks run directly.
   */
  exclusive?<T>(task: () => Promise<T>): Promise<T>;
}

export class UndecodableTextError extends Error {
  constructor(readonly path: string) {
    super(`文件不是 UTF-8 文本：${path}`);
    this.name = 'UndecodableTextError';
  }
}

/** Strict UTF-8 decoding for readText. */
export function decodeUtf8(path: string, bytes: ArrayBuffer | Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    throw new UndecodableTextError(path);
  }
}

export function splitPath(path: string): { dir: string; name: string } {
  const index = path.lastIndexOf('/');
  return index < 0 ? { dir: '', name: path } : { dir: path.slice(0, index), name: path.slice(index + 1) };
}

export function assertSafePath(path: string) {
  // "\" and ":" are separators or drive letters on Windows (tools/nodeFolder.ts), so they could leave the folder.
  if (!path || path.startsWith('/') || /[\\:]/.test(path) || path.split('/').some(part => !part || part === '.' || part === '..')) {
    throw new Error(`不允许的路径：${path}`);
  }
}

/** A name for a single path segment (a new file name in rename). */
export function assertSafeName(name: string) {
  if (!name || name === '.' || name === '..' || /[\\/:]/.test(name)) throw new Error(`不允许的文件名：${name}`);
}
