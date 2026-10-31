import { useEffect, useState } from 'react';
import { useWorkspace } from '../app/workspace';
import { emptyDictationFile, emptyErrorBookFile } from '../data/defaults';
import { paths, type DictationFile, type DictationLanguage, type ErrorBookFile } from '../data/schema';
import { checkDictationFile, checkErrorBookFile, type Checked } from '../data/validate';
import type { DataCache, Entry } from '../storage/dataCache';

const dictationArgs = (userId: string, language: DictationLanguage) =>
  [paths.dictation(userId, language), checkDictationFile, () => emptyDictationFile(language)] as const;
const errorBookArgs = (userId: string) => [paths.errorBook(userId), checkErrorBookFile, emptyErrorBookFile] as const;

/** One data file through the cache; re-renders when the file changes. */
export function useCachedFile<T>(cache: DataCache, path: string | null, check: (input: unknown) => Checked<T>, empty: () => T) {
  const [entry, setEntry] = useState<Entry<T> | undefined>(() => (path ? cache.peek<T>(path) : undefined));
  useEffect(() => {
    if (!path) return;
    let active = true;
    setEntry(cache.peek<T>(path));
    const refresh = () => { void cache.get(path, check, empty).then(value => { if (active) setEntry(value); }); };
    refresh();
    const unsubscribe = cache.subscribe(path, refresh);
    return () => { active = false; unsubscribe(); };
    // check/empty are module-level functions for a given path
  }, [cache, path]);
  return entry;
}

/** The current user's dictation file for one language. */
export function useDictationFile(language: DictationLanguage) {
  const { cache, currentUser } = useWorkspace();
  const args = currentUser ? dictationArgs(currentUser.id, language) : null;
  const entry = useCachedFile<DictationFile>(cache, args?.[0] ?? null, checkDictationFile, () => emptyDictationFile(language));
  const update = <R,>(change: (file: DictationFile) => { value: DictationFile; result: R }) => {
    if (!args) throw new Error('还没有选择用户');
    return cache.update(...args, change);
  };
  return { entry, update };
}

/** The current user's error book. */
export function useErrorBookFile() {
  const { cache, currentUser } = useWorkspace();
  const args = currentUser ? errorBookArgs(currentUser.id) : null;
  const entry = useCachedFile<ErrorBookFile>(cache, args?.[0] ?? null, checkErrorBookFile, emptyErrorBookFile);
  const update = <R,>(change: (file: ErrorBookFile) => { value: ErrorBookFile; result: R }) => {
    if (!args) throw new Error('还没有选择用户');
    return cache.update(...args, change);
  };
  return { entry, update };
}
