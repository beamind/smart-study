import { Capacitor } from '@capacitor/core';
import { emptyAppFile, defaultSettings } from '../data/defaults';
import { paths, type AppFile, type Settings } from '../data/schema';
import { checkAppFile, checkSettings } from '../data/validate';
import type { Folder } from '../storage/folder';
import { JsonFiles } from '../storage/jsonFiles';
import { MemoryFolder } from '../storage/memoryFolder';
import { NativeFolder, SafFolder } from '../storage/nativeFolder';
import {
  pickWebFolder, rememberedWebFolder, WebFolder, webFolderExists, webFolderPermission, webFolderSupported,
} from '../storage/webFolder';

export type Platform = 'native' | 'web' | 'memory';

declare global {
  interface Window {
    /** Files for the in-memory folder, injected by Playwright tests and screenshot scripts. */
    __SMART_STUDY_SEED__?: Record<string, string>;
  }
}

/** `?storage=memory` forces the in-memory folder (automated tests, quick demos). */
export const platform = (): Platform => {
  if (Capacitor.isNativePlatform()) return 'native';
  if (new URLSearchParams(window.location.search).get('storage') === 'memory') return 'memory';
  return webFolderSupported() ? 'web' : 'memory';
};

const memoryFolder = () => new MemoryFolder('内存（刷新后消失）', window.__SMART_STUDY_SEED__ ?? {});

/** What the start screen should show before the data folder is open. */
export type FolderState =
  | { kind: 'none' }
  /** PC browser: the folder is remembered but the browser wants a click before granting access again. */
  | { kind: 'needs-permission'; label: string; handle: FileSystemDirectoryHandle }
  /** Android: a folder was chosen before but the grant was lost (e.g. after reinstalling). PC: the folder is gone. */
  | { kind: 'lost'; label: string }
  | { kind: 'ready'; folder: Folder };

export async function findFolder(): Promise<FolderState> {
  const where = platform();
  if (where === 'native') {
    const status = await SafFolder.getFolder();
    if (!status.configured) return { kind: 'none' };
    return status.granted ? { kind: 'ready', folder: new NativeFolder(status.label ?? 'smartstudy', status.folderId ?? '') } : { kind: 'lost', label: status.label ?? '' };
  }
  if (where === 'web') {
    const handle = await rememberedWebFolder().catch(() => undefined);
    if (!handle) return { kind: 'none' };
    const state = await webFolderPermission(handle);
    if (state !== 'granted') return { kind: 'needs-permission', label: handle.name, handle };
    // A folder that was moved or deleted would otherwise look like an empty one, as if all data were gone.
    return await webFolderExists(handle) ? { kind: 'ready', folder: new WebFolder(handle) } : { kind: 'lost', label: handle.name };
  }
  return window.__SMART_STUDY_SEED__ ? { kind: 'ready', folder: memoryFolder() } : { kind: 'none' };
}

/** Must run inside a click handler. */
export async function chooseFolder(): Promise<Folder> {
  const where = platform();
  if (where === 'native') {
    const status = await SafFolder.pickFolder();
    return new NativeFolder(status.label ?? 'smartstudy', status.folderId ?? '');
  }
  if (where === 'web') return new WebFolder(await pickWebFolder());
  return memoryFolder();
}

/** Must run inside a click handler. */
export async function reopenWebFolder(handle: FileSystemDirectoryHandle): Promise<Folder> {
  if (await webFolderPermission(handle, true) !== 'granted') throw new Error('浏览器没有授予这个文件夹的读写权限');
  if (!await webFolderExists(handle)) throw new Error(`文件夹 ${handle.name} 找不到了（可能被移动、改名或删除），请选择其他文件夹`);
  return new WebFolder(handle);
}

export type OpenResult =
  | { kind: 'ok'; id: number; files: JsonFiles; app: AppFile; settings: Settings; isNew: boolean; notices: string[] }
  | { kind: 'error'; message: string; /** Files that were already repaired before the unusable one was found. */ notices: string[] };

let opens = 0;

/** Load app.json and settings.json from an opened folder. Only a recovery (see JsonFiles.load) writes files. */
export async function openFolder(folder: Folder): Promise<OpenResult> {
  const files = new JsonFiles(folder);
  const notices: string[] = [];
  const app = await files.load(paths.app, checkAppFile);
  if (app.status === 'unusable') return { kind: 'error', message: `无法读取用户数据（${paths.app}）：${app.error}`, notices };
  if (app.status === 'ok' && app.problem) notices.push(`用户数据有问题，已从 ${app.recoveredFrom} 恢复：${app.problem}`);

  const settings = await files.load(paths.settings, checkSettings);
  if (settings.status === 'unusable') return { kind: 'error', message: `无法读取设置（${paths.settings}）：${settings.error}`, notices };
  if (settings.status === 'ok' && settings.problem) notices.push(`设置有问题，已从 ${settings.recoveredFrom} 恢复：${settings.problem}`);

  const loadedApp = app.status === 'ok' ? app.value : emptyAppFile();
  return {
    kind: 'ok', id: ++opens, files, notices,
    // A hand-edited app.json may list children without choosing one; pages need a current child.
    app: loadedApp.currentUserId === null && loadedApp.users.length ? { ...loadedApp, currentUserId: loadedApp.users[0].id } : loadedApp,
    settings: settings.status === 'ok' ? settings.value : defaultSettings(),
    isNew: app.status === 'missing',
  };
}
