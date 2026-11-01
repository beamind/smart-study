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
  const { reset, route } = useNavigation();
  const { currentUser } = useWorkspace();
  const [confirming, setConfirming] = useState(false);
  const [problem, setProblem] = useState('');
  const home = () => { onReset(); reset(); };
  // The broken page's own back handling is gone; back here goes home, it never steps through hidden pages or exits.
  useBackHandler(true, home);
  // Only the draft of the page that broke (the route is still that page), of the current child: other drafts are fine.
  const draft = !currentUser ? null
    : route.name === 'dictation' ? { key: dictationDraftKey(currentUser.id, route.language), what: route.language === 'chinese' ? '语文听写' : '英语听写' }
    : route.name === 'error-book' ? { key: errorBookDraftKey(currentUser.id), what: '错题练习' }
    : null;
  const discard = async () => {
    if (!draft) return;
    try {
      await drafts.clear(draft.key);
      home();
    } catch (failure) {
      setProblem(`没有放弃成功：${errorText(failure)}`);
    }
  };
  const name = currentUser?.name ?? '';
  return (
    <Page title="这个页面出错了" back={false}>
      <div className="notice error">{error.message || String(error)}</div>
      <p className="muted">数据文件没有改动。可以回到首页再试。{draft && `如果每次打开这个页面都出错，可能是没做完的${draft.what}保存的进度有问题，可以放弃它（只删 App 里保存的这份进度，不删学习记录）。`}</p>
      {problem && <div className="notice error">{problem}</div>}
      <button className="button primary big" onClick={home}>回到首页</button>
      {draft && (confirming
        ? <div className="row">
            <button className="button grow" onClick={() => setConfirming(false)}>取消</button>
            <button className="button danger grow" onClick={() => void discard()}>放弃{name}没做完的{draft.what}</button>
          </div>
        : <button className="button" onClick={() => setConfirming(true)}>放弃没做完的{draft.what}…</button>)}
    </Page>
  );
}
