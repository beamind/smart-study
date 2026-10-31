import { App as NativeApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { DictationLanguage, ReadMode } from '../data/schema';

/**
 * A small page stack instead of a router: push opens a page, back closes it.
 * Back (Android back button, Esc on a PC, or the header arrow) first goes to the newest
 * registered handler (e.g. close a sheet), then pops the page; on the home page the
 * Android back button exits the app.
 */
export type Route =
  | { name: 'home' }
  | { name: 'users' }
  | { name: 'settings' }
  | { name: 'dictation'; language: DictationLanguage; replay?: Replay }
  | { name: 'word-book'; language: DictationLanguage }
  | { name: 'error-book' }
  | { name: 'records' };

/** "再听一遍" from the learning record: the words of a past dictation, in their reading order. */
export interface Replay {
  words: { text: string; translation?: string }[];
  readMode: ReadMode;
}

interface Navigation {
  route: Route;
  depth: number;
  push(route: Route): void;
  /** Change the current page's route without leaving it (the page stays mounted when its key does not change). */
  replace(route: Route): void;
  /** Back to the home page alone, e.g. when the data folder is closed. */
  reset(): void;
  /** Returns false when there was nothing to go back to. */
  back(): boolean;
  /** Close the current page, ignoring back handlers (after the page asked the user itself). */
  pop(): void;
  /** Returns an unregister function. */
  onBack(handler: () => void): () => void;
}

const Context = createContext<Navigation | null>(null);

export function useNavigation(): Navigation {
  const navigation = useContext(Context);
  if (!navigation) throw new Error('useNavigation outside NavigationProvider');
  return navigation;
}

/** Intercept back while `active`, e.g. to close a sheet or confirm leaving. */
export function useBackHandler(active: boolean, handler: () => void) {
  const { onBack } = useNavigation();
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => (active ? onBack(() => latest.current()) : undefined), [active, onBack]);
}

export function NavigationProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Route[]>([{ name: 'home' }]);
  const depth = useRef(1);
  const handlers = useRef<(() => void)[]>([]);
  depth.current = stack.length;

  const push = useCallback((route: Route) => setStack(current => [...current, route]), []);
  const replace = useCallback((route: Route) => setStack(current => [...current.slice(0, -1), route]), []);
  const reset = useCallback(() => setStack([{ name: 'home' }]), []);
  const pop = useCallback(() => setStack(current => (current.length > 1 ? current.slice(0, -1) : current)), []);
  const onBack = useCallback((handler: () => void) => {
    handlers.current.push(handler);
    return () => { handlers.current = handlers.current.filter(item => item !== handler); };
  }, []);
  const back = useCallback(() => {
    const handler = handlers.current.at(-1);
    if (handler) { handler(); return true; }
    if (depth.current > 1) { pop(); return true; }
    return false;
  }, [pop]);

  useEffect(() => {
    if (Capacitor.isNativePlatform()) {
      const listener = NativeApp.addListener('backButton', () => { if (!back()) void NativeApp.exitApp(); });
      return () => { void listener.then(item => item.remove()); };
    }
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') back(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [back]);

  const value = useMemo<Navigation>(() => ({ route: stack.at(-1)!, depth: stack.length, push, replace, reset, back, pop, onBack }), [stack, push, replace, reset, back, pop, onBack]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
