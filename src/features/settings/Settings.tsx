import { ChevronRight, Coffee, FolderOpen, Gift, KeyRound, MessageCircle, RotateCcw } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { DEFAULT_PROMPTS, PROMPT_LABELS } from '../../ai/prompts';
import type { TrialRemaining } from '../../ai/trial';
import { useWorkspace } from '../../app/workspace';
import { defaultSettings } from '../../data/defaults';
import { PROMPT_KEYS, type PromptKey, type Settings as SettingsData } from '../../data/schema';
import { copyText } from '../../services/clipboard';
import { maskApiKey } from '../../services/secrets';
import { version } from '../../../package.json';
import { errorText, Page, Sheet, useToast } from '../../ui/components';
// The same picture as in README.md.
import rewardCode from '../../../docs/images/reward-code.png';

/** The QQ group for questions and feedback (also in README.md). */
const FEEDBACK_QQ_GROUP = '747233425';

export function Settings() {
  const workspace = useWorkspace();
  const toast = useToast();
  const [draft, setDraft] = useState(workspace.settings);
  const [editing, setEditing] = useState<'key' | 'reward' | PromptKey | null>(null);
  const saved = useRef(JSON.stringify(workspace.settings));
  /** The settings being written right now, so the same change is never written twice. */
  const saving = useRef<string | null>(null);
  const latest = useRef({ draft, workspace });
  latest.current = { draft, workspace };

  // Save shortly after the last change, so dragging a slider writes the file once.
  useEffect(() => {
    const text = JSON.stringify(draft);
    if (text === saved.current) return;
    const timer = window.setTimeout(() => {
      if (text === saving.current) return;
      saving.current = text;
      workspace.updateSettings(() => draft)
        .then(() => { saved.current = text; })
        .catch(error => toast.show(`设置没有保存：${errorText(error)}`))
        .finally(() => { if (saving.current === text) saving.current = null; });
    }, 600);
    return () => window.clearTimeout(timer);
  }, [draft, workspace, toast]);

  // Leaving the page within the delay still saves the last change.
  useEffect(() => () => {
    const { draft: last, workspace: current } = latest.current;
    const text = JSON.stringify(last);
    if (text !== saved.current && text !== saving.current) void current.updateSettings(() => last).catch(() => undefined);
  }, []);

  const set = (change: (settings: SettingsData) => SettingsData) => setDraft(current => change(structuredClone(current)));

  return (
    <Page title="设置">
      <Section title="千问">
        <button className="list-row" onClick={() => setEditing('key')}>
          <KeyRound size={20} className="muted" />
          <span className="grow">API Key</span>
          <span className="muted small">{workspace.apiKey ? maskApiKey(workspace.apiKey) : workspace.trial ? '未填写（正在试用）' : '未填写'}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        {workspace.trial && <TrialRow />}
      </Section>
      {workspace.trial && <p className="muted small">试用时使用默认的模型和提示词，超时和重试次数也由试用服务决定；下面"模型"和"提示词"里的修改要填写自己的 Key 后才生效。</p>}

      <Section title="朗读">
        <Slider label="语速" value={draft.tts.rate} min={0.2} max={1.2} step={0.05} format={value => `${value.toFixed(2)} 倍`}
          onChange={value => set(settings => { settings.tts.rate = value; return settings; })} />
        <Slider label="语文：两遍之间" value={draft.tts.repeatGapMs.chinese / 1000} min={0} max={5} step={0.5} format={value => `${value} 秒`}
          onChange={value => set(settings => { settings.tts.repeatGapMs.chinese = Math.round(value * 1000); return settings; })} />
        <Slider label="英语：两遍之间" value={draft.tts.repeatGapMs.english / 1000} min={0} max={5} step={0.5} format={value => `${value} 秒`}
          onChange={value => set(settings => { settings.tts.repeatGapMs.english = Math.round(value * 1000); return settings; })} />
      </Section>

      <Section title="每题限时">
        <Choice label="听写" value={draft.timeLimitSeconds.dictation} options={[0, 30, 60, 90, 120, 180]}
          onChange={value => set(settings => { settings.timeLimitSeconds.dictation = value; return settings; })} />
        <Choice label="错题练习" value={draft.timeLimitSeconds.errorBook} options={[0, 60, 120, 180, 300]}
          onChange={value => set(settings => { settings.timeLimitSeconds.errorBook = value; return settings; })} />
      </Section>

      <details className="card advanced">
        <summary>模型（高级）</summary>
        <div className="stack">
          <TextField label="识别图片" value={draft.models.vision} onChange={value => set(settings => { settings.models.vision = value; return settings; })} />
          <TextField label="翻译" value={draft.models.translation} onChange={value => set(settings => { settings.models.translation = value; return settings; })} />
          <TextField label="举一反三" value={draft.models.generation} onChange={value => set(settings => { settings.models.generation = value; return settings; })} />
          <Slider label="举一反三 temperature" value={draft.models.generationTemperature} min={0} max={1.99} step={0.01} format={value => value.toFixed(2)}
            onChange={value => set(settings => { settings.models.generationTemperature = value; return settings; })} />
          <Slider label="单次请求超时" value={draft.models.requestTimeoutSeconds} min={10} max={120} step={5} format={value => `${value} 秒`}
            onChange={value => set(settings => { settings.models.requestTimeoutSeconds = value; return settings; })} />
          <Slider label="失败时最多尝试" value={draft.models.maxAttempts} min={1} max={5} step={1} format={value => `${value} 次`}
            onChange={value => set(settings => { settings.models.maxAttempts = value; return settings; })} />
          <button className="button" onClick={() => set(settings => ({ ...settings, models: defaultSettings().models }))}><RotateCcw size={18} />恢复默认模型</button>
        </div>
      </details>

      <details className="card advanced">
        <summary>提示词（高级）</summary>
        <div className="list settings-links">
          {PROMPT_KEYS.map(key => (
            <button key={key} className="list-row" onClick={() => setEditing(key)}>
              <span className="grow">{PROMPT_LABELS[key]}</span>
              {draft.prompts[key] !== undefined && <span className="badge">已修改</span>}
              <ChevronRight size={18} className="muted" />
            </button>
          ))}
        </div>
      </details>

      <Section title="数据">
        <div className="list-row">
          <FolderOpen size={20} className="muted" />
          <span className="grow"><span className="muted small">数据文件夹</span><br />{workspace.folderLabel}</span>
          <button className="button" onClick={workspace.changeFolder}>更换</button>
        </div>
      </Section>

      <Section title="反馈和支持">
        <div className="list-row">
          <MessageCircle size={20} className="muted" />
          <span className="grow"><span className="muted small">QQ 交流群</span><br />{FEEDBACK_QQ_GROUP}</span>
          <button className="button" onClick={async () => toast.show(await copyText(FEEDBACK_QQ_GROUP) ? '已复制群号，可以在 QQ 里搜索加入' : '没能复制，请记下群号在 QQ 里搜索')}>复制群号</button>
        </div>
        <button className="list-row" onClick={() => setEditing('reward')}>
          <Coffee size={20} className="muted" />
          <span className="grow">支持作者</span>
          <span className="muted small">微信赞赏</span>
          <ChevronRight size={18} className="muted" />
        </button>
      </Section>
      <p className="muted small">在 QQ 里搜索群号加入。反馈时请写上手机或平板的型号、在哪一步出的问题，最好附上截图；截图前可以先切换到示例孩子小华。</p>
      <p className="muted small" style={{ textAlign: 'center' }}>版本 {version}</p>

      {editing === 'key' && <KeySheet onClose={() => setEditing(null)} />}
      {editing === 'reward' && (
        <Sheet title="支持作者" onClose={() => setEditing(null)}>
          <p className="muted small">智能学习是免费开源的，免费试用调用千问的费用由作者承担。如果它帮到了你家孩子，欢迎用微信扫码赞赏。在这台设备上扫不了时，可以用另一台手机扫，或者截屏后在微信“扫一扫”里从相册选择。</p>
          <img className="reward-code" src={rewardCode} alt="微信赞赏码" />
          <button className="button" onClick={() => setEditing(null)}>关闭</button>
        </Sheet>
      )}
      {editing && editing !== 'key' && editing !== 'reward' && (
        <PromptSheet promptKey={editing} value={draft.prompts[editing]} onClose={() => setEditing(null)}
          onSave={value => set(settings => {
            if (value === undefined || value === DEFAULT_PROMPTS[editing]) delete settings.prompts[editing];
            else settings.prompts[editing] = value;
            return settings;
          })} />
      )}
    </Page>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return <section className="stack-tight"><h2 className="section-title">{title}</h2><div className="list">{children}</div></section>;
}

function Slider({ label, value, min, max, step, format, onChange }: {
  label: string; value: number; min: number; max: number; step: number; format(value: number): string; onChange(value: number): void;
}) {
  return (
    <label className="list-row slider">
      <span className="grow">{label}</span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={event => onChange(Number(event.target.value))} />
      <span className="slider-value">{format(value)}</span>
    </label>
  );
}

function Choice({ label, value, options, onChange }: { label: string; value: number; options: number[]; onChange(value: number): void }) {
  return (
    <div className="list-row wrap-row">
      <span className="grow">{label}</span>
      <div className="chips">
        {options.map(option => (
          <button key={option} className={`chip${option === value ? ' selected' : ''}`} onClick={() => onChange(option)}>
            {option === 0 ? '不限时' : option < 60 || option % 60 ? `${option} 秒` : `${option / 60} 分钟`}
          </button>
        ))}
      </div>
    </div>
  );
}

function TextField({ label, value, onChange }: { label: string; value: string; onChange(value: string): void }) {
  return (
    <label className="field"><span>{label}</span>
      <input className="input" value={value} spellCheck={false} onChange={event => onChange(event.target.value)} />
    </label>
  );
}

/** The trial allowance left on this device (docs/decisions/004), asked from the proxy when the page opens. */
function TrialRow() {
  const workspace = useWorkspace();
  const [left, setLeft] = useState<TrialRemaining | null | undefined>(undefined);
  useEffect(() => {
    let current = true;
    void workspace.trialRemaining().then(value => { if (current) setLeft(value); });
    return () => { current = false; };
    // Once per opening of the page; the workspace object changes with every settings save.
  }, []);
  return (
    <div className="list-row">
      <Gift size={20} className="muted" />
      <span className="grow"><span className="muted small">试用还剩</span><br />
        {left === undefined ? '正在查询…'
          : left === null ? '暂时查不到，请检查网络'
          : `拍照识别 ${left.photo} 次，翻译 ${left.translate} 次，举一反三 ${left.generate} 次`}
      </span>
    </div>
  );
}

function KeySheet({ onClose }: { onClose(): void }) {
  const workspace = useWorkspace();
  const [value, setValue] = useState(workspace.apiKey);
  const [error, setError] = useState('');
  return (
    <Sheet title="千问 API Key" onClose={onClose} keepOnOutsideTap>
      <p className="muted small">只保存在这台设备的 App 里，不会写入数据文件夹。</p>
      <input className="input" value={value} autoFocus autoComplete="off" spellCheck={false} placeholder="sk-…" onChange={event => setValue(event.target.value)} />
      {error && <div className="notice error">{error}</div>}
      <div className="row">
        <button className="button grow" onClick={onClose}>取消</button>
        <button className="button primary grow" onClick={async () => {
          try { await workspace.setApiKey(value); onClose(); } catch (problem) { setError(errorText(problem)); }
        }}>保存</button>
      </div>
    </Sheet>
  );
}

function PromptSheet({ promptKey, value, onSave, onClose }: { promptKey: PromptKey; value?: string; onSave(value: string | undefined): void; onClose(): void }) {
  const [text, setText] = useState(value ?? DEFAULT_PROMPTS[promptKey]);
  return (
    <Sheet title={PROMPT_LABELS[promptKey]} onClose={onClose} keepOnOutsideTap>
      {(promptKey === 'chineseDictation' || promptKey === 'englishDictation') && (
        <p className="muted small">这里只写角色和任务。输出格式的要求由 App 固定附加，以保证结果能被解析。</p>
      )}
      <textarea className="input prompt" value={text} onChange={event => setText(event.target.value)} spellCheck={false} />
      <div className="row">
        <button className="button" onClick={() => setText(DEFAULT_PROMPTS[promptKey])}><RotateCcw size={18} />默认</button>
        <span style={{ flex: 1 }} />
        <button className="button" onClick={onClose}>取消</button>
        <button className="button primary" disabled={!text.trim()} onClick={() => { onSave(text); onClose(); }}>保存</button>
      </div>
    </Sheet>
  );
}
