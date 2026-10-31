import { DictationPage } from '../features/dictation/DictationPage';
import { WordBook } from '../features/dictation/WordBook';
import { ErrorBookPage } from '../features/errorBook/ErrorBookPage';
import { Home } from '../features/home/Home';
import { RecordsPage } from '../features/records/RecordsPage';
import { Settings } from '../features/settings/Settings';
import { Users } from '../features/users/Users';
import { ToastProvider } from '../ui/components';
import { NavigationProvider, useNavigation } from './navigation';
import { Start } from './Start';

export function App() {
  return (
    <ToastProvider>
      <NavigationProvider>
        <Start><CurrentPage /></Start>
      </NavigationProvider>
    </ToastProvider>
  );
}

function CurrentPage() {
  const { route } = useNavigation();
  switch (route.name) {
    case 'home': return <Home />;
    case 'users': return <Users />;
    case 'settings': return <Settings />;
    case 'dictation': return <DictationPage key={route.language} language={route.language} replay={route.replay} />;
    case 'word-book': return <WordBook key={route.language} language={route.language} />;
    case 'error-book': return <ErrorBookPage />;
    case 'records': return <RecordsPage />;
  }
}
