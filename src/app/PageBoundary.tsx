import { Component, type ReactNode } from 'react';

/**
 * Catches an error while showing a page, so one broken page (for example a draft in an unexpected shape) shows a
 * message with a way back instead of a blank app. Data files are not touched here: nothing is written.
 */
export class PageBoundary extends Component<{ children: ReactNode; onHome(): void; onDiscardDrafts(): Promise<void> }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    const home = () => { this.setState({ error: null }); this.props.onHome(); };
    return (
      <div className="page"><main className="page-body"><div className="page-inner stack">
        <h1>这个页面出错了</h1>
        <div className="notice error">{error.message || String(error)}</div>
        <p className="muted">数据文件没有改动。可以回到首页再试；如果每次打开这个页面都出错，可以放弃没做完的听写和练习（只删 App 里保存的进度，不删学习记录）。</p>
        <button className="button primary big" onClick={home}>回到首页</button>
        <button className="button" onClick={() => void this.props.onDiscardDrafts().then(home)}>放弃没做完的听写和练习</button>
      </div></main></div>
    );
  }
}
