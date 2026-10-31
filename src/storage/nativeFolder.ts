import { registerPlugin } from '@capacitor/core';
import { assertSafeName, assertSafePath, UndecodableTextError, type Folder, type FolderEntry } from './folder';

export interface FolderStatus {
  configured: boolean;
  /** The persisted SAF grant is still valid and the folder still exists. */
  granted?: boolean;
  label?: string;
  /** Names the configured folder; every file call passes it, so calls for a folder that was replaced are refused. */
  folderId?: string;
}

interface SafFolderPlugin {
  getFolder(): Promise<FolderStatus>;
  pickFolder(): Promise<FolderStatus>;
  readText(options: { folderId: string; path: string }): Promise<{ text: string | null }>;
  writeText(options: { folderId: string; path: string; text: string }): Promise<void>;
  list(options: { folderId: string; path: string }): Promise<{ entries: FolderEntry[] }>;
  remove(options: { folderId: string; path: string }): Promise<void>;
  rename(options: { folderId: string; path: string; newName: string }): Promise<void>;
}

/** Native plugin: android/app/src/main/java/com/smartstudy/SafFolderPlugin.java */
export const SafFolder = registerPlugin<SafFolderPlugin>('SafFolder');

export class NativeFolder implements Folder {
  constructor(readonly label: string, private readonly folderId: string) {}

  async readText(path: string) {
    assertSafePath(path);
    try {
      return (await SafFolder.readText({ folderId: this.folderId, path })).text;
    } catch (error) {
      if ((error as { code?: string }).code === 'NOT_UTF8') throw new UndecodableTextError(path);
      throw error;
    }
  }

  async writeText(path: string, text: string) {
    assertSafePath(path);
    await SafFolder.writeText({ folderId: this.folderId, path, text });
  }

  async list(dir: string) {
    if (dir) assertSafePath(dir);
    return (await SafFolder.list({ folderId: this.folderId, path: dir })).entries;
  }

  async remove(path: string) {
    assertSafePath(path);
    await SafFolder.remove({ folderId: this.folderId, path });
  }

  async rename(path: string, newName: string) {
    assertSafePath(path);
    assertSafeName(newName);
    await SafFolder.rename({ folderId: this.folderId, path, newName });
  }
}
