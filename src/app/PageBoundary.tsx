import { Component, useState, type ReactNode } from 'react';
import { draftKey as dictationDraftKey } from '../features/dictation/types';
import { draftKey as errorBookDraftKey } from '../features/errorBook/types';
import { drafts } from '../services/device';
import { errorText, Page } from '../ui/components';
import { useBackHandler, useNavigation } from './navigation';
import { useWorkspace } from './workspace';

/**
 * Catches an error while showing a page, so one broken page (for example a draft in an unexpected shape) shows a
 * message with a way back instead of a blank app. Data files are not touched here: nothing is written.
 */
class Boundary extends Component<{ children: ReactNode; fallback(error: Error, reset: () => void): ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    const { error } = this.state;
    return error ? this.props.fallback(error, () => this.setState({ error: null })) : this.props.children;
  }
}

export function PageBoundary({ children }: { children: ReactNode }) {
  return <Boundary fallback={(error, reset) => <BrokenPage error={error} onReset={reset} />}>{children}</Boundary>;
}

function BrokenPage({ error, onReset }: { error: Error; onReset(): void }) {
  const { reset } = useNavigation();
  const { currentUser } = useWorkspace();
  const [confirming, setConfirming] = useState(false);
  const [problem, setProblem] = useState('');
  const home = () => { onReset(); reset(); };
  // The broken page's own back handling is gone; back here goes home, it never steps through hidden pages or exits.
  useBackHandler(true, home);
  // The drafts of the current child only: those of the other children are theirs to keep.
  const discard = async () => {
    if (!currentUser) return home();
    try {
      for (const key of [dictationDraftKey(currentUser.id, 'chinese'), dictationDraftKey(currentUser.id, 'english'), errorBookDraftKey(currentUser.id)]) {
        await drafts.clear(key);
      }
      home();
    } catch (failure) {
      setProblem(`没有放弃成功：${errorText(failure)}`);
    }
  };
  const name = currentUser?.name ?? '';
  return (
    <Page title="这个页面出错了" back={false}>
      <div className="notice error">{error.message || String(error)}</div>
      <p className="muted">数据文件没有改动。可以回到首页再试；如果每次打开这个页面都出错，可能是没做完的听写或练习保存的进度有问题，可以放弃{name}没做完的听写和练习（只删 App 里保存的进度，不删学习记录，其他孩子的不受影响）。</p>
      {problem && <div className="notice error">{problem}</div>}
      <button className="button primary big" onClick={home}>回到首页</button>
      {confirming
        ? <div className="row">
            <button className="button grow" onClick={() => setConfirming(false)}>取消</button>
            <button className="button danger grow" onClick={() => void discard()}>放弃{name}没做完的听写和练习</button>
          </div>
        : <button className="button" onClick={() => setConfirming(true)}>放弃没做完的听写和练习…</button>}
    </Page>
  );
}
