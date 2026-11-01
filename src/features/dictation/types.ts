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
  /**
   * The id the submission will be saved under, chosen when the run starts. If a submission reported as failed is
   * found in the file when the app is opened again, the draft is recognised as saved.
   * Missing in older drafts.
   */
  sessionId?: string;
  /**
   * A submission failed in a way that cannot be checked in this run (SaveStoppedError: the file is not written again
   * until the app is opened again). While the file cannot be written, the draft is kept as it is (marks, no
   * discarding); once it is readable again, either the submission is in it (the draft is dropped) or it can be
   * submitted again. Development builds stored a string here; any truthy value means the same.
   */
  saveFailed?: boolean;
}

let counter = 0;
export const planWord = (text: string, translation = ''): PlanWord => ({ key: `k${Date.now().toString(36)}${(counter++).toString(36)}`, text, translation });

/**
 * Words typed in a box: one per line, or separated by commas, 、 or ；. Chinese words are also separated by spaces
 * ("春天 温暖"); English keeps them, because a phrase like "take care" is one item.
 */
export const splitWords = (text: string, language: DictationLanguage): string[] =>
  text.split(language === 'chinese' ? /[\r\n，,、；;\s]+/ : /[\r\n，,、；;]+/).map(item => item.trim()).filter(Boolean);

export const draftKey = (userId: string, language: DictationLanguage) => `dictation:${userId}:${language}`;

export const languageTitle = (language: DictationLanguage) => (language === 'chinese' ? '语文听写' : '英语听写');
