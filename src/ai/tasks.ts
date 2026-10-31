import type { DictationLanguage, Settings } from '../data/schema';
import { blanksNotMarks, markUnderlines, stripUnderlines, wordPattern, type UnderlineHit } from '../domain/underline';
import { AiError, chat, type ChatRequest } from './dashscope';
import { cleanTranslation, extractJson, listIn, parseGenerated, parseQuestions, parseWords, type QuestionAnswer } from './parse';
import { prompt } from './prompts';

export type { QuestionAnswer } from './parse';

/** Everything the app asks the model for. Pages use this interface; tests use fakeAi. */
export interface AiService {
  /** Words for dictation from a JPEG data URL. */
  recognizeWords(imageDataUrl: string, language: DictationLanguage, signal?: AbortSignal): Promise<string[]>;
  /** Chinese translation of an English word or phrase. */
  translate(text: string, signal?: AbortSignal): Promise<string>;
  /** Error-book questions in a photo, with correct answers given by the model (not the child's answers on the paper). */
  recognizeQuestions(imageDataUrl: string, signal?: AbortSignal): Promise<QuestionAnswer[]>;
  /**
   * Underlined letters and words in the same photo, marked as __x__ in each question's text.
   * The model only lists what is underlined; the text itself is never rewritten.
   */
  recognizeUnderlines(imageDataUrl: string, questions: QuestionAnswer[], signal?: AbortSignal): Promise<UnderlineResult>;
  /** A new question on the same point as `source` (举一反三). */
  generateQuestion(source: QuestionAnswer, signal?: AbortSignal): Promise<QuestionAnswer>;
}

/** Fixed output rules appended to the editable "role and task" part, so replies stay parseable whatever the user edits. */
const OUTPUT_RULES: Record<DictationLanguage, string> = {
  chinese: [
    '输出要求（非常重要）：',
    '1) 仅输出 JSON 数组，例如 ["春天","认真学习"]；',
    '2) 只输出中文词语/短语（必须包含汉字），不要输出英文、数字、拼音、音标、解释、题目说明、页码、日期等；',
    '3) 去掉序号与标点符号（如 1. 、（ ） 等），不要输出空字符串；',
    '4) 保持词语原顺序（从上到下、从左到右），不要自行编造。',
  ].join('\n'),
  english: [
    '输出要求（非常重要）：',
    '1) 仅输出 JSON 数组，例如 ["apple","take care"]；',
    "2) 只输出英文内容（A-Z/a-z，可含空格、连字符-、撇号'），不要输出中文、数字、音标(/ˈ.../)、释义、例句、题目说明、页码等；",
    '3) 去掉序号与标点符号（如 1. 、( ) 等），不要输出空字符串；',
    '4) 根据语义保持句子、短语的完整性，不要都拆成单词；',
    '5) 保持原顺序（从上到下、从左到右），不要自行编造。',
  ].join('\n'),
};

export function dictationPrompt(settings: Settings, language: DictationLanguage): string {
  return `${prompt(settings, language === 'chinese' ? 'chineseDictation' : 'englishDictation')}\n\n${OUTPUT_RULES[language]}`;
}

/** Fixed output rules appended to the editable underline prompt, followed by the questions. */
const UNDERLINE_OUTPUT = [
  '输出格式：只输出 JSON 数组，每个元素是 {"question": 题目编号, "word": "画线所在的单词或短语", "underlined": "画线的部分"}。',
  'question 用下面列出的编号（题目1 写 1，题目2 写 2），不要用照片上印的题号。',
  '例如：[{"question": 2, "word": "cake", "underlined": "a"}, {"question": 3, "word": "by ship", "underlined": "by ship"}]',
  '没有任何下划线时输出 []。',
].join('\n');

export function underlinePrompt(settings: Settings, questions: QuestionAnswer[]): string {
  const texts = questions.map((question, index) => `题目${index + 1}：\n${stripUnderlines(question.text)}`).join('\n\n');
  return `${prompt(settings, 'errorBookUnderline')}\n\n${UNDERLINE_OUTPUT}\n\n${texts}`;
}

export interface NumberedHit extends UnderlineHit {
  /** 0-based question index from the reply; models sometimes use the number printed on the paper instead. */
  question: number | null;
}

const isHit = (value: unknown) => !!value && typeof value === 'object' && !Array.isArray(value) && 'underlined' in value;

/** Underlines listed in a reply: an array, an object holding one (e.g. {"underlines": [...]}), or a single underline. */
export function parseUnderlineHits(reply: string): NumberedHit[] {
  const value = extractJson(reply, item => isHit(item) || !!listIn(item)?.every(entry => !!entry && typeof entry === 'object'));
  const json = isHit(value) ? [value] : listIn(value);
  if (!json) throw new AiError('bad-response', '划线识别的结果无法解析');
  return json.flatMap(item => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const underlined = typeof record.underlined === 'string' ? record.underlined : '';
    if (!underlined.trim()) return [];
    const number = Number(String(record.question ?? '').replace(/\D+/g, '')) - 1;
    return [{ word: typeof record.word === 'string' ? record.word : underlined, underlined, question: Number.isInteger(number) && number >= 0 ? number : null }];
  });
}

const contains = (text: string, word: string) =>
  new RegExp(`(?<![A-Za-z0-9])${wordPattern(word)}(?![A-Za-z0-9])`, 'i').test(stripUnderlines(text));

export interface UnderlineResult {
  /** Per question: the text with marks (unchanged when the model saw no underline in it). */
  texts: string[];
  marks: number;
  /** Underlines the model named but that are not in the question text. */
  missed: number;
}

/** Fixed after the (editable) generation prompt: blanks and underlines must be written so the app can tell them apart. */
const GENERATION_BLANKS = '填空的横线至少写三个下划线（___），不要只写两个。';

export function generationPrompt(settings: Settings, source: QuestionAnswer): string {
  const marks = stripUnderlines(source.text) !== source.text
    ? '\n（题目中用 __ 包起来的部分是原题里画线的部分；新题里画线的部分也这样写，例如 __word__）'
    : '';
  return `${prompt(settings, 'questionGeneration')}\n\n原始题目：${source.text}${source.answer ? `\n原题答案：${source.answer}` : ''}${marks}\n${GENERATION_BLANKS}`;
}

/**
 * Put the listed underlines into the questions. A hit goes to the question with its number when that
 * question contains the word, otherwise to the first question that does.
 */
export function applyHits(questions: QuestionAnswer[], hits: NumberedHit[]): UnderlineResult {
  const groups: UnderlineHit[][] = questions.map(() => []);
  let missed = 0;
  for (const hit of hits) {
    const word = hit.word.trim() && hit.word.toLowerCase().includes(hit.underlined.trim().toLowerCase()) ? hit.word : hit.underlined;
    const numbered = hit.question !== null && hit.question < questions.length && contains(questions[hit.question].text, word) ? hit.question : -1;
    const target = numbered >= 0 ? numbered : questions.findIndex(question => contains(question.text, word));
    if (target < 0) missed++;
    else groups[target].push(hit);
  }
  let marks = 0;
  const texts = questions.map((question, index) => {
    if (!groups[index].length) return question.text;
    const result = markUnderlines(question.text, groups[index]);
    marks += result.marks;
    missed += result.missed;
    return result.text;
  });
  return { texts, marks, missed };
}

/**
 * One model call, described by what it is for. The request (model, prompt) is built from it by modelRequest;
 * the app does that itself with the user's key, the trial proxy (docs/decisions/004) does it on the server.
 */
export type ModelTask =
  | { task: 'words'; image: string; language: DictationLanguage }
  | { task: 'questions'; image: string }
  | { task: 'underlines'; image: string; questions: QuestionAnswer[] }
  | { task: 'translate'; text: string }
  | { task: 'generate'; source: QuestionAnswer };

/** Sends one task and returns the model's reply text. */
export type ModelCall = (task: ModelTask, signal?: AbortSignal) => Promise<string>;

/** Longest reply each task may need; the trial proxy caps replies at this, so one request cannot cost much. */
export const MAX_REPLY_TOKENS: Record<ModelTask['task'], number> = { words: 1500, questions: 4000, underlines: 1500, translate: 200, generate: 1000 };

/** Model, messages and options for a task. Deep thinking is off: these tasks do not need it and are 5–20× slower with it. */
export function modelRequest(settings: Settings, task: ModelTask): Pick<ChatRequest, 'model' | 'messages' | 'temperature' | 'thinking'> {
  const vision = (image: string, text: string) => ({
    model: settings.models.vision, thinking: false,
    messages: [{ role: 'user' as const, content: [{ type: 'image_url' as const, image_url: { url: image } }, { type: 'text' as const, text }] }],
  });
  switch (task.task) {
    case 'words': return vision(task.image, dictationPrompt(settings, task.language));
    case 'questions': return vision(task.image, prompt(settings, 'errorBookRecognition'));
    case 'underlines': return vision(task.image, underlinePrompt(settings, task.questions));
    case 'translate':
      return { model: settings.models.translation, thinking: false, messages: [{ role: 'user', content: `${prompt(settings, 'translation')}\n英文：${task.text}` }] };
    case 'generate':
      return {
        model: settings.models.generation, thinking: false, temperature: settings.models.generationTemperature,
        messages: [{ role: 'system', content: prompt(settings, 'questionGenerationSystem') }, { role: 'user', content: generationPrompt(settings, task.source) }],
      };
  }
}

/** Calls DashScope directly with the user's own key (an empty key fails with 'no-key'). */
export function directCall(settings: Settings, apiKey: string): ModelCall {
  return (task, signal) => chat({
    ...modelRequest(settings, task), apiKey, signal,
    timeoutMs: settings.models.requestTimeoutSeconds * 1000, maxAttempts: settings.models.maxAttempts,
  });
}

/** The app's model features on top of a ModelCall: the same parsing for the user's own key and for the trial. */
export function createAi(call: ModelCall): AiService {
  return {
    async recognizeWords(imageDataUrl, language, signal) {
      const words = parseWords(await call({ task: 'words', image: imageDataUrl, language }, signal), language);
      if (!words.length) throw new AiError('bad-response', '没有从照片里识别出词语，可以换一张更清楚的照片，或手动输入');
      return words;
    },
    async translate(text, signal) {
      const translation = cleanTranslation(await call({ task: 'translate', text }, signal));
      if (!translation) throw new AiError('bad-response', '翻译结果为空');
      return translation;
    },
    async recognizeQuestions(imageDataUrl, signal) {
      const questions = parseQuestions(await call({ task: 'questions', image: imageDataUrl }, signal))
        .map(question => ({ ...question, text: blanksNotMarks(question.text) }));
      if (!questions.length) throw new AiError('bad-response', '没有从照片里识别出题目，可以换一张更清楚的照片，或手动输入');
      return questions;
    },
    async recognizeUnderlines(imageDataUrl, questions, signal) {
      if (!questions.length) return { texts: [], marks: 0, missed: 0 };
      return applyHits(questions, parseUnderlineHits(await call({ task: 'underlines', image: imageDataUrl, questions }, signal)));
    },
    async generateQuestion(source, signal) {
      const generated = parseGenerated(await call({ task: 'generate', source }, signal));
      if (!generated) throw new AiError('bad-response', '生成的题目缺少题干或答案');
      // Without underlines in the source, every "__" in the new question is a blank ("w__t__r"), not a mark.
      return stripUnderlines(source.text) === source.text ? { ...generated, text: blanksNotMarks(generated.text) } : generated;
    },
  };
}

const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
const failedOnce = new Set<string>();

/** `?ai=fake`: deterministic answers for automated tests, no network and no cost. */
export const fakeAi: AiService = {
  async recognizeWords(_image, language) {
    await wait(300);
    return language === 'chinese' ? ['春天', '温暖', '柳树', '发芽', '燕子'] : ['apple', 'take care', 'vegetable', 'chef'];
  },
  async translate(text) {
    await wait(100);
    return `译:${text}`;
  },
  async recognizeQuestions() {
    await wait(300);
    return [
      { text: 'It was foggy yesterday. (对画线部分提问)', answer: 'What was the weather like yesterday?' },
      { text: 'Which word has a different sound?\nA. cake B. map C. name', answer: 'B' },
      { text: 'Tom ______ (go) to school by bike every day.', answer: 'goes' },
    ];
  },
  async recognizeUnderlines(_image, questions) {
    await wait(300);
    return applyHits(questions, /\bcake\b/.test(questions.map(question => question.text).join('\n'))
      ? [{ word: 'cake', underlined: 'a', question: 2 }, { word: 'map', underlined: 'a', question: 2 }, { word: 'name', underlined: 'a', question: 2 }]
      : []);
  },
  /** Questions containing "[fail-once]" fail on the first try, to test retrying. */
  async generateQuestion(source, signal) {
    await wait(200);
    if (signal?.aborted) throw new AiError('cancelled', '已取消');
    if (source.text.includes('[fail-once]') && !failedOnce.has(source.text)) {
      failedOnce.add(source.text);
      throw new AiError('server', '千问服务暂时出错（500）');
    }
    return { text: `新题：${stripUnderlines(source.text).replace(' [fail-once]', '')}`, answer: source.answer };
  },
};

export const isFakeAi = () => typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('ai') === 'fake';
