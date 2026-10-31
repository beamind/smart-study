import { Preferences } from '@capacitor/preferences';

/**
 * A random id for this installation, sent to the trial proxy (docs/decisions/004) so it can count the trial
 * per device. Kept in the app's private storage like the API key; it says nothing about the user.
 */
const KEY = 'trialDeviceId';

export async function trialDeviceId(): Promise<string> {
  const { value } = await Preferences.get({ key: KEY });
  if (value) return value;
  const id = crypto.randomUUID();
  await Preferences.set({ key: KEY, value: id });
  return id;
}
