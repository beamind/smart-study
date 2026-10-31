import { assertSafeName, assertSafePath, splitPath, UndecodableTextError, type Folder, type FolderEntry } from './folder';

/** In-memory folder for tests and for trying the app without choosing a folder. */
export class MemoryFolder implements Folder {
  readonly label: string;
  readonly files = new Map<string, string>();

  constructor(label = '内存（不会保存）', initial: Record<string, string> = {}) {
    this.label = label;
    for (const [path, text] of Object.entries(initial)) this.files.set(path, text);
  }

  /** A stored string with a lone surrogate stands for a file whose bytes are not UTF-8. */
  async readText(path: string) {
    assertSafePath(path);
    const text = this.files.get(path) ?? null;
    if (text !== null && /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(text)) throw new UndecodableTextError(path);
    return text;
  }

  async writeText(path: string, text: string) {
    assertSafePath(path);
    this.files.set(path, text);
  }

  async list(dir: string): Promise<FolderEntry[]> {
    const prefix = dir ? `${dir}/` : '';
    const entries = new Map<string, boolean>();
    for (const path of this.files.keys()) {
      if (!path.startsWith(prefix)) continue;
      const [name, ...rest] = path.slice(prefix.length).split('/');
      entries.set(name, (entries.get(name) ?? false) || rest.length > 0);
    }
    return [...entries].map(([name, isDirectory]) => ({ name, isDirectory }));
  }

  async remove(path: string) {
    assertSafePath(path);
    this.files.delete(path);
  }

  async rename(path: string, newName: string) {
    assertSafePath(path);
    assertSafeName(newName);
    const { dir } = splitPath(path);
    const target = dir ? `${dir}/${newName}` : newName;
    const text = this.files.get(path);
    if (text === undefined) throw new Error(`文件不存在：${path}`);
    if (this.files.has(target)) throw new Error(`目标已存在：${target}`);
    this.files.delete(path);
    this.files.set(target, text);
  }
}
