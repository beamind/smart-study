import { wordKey } from '../data/normalize';
import type { DictationLanguage } from '../data/schema';

/** How many opening brackets extractJson tries before giving up. */
const MAX_STARTS = 20;

/**
 * Parsing model output. Models often wrap JSON in ```json fences or add a sentence before it,
 * so we look for the JSON inside the text instead of failing (and paying for a retry).
 * `accept` skips parses of the wrong shape, e.g. "第[1]题：{...}" when an object is expected.
 */
export function extractJson(text: string, accept: (value: unknown) => boolean = () => true): unknown {
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(text);
  const candidates = [fenced?.[1], text];
  /** The accepted value; 'rejected' when the text is JSON of the wrong shape; null when it is not JSON. */
  const tryParse = (source: string): { value: unknown } | 'rejected' | null => {
    const escaped = escapeControlsInStrings(source);
    let parsed = false;
    for (const attempt of [source, escaped, withoutTrailingCommas(escaped)]) {
      try {
        const value = JSON.parse(attempt);
        parsed = true;
        if (accept(value)) return { value };
      } catch { /* next */ }
    }
    return parsed ? 'rejected' : null;
  };
  for (const candidate of candidates) {
    if (!candidate) continue;
    const trimmed = candidate.trim();
    const whole = tryParse(trimmed);
    if (whole && whole !== 'rejected') return whole.value;
    // Try the bracket that opens first: an object reply may contain an inner array, and the other way round.
    // Later brackets are tried too, because text before the JSON may contain one ("第[1]题：{...}").
    // Each start is tried to its matching close bracket first, so a note after the JSON ("注：第[2]题…") is left out,
    // and then to the last close bracket of its kind.
    // A bracket pair that is broken JSON (a list with an unescaped quote) is not searched inside: taking one of its
    // items would silently drop the others, while no result asks for a retry. When the first bracket never closes
    // (a reply cut off, or quotes that do not pair up), everything after it counts as that broken pair.
    const lastClose = { '[': trimmed.lastIndexOf(']'), '{': trimmed.lastIndexOf('}') };
    const broken: [number, number][] = [];
    let tries = 0;
    let first = true;
    for (let start = 0; start < trimmed.length && tries < MAX_STARTS; start++) {
      const open = trimmed[start];
      if (open !== '[' && open !== '{') continue;
      const isFirst = first;
      first = false;
      if (broken.some(([from, to]) => start > from && start < to)) continue;
      const matching = matchingClose(trimmed, start);
      const ends = [...new Set([matching, lastClose[open]])].filter(end => end > start);
      let parsed = false;
      if (ends.length) tries++;
      for (const end of ends) {
        const part = tryParse(trimmed.slice(start, end + 1));
        if (part && part !== 'rejected') return part.value;
        if (part) parsed = true;
        else if (end === matching) broken.push([start, end]);
      }
      if (!parsed && matching < 0 && isFirst) broken.push([start, trimmed.length]);
    }
  }
  return undefined;
}

/** Index of the bracket that closes the one at start, ignoring brackets inside strings; -1 when there is none. */
function matchingClose(text: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < text.length; index++) {
    const char = text[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
    } else if (char === '"') inString = true;
    else if (char === '[' || char === '{') depth++;
    else if (char === ']' || char === '}') { depth--; if (depth === 0) return index; }
  }
  return -1;
}

/** Drop a comma right before a closing bracket ("["a", "b",]"), outside strings. */
function withoutTrailingCommas(source: string): string {
  let result = '';
  let inString = false;
  let escaped = false;
  for (let index = 0; index < source.length; index++) {
    const char = source[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
    } else if (char === '"') inString = true;
    else if (char === ',' && /^\s*[\]}]/.test(source.slice(index + 1))) continue;
    result += char;
  }
  return result;
}

/**
 * Models sometimes put real line breaks or tabs inside JSON strings, which JSON.parse rejects.
 * Escape control characters that appear inside string literals; everything else is left as it is.
 */
function escapeControlsInStrings(source: string): string {
  let result = '';
  let inString = false;
  let escaped = false;
  for (const char of source) {
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      else if (char === '\n') { result += '\\n'; continue; }
      else if (char === '\r') { result += '\\r'; continue; }
      else if (char === '\t') { result += '\\t'; continue; }
    } else if (char === '"') inString = true;
    result += char;
  }
  return result;
}

/** The list in a reply: the value itself, or the first array inside an object such as {"words": [...]}. */
export function listIn(value: unknown): unknown[] | undefined {
  if (Array.isArray(value)) return value;
  if (value && typeof value === 'object') return Object.values(value).find(item => Array.isArray(item)) as unknown[] | undefined;
  return undefined;
}

const han = /[\u4e00-\u9fa5]/;
const latin = /[A-Za-z]/;
const digit = /\d/;
const englishWord = /^[A-Za-z][A-Za-z\s\-']*[A-Za-z]$|^[A-Za-z]$/;
const trimQuotes = (value: string) => value.replace(/^["“”'‘’《》（）()\[\]]+|["“”'‘’《》（）()\[\]]+$/g, '').trim();

/**
 * English: typographic apostrophes become ', sentence punctuation (also full-width), slashes and brackets become spaces
 * ("How are you?" → "How are you", "his/her" → "his her", "(be) good at" → "be good at").
 */
const cleanEnglish = (value: string) => trimQuotes(value.replace(/[’‘]/g, "'").replace(/[/[\]()（）.?!,;:"“”？！，。；：、…]+/g, ' ')).replace(/\s+/g, ' ').trim();
/**
 * Chinese: pinyin in brackets after a word is dropped ("春天(chūn tiān)" → "春天"), and so is punctuation around it
 * ("春天，" → "春天"); "（秋天）" keeps its word.
 */
const cleanChinese = (value: string) => trimQuotes(value.replace(/[(（][^)）]*[A-Za-zāáǎàōóǒòēéěèīíǐìūúǔùǖǘǚǜü][^)）]*[)）]/g, '').trim())
  .replace(/^[\p{P}\s]+|[\p{P}\s]+$/gu, '');

/** Words for dictation from a model reply: language filters, code-fence handling and in-order de-duplication. */
export function parseWords(reply: string, language: DictationLanguage): string[] {
  // A reply like {"words": [...]} is accepted too; a list of numbers ("第[1]组") is not the answer.
  const list = listIn(extractJson(reply, value => {
    const items = listIn(value);
    return !!items && (items.length === 0 || items.some(item => typeof item === 'string'));
  }));
  // A reply that tried to be JSON but is broken gives no words, so the user is asked to try again: splitting it
  // as plain text would cut phrases and sentences apart. English plain text is one word or phrase per line or item.
  const looksLikeJson = /[[{]/.test(reply);
  const raw = list
    ? list.filter((item): item is string => typeof item === 'string')
    : looksLikeJson ? []
    : language === 'english' ? reply.split(/[\r\n，,；;、]+/)
    : reply.split(/[\s，。、；：！？,.;:!?\[\]"“”]+/).filter(part => part.length <= 30);
  // A number kept from a numbered list ("1. 春天", "(2) apple") is not part of the word.
  const cleaned = raw.map(item => item.trim().replace(/^(?:\d+\s*[.、．)）:：]|[(（]\d+[)）])\s*/, '')).filter(Boolean).filter(item => item.length <= 200)
    .map(item => (language === 'english' ? cleanEnglish(item) : cleanChinese(item)))
    .filter(item => {
      if (!item) return false;
      if (language === 'chinese') return han.test(item) && !latin.test(item) && !digit.test(item) && item.length <= 10;
      return !han.test(item) && !digit.test(item) && englishWord.test(item);
    });
  const seen = new Set<string>();
  return cleaned.filter(item => { const key = wordKey(item, language); if (seen.has(key)) return false; seen.add(key); return true; });
}

/** A translation reply: first line, without quotes, a leading "译文：" or a note in brackets at the end ("苹果（复数）"). */
export function cleanTranslation(reply: string): string {
  const line = reply.split('\n').map(item => item.trim()).find(Boolean) ?? '';
  const text = line.replace(/^(译文|中文|翻译)[:：]\s*/, '');
  const withoutNote = text.replace(/\s*[（(][^（）()]*[）)]\s*$/, '');
  return trimQuotes(withoutNote.trim() ? withoutNote : text);
}

export interface QuestionAnswer { text: string; answer: string }

const scalar = (value: unknown) => (typeof value === 'string' ? value : typeof value === 'number' ? String(value) : '');
/** A text field; a list (e.g. one answer per blank: ["goes", "comes"]) is joined with "; ". */
const field = (value: unknown) => (Array.isArray(value) ? value.map(scalar).filter(item => item.trim()).join('; ') : scalar(value));
/** Real line breaks; some replies still contain a literal "\n" after JSON parsing. */
const lines = (value: string) => value.replace(/\\n/g, '\n').replace(/\r\n?/g, '\n').split('\n').map(line => line.trimEnd()).join('\n').trim();

function toQuestion(item: unknown): QuestionAnswer | null {
  if (!item || typeof item !== 'object') return null;
  const record = item as Record<string, unknown>;
  // Options in a separate field belong to the question text: ["A. cat", "B. dog"], [{"label": "A", "text": "cat"}] or {"A": "cat"}.
  const options = Array.isArray(record.options) ? record.options.map(option)
    : record.options && typeof record.options === 'object' ? Object.entries(record.options).map(([label, text]) => [label, field(text)].filter(Boolean).join('. '))
    : [];
  const text = lines([field(record.question ?? record.text), ...options].join('\n'));
  if (!text) return null;
  return { text, answer: lines(field(record.answer)) };
}

/** One option as text: "A. cat" stays, {"label": "A", "text": "cat"} becomes "A. cat". */
function option(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return field(value);
  const record = value as Record<string, unknown>;
  return [field(record.label ?? record.key ?? record.option), field(record.text ?? record.content ?? record.value)].filter(Boolean).join('. ');
}

/** A question: {question, …}, or {text, answer}. An option such as {label, text} has no answer, so it is not one. */
const isQuestionLike = (value: unknown) => !!value && typeof value === 'object' && !Array.isArray(value)
  && ('question' in value || ('text' in value && 'answer' in value));

/** Error-book questions from a reply: an array of {question, answer}, one such object, or an object holding the array. */
export function parseQuestions(reply: string): QuestionAnswer[] {
  const json = extractJson(reply, value => isQuestionLike(value) || !!listIn(value)?.some(isQuestionLike));
  // {"text": "说明", "questions": [...]} holds a list; a question with an answer is one question, whatever lists it has.
  const list = listIn(json);
  const single = isQuestionLike(json) && 'answer' in (json as object);
  const items = !single && list?.some(isQuestionLike) ? list : isQuestionLike(json) ? [json] : list ?? [];
  return items.map(toQuestion).filter((item): item is QuestionAnswer => !!item);
}

/** One generated question (举一反三); null when the reply has no usable question and answer. */
export function parseGenerated(reply: string): QuestionAnswer | null {
  const [first] = parseQuestions(reply);
  return first && first.answer ? first : null;
}
