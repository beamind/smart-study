/**
 * Underlined letters in error-book questions are stored as __x__. Fill-in blanks are also
 * written with underscores ("____", "__ __"), so a mark must not touch other underscores and
 * must start and end with a visible character.
 */
const MARK = /(?<!_)__(?=[^_\s])([^_]*?[^_\s])__(?!_)/g;

export interface TextPart { text: string; underline: boolean }

export function splitUnderlines(line: string): TextPart[] {
  const parts: TextPart[] = [];
  let last = 0;
  for (const match of line.matchAll(MARK)) {
    if (match.index > last) parts.push({ text: line.slice(last, match.index), underline: false });
    parts.push({ text: match[1], underline: true });
    last = match.index + match[0].length;
  }
  if (last < line.length) parts.push({ text: line.slice(last), underline: false });
  return parts;
}

/** Plain text for previews: marks removed, lines joined. */
export const plainQuestion = (text: string) => text.split('\n').map(line => splitUnderlines(line).map(part => part.text).join('')).join(' ');

/** Text without underline marks, line breaks kept. */
export const stripUnderlines = (text: string) => text.split('\n').map(line => splitUnderlines(line).map(part => part.text).join('')).join('\n');

/**
 * Recognised question text has no marks yet (they are added by markUnderlines), but a letter-fill blank
 * written with exactly two underscores ("w__t__r") would read as a mark. Widen such blanks to three.
 */
export const blanksNotMarks = (text: string) => text.replace(/(?<!_)__(?!_)/g, '___');

/** One underline the model saw: the word or phrase it is in, and the underlined part of it. */
export interface UnderlineHit { word: string; underlined: string }

const escapeRegex = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * A regex source for a word or phrase as it appears in question text: the words may be joined by any
 * spaces except line breaks (a mark across a line break would not display), including a no-break or
 * full-width space from text recognition.
 */
export const wordPattern = (word: string) => word.trim().split(/\s+/).map(escapeRegex).join('[^\\S\\r\\n]+');

/**
 * Mark the underlines the model listed in a question's text. The model only names words and letters;
 * the marks are placed here, so the question text itself (spacing, line breaks, spelling) never changes.
 * Having the model rewrite the whole question letter by letter instead loses the spaces between words
 * and misplaces marks. Existing marks are replaced.
 * `missed` counts hits that could not be found in the text.
 */
export function markUnderlines(text: string, hits: UnderlineHit[]): { text: string; marks: number; missed: number } {
  const source = stripUnderlines(text);
  const ranges: [number, number][] = [];
  const free = (start: number, end: number) => ranges.every(([a, b]) => end <= a || start >= b);
  let missed = 0;
  for (const hit of hits) {
    let word = hit.word.trim();
    const part = hit.underlined.trim();
    if (!part) { missed++; continue; }
    if (!word || !word.toLowerCase().includes(part.toLowerCase())) word = part;
    const pattern = wordPattern(word);
    let placed = false;
    for (const flags of ['g', 'gi']) {
      for (const match of source.matchAll(new RegExp(`(?<![A-Za-z0-9])${pattern}(?![A-Za-z0-9])`, flags))) {
        // Find the underlined part with the same spacing rule, so "to school" also matches "to\u00A0school".
        const inner = new RegExp(wordPattern(part), 'i').exec(match[0]);
        const inWord = inner ? inner.index : 0;
        const length = inner ? inner[0].length : match[0].length;
        const start = match.index + inWord;
        // A mark next to a fill-in blank ("___ter") would run into its underscores and not display.
        if (!free(start, start + length) || source[start - 1] === '_' || source[start + length] === '_') continue;
        ranges.push([start, start + length]);
        placed = true;
        break;
      }
      if (placed) break;
    }
    if (!placed) missed++;
  }
  // Touching or overlapping ranges become one mark: "__s____hip__" would not read back as underlined.
  ranges.sort((a, b) => a[0] - b[0]);
  const merged: [number, number][] = [];
  for (const range of ranges) {
    const last = merged.at(-1);
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
    else merged.push([...range]);
  }
  let result = '';
  let last = 0;
  for (const [start, end] of merged) {
    result += `${source.slice(last, start)}__${source.slice(start, end)}__`;
    last = end;
  }
  result += source.slice(last);
  return { text: result, marks: merged.length, missed };
}
