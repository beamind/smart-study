import { FolderOpen, KeyRound, UserRound } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { nowIso } from '../core/time';
import { paths, type AppFile } from '../data/schema';
import { getApiKey } from '../services/secrets';
import { TRIAL_URL } from '../ai/trial';
import type { Folder } from '../storage/folder';
import { errorText } from '../ui/components';
import {
  chooseFolder, findFolder, openFolder, platform, reopenWebFolder, type FolderState, type OpenResult,
} from './startup';
import { addDemoUser, DEMO_NAME } from '../demo/demo';
import { useNavigation } from './navigation';
import { validateUserName, WorkspaceProvider } from './workspace';
import { setApiKey } from '../services/secrets';

type Opened = Extract<OpenResult, { kind: 'ok' }>;

type Step =
  | { kind: 'checking' }
  | { kind: 'folder'; state: FolderState; error?: string }
  | { kind: 'error'; message: string }
  | { kind: 'key'; opened: Opened }
  | { kind: 'user'; opened: Opened }
  | { kind: 'ready'; opened: Opened; apiKey: string };

/**
 * First-run and every-run start: open the data folder, then (only when needed) ask for the API key
 * and create the first user.
 */
export function Start({ children }: { children: ReactNode }) {
  const [step, setStep] = useState<Step>({ kind: 'checking' });
  const [notices, setNotices] = useState<string[]>([]);
  const { reset } = useNavigation();

  const proceed = useCallback(async (folder: Folder, pickedNow: boolean) => {
    const opened = await openFolder(folder);
    if (opened.kind === 'error') { setNotices(opened.notices); setStep({ kind: 'error', message: opened.message }); return; }
    setNotices(opened.notices);
    const apiKey = await getApiKey();
    if (pickedNow && !apiKey) setStep({ kind: 'key', opened });
    else if (!opened.app.users.length) setStep({ kind: 'user', opened });
    else setStep({ kind: 'ready', opened, apiKey });
  }, []);

  const check = useCallback(async () => {
    setStep({ kind: 'checking' });
    try {
      const state = await findFolder();
      if (state.kind === 'ready') await proceed(state.folder, false);
      else setStep({ kind: 'folder', state });
    } catch (error) {
      setStep({ kind: 'folder', state: { kind: 'none' }, error: errorText(error) });
    }
  }, [proceed]);

  // Once per mount: React StrictMode runs effects twice in development, and two parallel folder checks
  // would recover the same damaged file at the same time.
  const checked = useRef(false);
  useEffect(() => {
    if (checked.current) return;
    checked.current = true;
    void check();
  }, [check]);

  // One folder at a time: a second tap while a folder opens would recover the same damaged file twice.
  const picking = useRef(false);
  const current = useRef(step);
  current.current = step;
  const pick = async (reopen?: FileSystemDirectoryHandle) => {
    if (picking.current) return;
    picking.current = true;
    const fail = (message: string) =>
      setStep(now => ({ kind: 'folder', state: now.kind === 'folder' ? now.state : { kind: 'none' }, error: message }));
    try {
      let folder: Folder;
      try {
        folder = reopen ? await reopenWebFolder(reopen) : await chooseFolder();
      } catch (error) {
        const message = errorText(error);
        if (/没有选择|abort/i.test(message)) return;
        // 更换文件夹 failed before another folder was chosen: the open folder is still the configured one, so stay in it.
        if (current.current.kind === 'ready') setNotices(notices => [...notices, `没有更换文件夹：${message}`]);
        else fail(message);
        return;
      }
      // From here on the new folder is the configured one: close the old workspace before opening the new folder,
      // and forget its pages, so back on the next screen does not go to a page of the old folder.
      reset();
      setStep({ kind: 'checking' });
      try { await proceed(folder, true); } catch (error) { fail(errorText(error)); }
    } finally {
      picking.current = false;
    }
  };

  const next = async (opened: Opened) => {
    const apiKey = await getApiKey();
    if (!opened.app.users.length) setStep({ kind: 'user', opened });
    else setStep({ kind: 'ready', opened, apiKey });
  };

  switch (step.kind) {
    case 'checking':
      return <Shell><p className="muted">正在打开数据文件夹…</p></Shell>;
    case 'folder':
      return <FolderStep state={step.state} error={step.error} onPick={pick} />;
    case 'error':
      return (
        <Shell icon={<FolderOpen size={28} />} title="数据文件无法使用">
          <div className="notice error">{step.message}</div>
          {notices.map(notice => <div key={notice} className="notice">{notice}</div>)}
          <p className="muted small">无法使用的文件没有被修改。可以把这个文件夹拷到电脑上检查，或者选择其他文件夹。</p>
          <button className="button primary big" onClick={() => pick()}>选择其他文件夹</button>
        </Shell>
      );
    case 'key':
      return <KeyStep onDone={() => void next(step.opened)} />;
    case 'user':
      return <UserStep opened={step.opened} onDone={app => void getApiKey().then(apiKey => setStep({ kind: 'ready', opened: { ...step.opened, app }, apiKey }))} />;
    case 'ready':
      return (
        // A new key per opened folder: after "更换文件夹" every state (users, settings, data cache) starts fresh.
        <WorkspaceProvider key={step.opened.id} files={step.opened.files} app={step.opened.app} settings={step.opened.settings}
          apiKey={step.apiKey} onChangeFolder={() => void pick()}
          onNotice={notice => setNotices(current => (current.includes(notice) ? current : [...current, notice]))}>
          <div className={`app-frame${notices.length > 0 ? ' has-notices' : ''}`}>
            {notices.length > 0 && (
              <div className="app-notices">
                {notices.map(notice => <div key={notice} className="notice row"><span className="grow">{notice}</span><button className="button text" onClick={() => setNotices(current => current.filter(item => item !== notice))}>知道了</button></div>)}
              </div>
            )}
            <div className="app-content">{children}</div>
          </div>
        </WorkspaceProvider>
      );
  }
}

function Shell({ icon, title, children }: { icon?: ReactNode; title?: string; children: ReactNode }) {
  return (
    <div className="page"><main className="page-body start-shell">
      <div className="card start-card">
        {icon && <div className="start-icon">{icon}</div>}
        {title && <h1 className="start-title">{title}</h1>}
        {children}
      </div>
    </main></div>
  );
}

function FolderStep({ state, error, onPick }: { state: FolderState; error?: string; onPick(reopen?: FileSystemDirectoryHandle): void }) {
  const where = platform();
  return (
    <Shell icon={<FolderOpen size={28} />} title="选择数据文件夹">
      {where === 'native' && (state.kind === 'lost'
        ? <p>之前使用的 <b>{state.label}</b> 需要重新授权。请在下一步选择 <b>Documents</b> 文件夹（或其中的 smartstudy），点"使用此文件夹"。</p>
        : <p>听写和错题记录保存在 <b>Documents/smartstudy</b>，卸载 App 也不会丢失。请在下一步选择 <b>Documents</b> 文件夹，点"使用此文件夹"。</p>)}
      {where === 'web' && (state.kind === 'needs-permission'
        ? <p>继续使用电脑上的文件夹 <b>{state.label}</b>，浏览器会再确认一次读写权限。</p>
        : state.kind === 'lost'
        ? <p>之前使用的文件夹 <b>{state.label}</b> 找不到了，可能被移动、改名或删除。请重新选择数据文件夹。</p>
        : <p>选择一个文件夹保存数据。可以选从平板拷出来的 smartstudy 文件夹，用真实数据试用。</p>)}
      {where === 'memory' && <p>数据只保存在内存中，刷新页面后消失。要保存到电脑上的文件夹，请用 Chrome 或 Edge 打开，且网址不带 <code>?storage=memory</code>。</p>}
      {error && <div className="notice error">{error}</div>}
      {state.kind === 'needs-permission'
        ? <>
            <button className="button primary big" onClick={() => onPick(state.handle)}>继续使用 {state.label}</button>
            <button className="button" onClick={() => onPick()}>选择其他文件夹</button>
          </>
        : <button className="button primary big" onClick={() => onPick()}>{where === 'memory' ? '先试用' : '选择文件夹'}</button>}
    </Shell>
  );
}

function KeyStep({ onDone }: { onDone(): void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  return (
    <Shell icon={<KeyRound size={28} />} title="填写千问 API Key">
      <p>拍照识别词语和题目、翻译、举一反三需要阿里云百炼（DashScope）的 API Key。</p>
      {TRIAL_URL && <p>不填也可以先免费试用，次数有限，在"设置"里能看到还剩多少。试用时照片会经过作者的服务器转给千问，不会保存。</p>}
      <p className="muted small">Key 只保存在这台设备的 App 里，不会写入数据文件夹。重装 App 后需要重新填写。以后也可以在"设置"里修改。</p>
      <label className="field"><span>API Key</span>
        <input className="input" value={value} onChange={event => setValue(event.target.value)} placeholder="sk-…" autoComplete="off" spellCheck={false} />
      </label>
      {error && <div className="notice error">{error}</div>}
      <button className="button primary big" disabled={!value.trim()} onClick={async () => {
        try { await setApiKey(value); onDone(); } catch (problem) { setError(errorText(problem)); }
      }}>保存</button>
      <button className="button text" onClick={onDone}>{TRIAL_URL ? '先免费试用' : '以后再填'}</button>
    </Shell>
  );
}

function UserStep({ opened, onDone }: { opened: Opened; onDone(app: AppFile): void }) {
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false); // state updates too late to stop a second Enter in the same frame
  const run = async (create: () => Promise<AppFile>) => {
    if (inFlight.current) return; // Enter pressed twice must not write app.json twice
    inFlight.current = true;
    setBusy(true);
    try {
      onDone(await create());
    } catch (problem) {
      setError(errorText(problem));
      setBusy(false);
      inFlight.current = false;
    }
  };
  const submit = () => {
    if (!name.trim()) return;
    void run(async () => {
      const user = { id: crypto.randomUUID(), name: validateUserName(name, opened.app.users), createdAt: nowIso() };
      const app: AppFile = { ...opened.app, users: [...opened.app.users, user], currentUserId: user.id };
      await opened.files.save(paths.app, app);
      return app;
    });
  };
  const demo = () => void run(async () => (await addDemoUser(opened.files, opened.app)).app);
  return (
    <Shell icon={<UserRound size={28} />} title="谁在学习？">
      <p>每个孩子的记录分开保存。以后可以在首页添加更多孩子。</p>
      <label className="field"><span>名字</span>
        <input className="input" value={name} maxLength={20} onChange={event => setName(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') submit(); }} autoFocus />
      </label>
      {error && <div className="notice error">{error}</div>}
      <button className="button primary big" disabled={!name.trim() || busy} onClick={submit}>开始使用</button>
      <p className="muted small">想先看看效果？可以先用示例孩子“{DEMO_NAME}”，里面有几次听写和一些错题。以后在首页点名字添加自己的孩子，再把{DEMO_NAME}删掉。</p>
      <button className="button" disabled={busy} onClick={demo}>先看看示例（{DEMO_NAME}）</button>
    </Shell>
  );
}
