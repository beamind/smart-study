import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { nowIso } from '../core/time';
import { paths, type AppFile, type Settings, type User } from '../data/schema';
import { checkAppFile, checkSettings } from '../data/validate';
import { addDemoUser } from '../demo/demo';
import { createAi, directCall, fakeAi, isFakeAi, type AiService } from '../ai/tasks';
import { trialCall, trialRemaining, TRIAL_URL, type TrialDependencies, type TrialRemaining } from '../ai/trial';
import { trialDeviceId } from '../services/trialDevice';
import { setApiKey as storeApiKey } from '../services/secrets';
import { DataCache } from '../storage/dataCache';
import { SaveInterruptedError, type JsonFiles } from '../storage/jsonFiles';

/**
 * Everything that is loaded once the data folder is open: users, settings, the API key,
 * and the JsonFiles instance used by feature pages to read and write their own data files.
 */
export interface Workspace {
  files: JsonFiles;
  /** Per-user data files, read once and written through one queue per file. */
  cache: DataCache;
  /** Model calls with the current settings and key (fake with ?ai=fake). */
  ai: AiService;
  folderLabel: string;
  app: AppFile;
  settings: Settings;
  apiKey: string;
  /** No API key, so model calls go through the trial proxy (docs/decisions/004). */
  trial: boolean;
  /** What is left of this device's trial; null when the proxy cannot be reached or there is no trial. */
  trialRemaining(): Promise<TrialRemaining | null>;
  currentUser: User | null;
  addUser(name: string): Promise<User>;
  /** Add the example child 小华 with a few records (src/demo) and switch to it. */
  addDemoUser(): Promise<User>;
  renameUser(id: string, name: string): Promise<void>;
  deleteUser(id: string): Promise<void>;
  switchUser(id: string): Promise<void>;
  updateSettings(change: (settings: Settings) => Settings): Promise<void>;
  setApiKey(value: string): Promise<void>;
  changeFolder(): void;
}

const Context = createContext<Workspace | null>(null);

export function useWorkspace(): Workspace {
  const workspace = useContext(Context);
  if (!workspace) throw new Error('useWorkspace outside WorkspaceProvider');
  return workspace;
}

export class UserNameError extends Error {}

const TRIAL_DEPS: TrialDependencies = { fetch: (...args) => fetch(...args), deviceId: trialDeviceId };

export function validateUserName(name: string, users: User[], exceptId?: string): string {
  const trimmed = name.trim();
  if (!trimmed) throw new UserNameError('请输入名字');
  if (trimmed.length > 20) throw new UserNameError('名字最多 20 个字');
  if (users.some(user => user.name === trimmed && user.id !== exceptId)) throw new UserNameError('已经有这个名字了');
  return trimmed;
}

interface Props {
  files: JsonFiles;
  app: AppFile;
  settings: Settings;
  apiKey: string;
  onChangeFolder(): void;
  /** Shows a message until the user dismisses it (the start screen's notices). */
  onNotice(message: string): void;
  children: ReactNode;
}

const FILE_LABELS: Array<[RegExp, string]> = [[/dictation-chinese\.json$/, '语文听写'], [/dictation-english\.json$/, '英语听写'], [/error-book\.json$/, '英语错题本']];

export function WorkspaceProvider({ files, app: initialApp, settings: initialSettings, apiKey: initialKey, onChangeFolder, onNotice, children }: Props) {
  const [app, setApp] = useState(initialApp);
  const [settings, setSettings] = useState(initialSettings);
  const [apiKey, setKey] = useState(initialKey);
  // Keep the latest values for async callbacks without re-creating them on every render.
  const appRef = useRef(app);
  const settingsRef = useRef(settings);
  const noticeRef = useRef(onNotice);
  noticeRef.current = onNotice;
  // A restored file is announced app-wide: the page that read it (e.g. 学习记录) may not show the notice.
  const cache = useMemo(() => new DataCache(files, (path, message) => {
    const user = appRef.current.users.find(item => path.includes(`/users/${item.id}/`));
    const what = FILE_LABELS.find(([pattern]) => pattern.test(path))?.[1] ?? path;
    noticeRef.current(`${user ? `${user.name}的` : ''}${what}：${message}`);
  }), [files]);
  // Without a key, a build with a trial proxy uses it; otherwise calls fail with 'no-key' and ask for a key.
  const trial = !apiKey && !!TRIAL_URL;
  const ai = useMemo(() => (isFakeAi() ? fakeAi : createAi(trial
    ? trialCall(TRIAL_URL, TRIAL_DEPS)
    : directCall(settings, apiKey))), [settings, apiKey, trial]);
  const queue = useRef<Promise<unknown>>(Promise.resolve());

  /**
   * Changes to app.json and settings.json run one after another, each built from the latest saved value,
   * checked like a file read from disk, and written once. Persist first, then show: if the write fails,
   * the screen keeps showing what is on disk (except after an interrupted save, which is built on).
   */
  const enqueue = useCallback(<R,>(task: () => Promise<R>) => {
    const run = queue.current.catch(() => undefined).then(task);
    queue.current = run;
    return run;
  }, []);
  const commitApp = useCallback(<R,>(change: (current: AppFile) => { value: AppFile; result: R }) => enqueue(async () => {
    const { value, result } = change(appRef.current);
    const checked = checkAppFile(value);
    if (!checked.ok) throw new Error(`用户数据没有通过校验，未保存：${checked.error}`);
    await files.save(paths.app, checked.value).catch((error: unknown) => {
      // The value waits in the temp file: build the next change on it, so that save does not drop this one.
      if (error instanceof SaveInterruptedError) { appRef.current = checked.value; setApp(checked.value); }
      throw error;
    });
    appRef.current = checked.value;
    setApp(checked.value);
    return result;
  }), [files, enqueue]);

  const workspace = useMemo<Workspace>(() => ({
    files,
    cache,
    ai,
    folderLabel: files.folder.label,
    app,
    settings,
    apiKey,
    trial,
    trialRemaining: () => (TRIAL_URL ? trialRemaining(TRIAL_URL, TRIAL_DEPS) : Promise.resolve(null)),
    // A file with children but no current one (only written by hand or another version) uses the first child.
    currentUser: app.users.find(user => user.id === app.currentUserId) ?? app.users[0] ?? null,

    addUser(name) {
      return commitApp(current => {
        const user: User = { id: crypto.randomUUID(), name: validateUserName(name, current.users), createdAt: nowIso() };
        return { value: { ...current, users: [...current.users, user], currentUserId: current.currentUserId ?? user.id }, result: user };
      });
    },
    addDemoUser() {
      // Through the same queue as other app.json changes, built from the latest saved value.
      return enqueue(async () => {
        const { app: next, user } = await addDemoUser(files, appRef.current).catch((error: unknown) => {
          // As in commitApp: an interrupted app.json save is built on (the example child's files are written by then).
          if (error instanceof SaveInterruptedError && error.path === paths.app) {
            const pending = error.value as AppFile;
            appRef.current = pending;
            setApp(pending);
          }
          throw error;
        });
        appRef.current = next;
        setApp(next);
        return user;
      });
    },
    renameUser(id, name) {
      return commitApp(current => {
        const trimmed = validateUserName(name, current.users, id);
        return { value: { ...current, users: current.users.map(user => user.id === id ? { ...user, name: trimmed } : user) }, result: undefined };
      });
    },
    deleteUser(id) {
      // The user's data files stay in data/users/<id>/ and remain in history; nothing is deleted from disk here.
      return commitApp(current => {
        const users = current.users.filter(user => user.id !== id);
        return { value: { ...current, users, currentUserId: current.currentUserId === id ? users[0]?.id ?? null : current.currentUserId }, result: undefined };
      });
    },
    switchUser(id) {
      return commitApp(current => ({ value: { ...current, currentUserId: id }, result: undefined }));
    },
    updateSettings(change) {
      return enqueue(async () => {
        const checked = checkSettings(change(settingsRef.current));
        if (!checked.ok) throw new Error(`设置没有通过校验，未保存：${checked.error}`);
        await files.save(paths.settings, checked.value);
        settingsRef.current = checked.value;
        setSettings(checked.value);
      });
    },
    async setApiKey(value) {
      await storeApiKey(value);
      setKey(value.trim());
    },
    changeFolder: onChangeFolder,
  }), [files, cache, ai, app, settings, apiKey, trial, commitApp, enqueue, onChangeFolder]);

  return <Context.Provider value={workspace}>{children}</Context.Provider>;
}
