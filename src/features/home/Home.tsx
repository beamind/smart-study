import { BookOpenCheck, ChevronDown, History, Languages, PenLine, Settings } from 'lucide-react';
import { useNavigation, type Route } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';

const entries: { key: string; route: Route; title: string; hint: string; icon: typeof PenLine }[] = [
  { key: 'dictation-chinese', route: { name: 'dictation', language: 'chinese' }, title: '语文听写', hint: '拍照或输入词语', icon: PenLine },
  { key: 'dictation-english', route: { name: 'dictation', language: 'english' }, title: '英语听写', hint: '读中文或读英文', icon: Languages },
  { key: 'error-book', route: { name: 'error-book' }, title: '英语错题本', hint: '录入、练习、举一反三', icon: BookOpenCheck },
  { key: 'records', route: { name: 'records' }, title: '学习记录', hint: '每一次的得分', icon: History },
];

export function Home() {
  const { push } = useNavigation();
  const { currentUser } = useWorkspace();
  return (
    <div className="page">
      <header className="page-header">
        <button className="user-switch" onClick={() => push({ name: 'users' })}>
          <span className="avatar">{currentUser?.name.slice(0, 1)}</span>
          <span>{currentUser?.name}</span>
          <ChevronDown size={18} />
        </button>
        <span style={{ flex: 1 }} />
        <button className="icon-button" aria-label="设置" onClick={() => push({ name: 'settings' })}><Settings size={24} /></button>
      </header>
      <main className="page-body">
        <div className="page-inner">
          <div className="home-grid">
            {entries.map(({ key, route, title, hint, icon: Icon }) => (
              <button key={key} className={`home-tile tile-${key}`} onClick={() => push(route)}>
                <span className="home-icon"><Icon size={28} /></span>
                <b>{title}</b>
                <span className="muted small">{hint}</span>
              </button>
            ))}
          </div>
        </div>
      </main>
    </div>
  );
}
