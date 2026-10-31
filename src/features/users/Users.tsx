import { Check, Pencil, Plus, Trash2 } from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigation } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import type { User } from '../../data/schema';
import { ArithmeticConfirm, errorText, Page, Sheet, useToast } from '../../ui/components';

type Editing = { mode: 'add' } | { mode: 'rename'; user: User } | { mode: 'delete'; user: User } | null;

export function Users() {
  const workspace = useWorkspace();
  const { back } = useNavigation();
  const toast = useToast();
  const [editing, setEditing] = useState<Editing>(null);
  // One switch at a time: a double tap must not write app.json twice.
  const switching = useRef(false);
  const [busy, setBusy] = useState(false);
  const choose = async (id: string) => {
    if (switching.current) return;
    switching.current = true;
    setBusy(true);
    try { if (id !== workspace.app.currentUserId) await workspace.switchUser(id); back(); } catch (error) { toast.show(errorText(error)); } finally {
      switching.current = false;
      setBusy(false);
    }
  };

  return (
    <Page title="谁在学习？" footer={<button className="button primary grow" onClick={() => setEditing({ mode: 'add' })}><Plus size={20} />添加孩子</button>}>
      <div className="list">
        {workspace.app.users.map(user => {
          const current = user.id === workspace.app.currentUserId;
          return (
            <div key={user.id} className="list-row">
              <button className="row grow user-choice" disabled={busy} onClick={() => void choose(user.id)}>
                <span className="avatar">{user.name.slice(0, 1)}</span>
                <span className="grow"><b>{user.name}</b></span>
                {current && <span className="badge"><Check size={16} />当前</span>}
              </button>
              <button className="icon-button" aria-label={`改名 ${user.name}`} onClick={() => setEditing({ mode: 'rename', user })}><Pencil size={20} /></button>
              <button className="icon-button" aria-label={`删除 ${user.name}`} disabled={workspace.app.users.length <= 1}
                onClick={() => setEditing({ mode: 'delete', user })}><Trash2 size={20} /></button>
            </div>
          );
        })}
      </div>
      <p className="muted small users-hint">点名字切换。每个孩子的听写和错题记录分开保存。</p>

      {editing?.mode === 'add' && (
        <NameSheet title="添加孩子" initial="" onClose={() => setEditing(null)} onSave={async name => { await workspace.addUser(name); toast.show(`已添加 ${name.trim()}`); }} />
      )}
      {editing?.mode === 'rename' && (
        <NameSheet title={`给 ${editing.user.name} 改名`} initial={editing.user.name} onClose={() => setEditing(null)}
          onSave={name => workspace.renameUser(editing.user.id, name)} />
      )}
      {editing?.mode === 'delete' && (
        <ArithmeticConfirm title={`删除 ${editing.user.name}？`} confirmLabel="删除"
          message={<p>{editing.user.name} 不会再出现在列表里。已有的听写和错题记录仍保留在数据文件夹中，不会被删除。</p>}
          onConfirm={async () => { await workspace.deleteUser(editing.user.id); toast.show(`已删除 ${editing.user.name}`); }}
          onClose={() => setEditing(null)} />
      )}
    </Page>
  );
}

function NameSheet({ title, initial, onSave, onClose }: { title: string; initial: string; onSave(name: string): Promise<unknown>; onClose(): void }) {
  const [name, setName] = useState(initial);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false); // state updates too late to stop a second Enter in the same frame
  const submit = async () => {
    if (inFlight.current || !name.trim()) return;
    inFlight.current = true;
    setBusy(true);
    try { await onSave(name); onClose(); } catch (problem) { setError(errorText(problem)); setBusy(false); inFlight.current = false; }
  };
  return (
    <Sheet title={title} onClose={onClose} keepOnOutsideTap>
      <label className="field"><span>名字</span>
        <input className="input" value={name} maxLength={20} autoFocus onChange={event => setName(event.target.value)}
          onKeyDown={event => { if (event.key === 'Enter') void submit(); }} />
      </label>
      {error && <div className="notice error">{error}</div>}
      <div className="row">
        <button className="button grow" onClick={onClose}>取消</button>
        <button className="button primary grow" disabled={!name.trim() || busy} onClick={() => void submit()}>保存</button>
      </div>
    </Sheet>
  );
}
