import { describe, expect, it } from 'vitest';
import { blanksNotMarks, markUnderlines, plainQuestion, splitUnderlines } from './underline';

const underlined = (line: string) => splitUnderlines(line).filter(part => part.underline).map(part => part.text);

describe('splitUnderlines', () => {
  it('finds underline marks, including several words', () => {
    expect(underlined('It was __foggy__ yesterday.')).toEqual(['foggy']);
    expect(underlined('There was __a cat__ on the sofa.')).toEqual(['a cat']);
    expect(underlined('c__a__ke and n__a__me')).toEqual(['a', 'a']);
  });

  it('leaves fill-in blanks alone', () => {
    expect(underlined("Let's ______ ______ the Sports Day activities.")).toEqual([]);
    expect(underlined('My teacher ____ a lot of snacks ____ us yesterday.')).toEqual([]);
    expect(underlined('Tom ______ (go) to school.')).toEqual([]);
    expect(underlined('Let\'s __ __ __ the activities.')).toEqual([]);
  });

  it('keeps the text intact', () => {
    const line = 'My teacher ____ a lot of __snacks__.';
    expect(splitUnderlines(line).map(part => part.text).join('')).toBe('My teacher ____ a lot of snacks.');
    expect(plainQuestion('There was __a cat__.\nA. x')).toBe('There was a cat. A. x');
  });
});

describe('markUnderlines', () => {
  it('marks letters inside words and keeps the spaces between words', () => {
    const text = 'Which word has a different sound?\nA. cake B. map C. name';
    expect(markUnderlines(text, [{ word: 'cake', underlined: 'a' }, { word: 'map', underlined: 'a' }, { word: 'name', underlined: 'a' }]))
      .toEqual({ text: 'Which word has a different sound?\nA. c__a__ke B. m__a__p C. n__a__me', marks: 3, missed: 0 });
  });

  it('marks whole words and phrases, case-insensitively when needed, never inside other words', () => {
    expect(markUnderlines('There are some apples. Are you OK?', [{ word: 'are', underlined: 'are' }]).text).toBe('There __are__ some apples. Are you OK?');
    expect(markUnderlines('I care about you. Are you OK?', [{ word: 'are', underlined: 'are' }]).text).toBe('I care about you. __Are__ you OK?');
    expect(markUnderlines('I go by ship.', [{ word: 'By Ship', underlined: 'by ship' }]).text).toBe('I go __by ship__.');
    expect(markUnderlines('Look at the book.', [{ word: 'book', underlined: 'oo' }, { word: 'look', underlined: 'oo' }]).text).toBe('L__oo__k at the b__oo__k.');
  });

  it('uses the next occurrence for a repeated word and counts what it cannot find', () => {
    expect(markUnderlines('a cat and a cat', [{ word: 'cat', underlined: 'cat' }, { word: 'cat', underlined: 'cat' }]).text).toBe('a __cat__ and a __cat__');
    expect(markUnderlines('Tom ______ (go).', [{ word: 'went', underlined: 'went' }, { word: '', underlined: ' ' }])).toEqual({ text: 'Tom ______ (go).', marks: 0, missed: 2 });
  });

  it('replaces marks already in the text', () => {
    expect(markUnderlines('It was __foggy__.', [{ word: 'was', underlined: 'was' }]).text).toBe('It __was__ foggy.');
  });

  it('joins touching marks into one, so the result reads back as underlined', () => {
    const result = markUnderlines('by ship', [{ word: 'ship', underlined: 's' }, { word: 'ship', underlined: 'hip' }]);
    expect(result).toMatchObject({ text: 'by __ship__', marks: 1 });
    expect(underlined(result.text)).toEqual(['ship']);
  });

  it('does not mark a phrase across a line break', () => {
    expect(markUnderlines('Please take\ncare of it.', [{ word: 'take care', underlined: 'take care' }])).toMatchObject({ text: 'Please take\ncare of it.', marks: 0, missed: 1 });
  });

  it('matches phrases joined by no-break or full-width spaces', () => {
    expect(markUnderlines('go by\u00A0ship and look\u3000at it', [{ word: 'by ship', underlined: 'by ship' }, { word: 'look at', underlined: 'at' }]))
      .toMatchObject({ text: 'go __by\u00A0ship__ and look\u3000__at__ it', marks: 2, missed: 0 });
    expect(markUnderlines('I go to\u00A0school.', [{ word: 'go to school', underlined: 'to school' }])).toMatchObject({ text: 'I go __to\u00A0school__.', marks: 1 });
  });

  it('marks the underlined word itself when the model names a whole phrase, not letters of an earlier word', () => {
    expect(markUnderlines('There is the book on the desk.', [{ word: 'There is the book', underlined: 'the' }]).text).toBe('There is __the__ book on the desk.');
    expect(markUnderlines('I have a cat.', [{ word: 'I have a cat', underlined: 'a' }]).text).toBe('I have __a__ cat.');
    expect(markUnderlines('a cake', [{ word: 'cake', underlined: 'a' }]).text).toBe('a c__a__ke');
  });

  it('never puts a mark next to a fill-in blank', () => {
    expect(markUnderlines('Tom___ is Tom', [{ word: 'Tom', underlined: 'Tom' }])).toMatchObject({ text: 'Tom___ is __Tom__', marks: 1, missed: 0 });
    expect(markUnderlines('a ___ter', [{ word: 'ter', underlined: 'ter' }])).toMatchObject({ text: 'a ___ter', marks: 0, missed: 1 });
  });
});

describe('blanksNotMarks', () => {
  it('widens two-underscore letter blanks so they are not read as marks', () => {
    expect(blanksNotMarks('Fill in: w__t__r, b__by, Tom ____ (go)')).toBe('Fill in: w___t___r, b___by, Tom ____ (go)');
    expect(underlined(blanksNotMarks('w__t__r'))).toEqual([]);
  });
});