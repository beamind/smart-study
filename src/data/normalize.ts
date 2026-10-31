import type { DictationLanguage } from './schema';

/** Key used to decide whether two dictation words are the same word. */
export function wordKey(text: string, language: DictationLanguage): string {
  const trimmed = text.trim();
  return language === 'english' ? trimmed.replace(/\s+/g, ' ').toLowerCase() : trimmed;
}

/** Key used to detect duplicate error-book questions: ignores underline marks, spacing, punctuation and case. */
export function questionKey(text: string): string {
  return text
    .replace(/__/g, '')
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}]+/gu, '');
}

/** Normalised Levenshtein similarity in [0, 1]. */
export function similarity(a: string, b: string): number {
  if (a === b) return 1;
  if (!a.length || !b.length) return 0;
  let previous = Array.from({ length: b.length + 1 }, (_, index) => index);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++) {
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    previous = current;
  }
  return 1 - previous[b.length] / Math.max(a.length, b.length);
}
