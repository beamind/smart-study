import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { assertSafeName, assertSafePath, decodeUtf8, type Folder, type FolderEntry } from '../src/storage/folder';

/** A data folder on the computer, for command-line tools. Same contract as the app's folders. */
export class NodeFolder implements Folder {
  constructor(private readonly root: string) {}

  get label() { return this.root; }

  private full(relative: string) {
    assertSafePath(relative);
    return path.join(this.root, ...relative.split('/'));
  }

  async readText(relative: string) {
    const file = this.full(relative);
    return existsSync(file) ? decodeUtf8(relative, readFileSync(file)) : null;
  }

  async writeText(relative: string, text: string) {
    const file = this.full(relative);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, text, 'utf8');
  }

  async list(dir: string): Promise<FolderEntry[]> {
    const full = dir ? this.full(dir) : this.root;
    if (!existsSync(full)) return [];
    return readdirSync(full, { withFileTypes: true }).map(entry => ({ name: entry.name, isDirectory: entry.isDirectory() }));
  }

  async remove(relative: string) {
    rmSync(this.full(relative), { force: true });
  }

  async rename(relative: string, newName: string) {
    assertSafeName(newName);
    const from = this.full(relative);
    const to = path.join(path.dirname(from), newName);
    if (existsSync(to)) throw new Error(`目标已存在：${to}`);
    renameSync(from, to);
  }
}
