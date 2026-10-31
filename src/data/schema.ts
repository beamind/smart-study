import type { IsoTime } from '../core/time';

/** Data file types. The on-disk contract is documented in docs/data-contract.md. */

export const SCHEMAS = {
  app: 'smart-study.app/1',
  settings: 'smart-study.settings/1',
  dictation: 'smart-study.dictation/1',
  errorBook: 'smart-study.error-book/1',
} as const;

export interface User {
  id: string;
  name: string;
  createdAt: IsoTime;
}

export interface AppFile {
  schema: typeof SCHEMAS.app;
  users: User[];
  currentUserId: string | null;
}

/** One answer to one word or question. Its time and kind are those of the session it belongs to. */
export interface Attempt {
  sessionId: string;
  correct: boolean;
}

export type DictationLanguage = 'chinese' | 'english';
/** 'legacy': early records whose kind is unknown. */
export const DICTATION_SOURCES = ['dictation', 'review', 'added', 'legacy'] as const;
export type DictationSource = (typeof DICTATION_SOURCES)[number];
/** English only: read the Chinese translation (write English) or read the English word itself. */
export type ReadMode = 'translation' | 'original';

export interface DictationWord {
  id: string;
  text: string;
  translation?: string;
  createdAt: IsoTime;
  attempts: Attempt[];
}

export interface DictationSession {
  id: string;
  kind: DictationSource;
  at: IsoTime;
  /** In reading order, so the same list can be dictated again. */
  wordIds: string[];
  readMode?: ReadMode;
}

export interface DictationFile {
  schema: typeof SCHEMAS.dictation;
  language: DictationLanguage;
  sessions: DictationSession[];
  words: DictationWord[];
}

/** 'legacy': early records whose kind is unknown. */
export const ERROR_BOOK_SOURCES = ['capture', 'practice', 'variation', 'legacy'] as const;
export type ErrorBookSource = (typeof ERROR_BOOK_SOURCES)[number];
export const QUESTION_ORIGINS = ['photo', 'manual', 'generated'] as const;
export type QuestionOrigin = (typeof QUESTION_ORIGINS)[number];

export interface Question {
  id: string;
  /** Keeps line breaks; underlined letters are marked as __x__. */
  text: string;
  answer: string;
  origin: QuestionOrigin;
  sourceQuestionId?: string;
  createdAt: IsoTime;
  attempts: Attempt[];
}

export interface ErrorBookSession {
  id: string;
  kind: ErrorBookSource;
  at: IsoTime;
  questionIds: string[];
}

export interface ErrorBookFile {
  schema: typeof SCHEMAS.errorBook;
  sessions: ErrorBookSession[];
  questions: Question[];
}

export const PROMPT_KEYS = [
  'chineseDictation', 'englishDictation', 'translation',
  'errorBookRecognition', 'errorBookUnderline', 'questionGeneration', 'questionGenerationSystem',
] as const;
export type PromptKey = (typeof PROMPT_KEYS)[number];

export interface Settings {
  schema: typeof SCHEMAS.settings;
  tts: { rate: number; pitch: number; repeatGapMs: { chinese: number; english: number } };
  /** 0 means no time limit. */
  timeLimitSeconds: { dictation: number; errorBook: number };
  models: {
    vision: string;
    translation: string;
    generation: string;
    generationTemperature: number;
    requestTimeoutSeconds: number;
    maxAttempts: number;
  };
  /** Only prompts that differ from the built-in defaults. */
  prompts: Partial<Record<PromptKey, string>>;
}

/** Relative paths inside the authorised smartstudy folder. */
export const paths = {
  app: 'data/app.json',
  settings: 'data/settings.json',
  dictation: (userId: string, language: DictationLanguage) => `data/users/${userId}/dictation-${language}.json`,
  errorBook: (userId: string) => `data/users/${userId}/error-book.json`,
};
