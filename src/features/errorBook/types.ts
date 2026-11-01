import type { ErrorBookSource } from '../../data/schema';

/** A question on its way through practice or 举一反三. `key` keeps React lists stable. */
export interface PracticeItem {
  key: string;
  text: string;
  answer: string;
  /** Set for practice: the question in the book. */
  questionId?: string;
  /** Set for 举一反三: the original question the new one was made from. */
  sourceQuestionId?: string;
}

export type RunKind = 'practice' | 'variation';

/** Practice in progress. Saved as a draft after every step so it can be resumed. */
export interface PracticeRun {
  kind: RunKind;
  items: PracticeItem[];
  index: number;
  phase: 'running' | 'answers';
  /** Keys of the items answered wrong. */
  wrong: string[];
  startedAt: string;
  /**
   * The id the submission will be saved under, chosen when the run starts. If a save was interrupted and later
   * restored from the temp file, the session is already in the file: the draft is then recognised as saved.
   * Missing in drafts from before 2.0.0's trial release.
   */
  sessionId?: string;
}

/** A recognised or typed question before it is saved. */
export interface CaptureItem {
  key: string;
  text: string;
  answer: string;
  checked: boolean;
  /** Set when the user changed the tick, so the duplicate check no longer overrides it. */
  touched?: boolean;
  origin: 'photo' | 'manual';
}

let counter = 0;
export const newKey = () => `k${Date.now().toString(36)}${(counter++).toString(36)}`;

export const draftKey = (userId: string) => `error-book:${userId}`;

export const runTitle = (kind: RunKind) => (kind === 'practice' ? '错题练习' : '举一反三');

export const SESSION_LABELS: Record<ErrorBookSource, string> = { capture: '录入', practice: '练习', variation: '举一反三', legacy: '记录' };
