import { Capacitor } from '@capacitor/core';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { Preferences } from '@capacitor/preferences';
import { useEffect } from 'react';

/** Keep the screen on while `active` (dictation and practice in progress). */
export function useKeepAwake(active: boolean) {
  useEffect(() => {
    if (!active) return;
    let lock: { release(): Promise<void> } | null = null;
    let ended = false;
    if (Capacitor.isNativePlatform()) void KeepAwake.keepAwake().catch(() => undefined);
    else void (navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<{ release(): Promise<void> }> } })
      .wakeLock?.request('screen').then(value => {
        // The page may have stopped needing the lock while the request was pending.
        if (ended) void value.release().catch(() => undefined); else lock = value;
      }).catch(() => undefined);
    return () => {
      ended = true;
      if (Capacitor.isNativePlatform()) void KeepAwake.allowSleep().catch(() => undefined);
      else void lock?.release().catch(() => undefined);
    };
  }, [active]);
}

/** Called when the app goes to the background (screen off, switched app, phone call). */
export function useOnHidden(handler: () => void) {
  useEffect(() => {
    const onChange = () => { if (document.visibilityState === 'hidden') handler(); };
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, [handler]);
}

/**
 * Drafts of unfinished dictations or practice, kept in the app's private storage.
 * They are temporary working state, not records, so they do not go into the shared data folder.
 */
export const drafts = {
  async read<T>(key: string): Promise<T | null> {
    const { value } = await Preferences.get({ key: `draft:${key}` });
    if (!value) return null;
    try { return JSON.parse(value) as T; } catch { return null; }
  },
  async write(key: string, value: unknown) {
    await Preferences.set({ key: `draft:${key}`, value: JSON.stringify(value) });
  },
  async clear(key: string) {
    await Preferences.remove({ key: `draft:${key}` });
  },
  /** Every draft of every child: the way out when a draft in an unexpected shape breaks its page. */
  async clearAll() {
    const { keys } = await Preferences.keys();
    for (const key of keys) if (key.startsWith('draft:')) await Preferences.remove({ key });
  },
};
