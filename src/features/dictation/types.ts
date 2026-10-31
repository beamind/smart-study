import type { DictationLanguage, ReadMode } from '../../data/schema';

/** A word on its way through a dictation: confirm → read aloud → mark. `key` keeps React lists stable while editing. */
export interface PlanWord {
  key: string;
  text: string;
  translation: string;
}

export type PlanKind = 'dictation' | 'review';

export interface Plan {
  kind: PlanKind;
  words: PlanWord[];
  readMode: ReadMode;
}

/** A dictation in progress. Saved as a draft after every step so it can be resumed. */
export interface Run extends Plan {
  /** Reading order (already shuffled). */
  words: PlanWord[];
  index: number;
  phase: 'running' | 'answers';
  wrong: string[];
  startedAt: string;
}

let counter = 0;
export const planWord = (text: string, translation = ''): PlanWord => ({ key: `k${Date.now().toString(36)}${(counter++).toString(36)}`, text, translation });

export const draftKey = (userId: string, language: DictationLanguage) => `dictation:${userId}:${language}`;

export const languageTitle = (language: DictationLanguage) => (language === 'chinese' ? '语文听写' : '英语听写');
