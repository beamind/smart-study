import { Preferences } from '@capacitor/preferences';

/**
 * A random id for this installation, sent to the trial proxy (docs/decisions/004) so it can count the trial
 * per device. Kept in the app's private storage like the API key; it says nothing about the user.
 */
const KEY = 'trialDeviceId';

/** Shared by calls made at the same time (举一反三 sends three at once), so a new install gets one id, not three. */
let pending: Promise<string> | null = null;

export function trialDeviceId(): Promise<string> {
  pending ??= (async () => {
    const { value } = await Preferences.get({ key: KEY });
    if (value) return value;
    const id = crypto.randomUUID();
    await Preferences.set({ key: KEY, value: id });
    return id;
  })().catch(error => { pending = null; throw error; });
  return pending;
}
