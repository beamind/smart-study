import { SCHEMAS, type AppFile, type DictationFile, type DictationLanguage, type ErrorBookFile, type Settings } from './schema';

export const defaultSettings = (): Settings => ({
  schema: SCHEMAS.settings,
  tts: { rate: 0.5, pitch: 1.0, repeatGapMs: { chinese: 1000, english: 1500 } },
  timeLimitSeconds: { dictation: 60, errorBook: 120 },
  models: {
    // Chosen 2026-10-31 (see docs/software-plan.md, "千问模型"): newest generation, low price, all
    // test tasks correct.
    vision: 'qwen3.8-flash',
    translation: 'qwen3.8-flash',
    generation: 'qwen3.8-max',
    generationTemperature: 1.2,
    requestTimeoutSeconds: 45,
    maxAttempts: 3,
  },
  prompts: {},
});

export const emptyAppFile = (): AppFile => ({ schema: SCHEMAS.app, users: [], currentUserId: null });

export const emptyDictationFile = (language: DictationLanguage): DictationFile =>
  ({ schema: SCHEMAS.dictation, language, sessions: [], words: [] });

export const emptyErrorBookFile = (): ErrorBookFile => ({ schema: SCHEMAS.errorBook, sessions: [], questions: [] });
