import { Preferences } from '@capacitor/preferences';

/**
 * The DashScope API key is kept in the app's private storage (Android SharedPreferences,
 * browser localStorage). It is never written to the shared data folder or the repository.
 */
const KEY = 'dashscopeApiKey';

export async function getApiKey(): Promise<string> {
  return (await Preferences.get({ key: KEY })).value ?? '';
}

export async function setApiKey(value: string): Promise<void> {
  const trimmed = value.trim();
  if (trimmed) await Preferences.set({ key: KEY, value: trimmed });
  else await Preferences.remove({ key: KEY });
}

export const maskApiKey = (value: string) => value.length <= 8 ? '已填写' : `${value.slice(0, 3)}…${value.slice(-4)}`;
