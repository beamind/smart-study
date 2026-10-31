import { describe, expect, it } from 'vitest';
import { defaultSettings } from '../data/defaults';
import { AiError, chat, type ChatRequest } from './dashscope';
import { cleanTranslation, extractJson, parseGenerated, parseQuestions, parseWords } from './parse';
import { applyHits, generationPrompt, parseUnderlineHits, underlinePrompt } from './tasks';

describe('parseWords', () => {
  it('reads a JSON array, including inside a code fence or after a sentence', () => {
    expect(parseWords('["春天","认真学习"]', 'chinese')).toEqual(['春天', '认真学习']);
    expect(parseWords('```json\n["春天", "温暖"]\n```', 'chinese')).toEqual(['春天', '温暖']);
    expect(parseWords('识别结果如下：["apple","take care"]。', 'english')).toEqual(['apple', 'take care']);
  });

  it('filters by language and removes duplicates in order', () => {
    expect(parseWords('["春天","spring","1. 夏天","春天","（秋天）"]', 'chinese')).toEqual(['春天', '夏天', '秋天']);
    expect(parseWords('["Apple","apple","苹果","/ˈæpl/","take  care","3 cats"]', 'english')).toEqual(['Apple', 'take care']);
  });

  it('falls back to splitting plain text', () => {
    expect(parseWords('春天，温暖、柳树', 'chinese')).toEqual(['春天', '温暖', '柳树']);
  });

  it('extracts objects too', () => {
    expect(extractJson('好的：\n```\n{"question":"Q","answer":"A"}\n```')).toEqual({ question: 'Q', answer: 'A' });
  });

  it('cleans translations', () => {
    expect(cleanTranslation('“保重”')).toBe('保重');
    expect(cleanTranslation('译文：保重\n（这是常用语）')).toBe('保重');
  });
});

describe('error-book replies', () => {
  it('reads questions with answers, in fences, with literal \\n and number answers', () => {
    const reply = '```json\n[{"question":"What is your name?\\\\nA. Tom  \\nB. John","answer":"A"},{"question":"1+1","answer":2},{"question":"","answer":"x"},"bad"]\n```';
    expect(parseQuestions(reply)).toEqual([{ text: 'What is your name?\nA. Tom\nB. John', answer: 'A' }, { text: '1+1', answer: '2' }]);
    expect(parseQuestions('没有题目：[]')).toEqual([]);
  });

  it('reads one generated question and rejects one without an answer', () => {
    expect(parseGenerated('好的：{"question":"Lucy ____ (like) cats.","answer":"likes"}')).toEqual({ text: 'Lucy ____ (like) cats.', answer: 'likes' });
    expect(parseGenerated('{"question":"Q"}')).toBeNull();
    expect(parseGenerated('抱歉')).toBeNull();
  });

  it('reads the underline list and puts each mark into the question that contains the word', () => {
    const questions = [{ text: 'A. cake B. map', answer: 'B' }, { text: 'He goes to school.', answer: '' }, { text: 'I go by ship.', answer: '' }];
    // Numbers as printed on the paper (4, 5) instead of 1–3, one given as "题目3", one unknown word.
    const hits = parseUnderlineHits('```json\n[{"question":4,"word":"cake","underlined":"a"},{"question":"题目3","word":"by ship","underlined":"by ship"},{"question":1,"word":"map","underlined":"a"},{"question":5,"word":"boat","underlined":"boat"},{"word":"x","underlined":""}]\n```');
    expect(hits).toHaveLength(4);
    expect(applyHits(questions, hits)).toEqual({ texts: ['A. c__a__ke B. m__a__p', 'He goes to school.', 'I go __by ship__.'], marks: 3, missed: 1 });
    expect(() => parseUnderlineHits('看不清')).toThrow(AiError);
    expect(parseUnderlineHits('[]')).toEqual([]);
  });

  it('prefers the numbered question when several contain the word', () => {
    const questions = [{ text: 'I like cake.', answer: '' }, { text: 'A cake for you.', answer: '' }];
    expect(applyHits(questions, [{ word: 'cake', underlined: 'cake', question: 1 }]).texts).toEqual(['I like cake.', 'A __cake__ for you.']);
  });

  it('builds the underline prompt from unmarked text, with the fixed output format', () => {
    const text = underlinePrompt(defaultSettings(), [{ text: 'It was __foggy__.', answer: 'What was the weather like?' }]);
    expect(text).toContain('题目1：\nIt was foggy.');
    expect(text).toContain('"underlined"');
  });
});

describe('chat', () => {
  const request: ChatRequest = { apiKey: 'k', model: 'm', messages: [{ role: 'user', content: 'hi' }], timeoutMs: 1000, maxAttempts: 3 };
  const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
  const ok = reply(200, { choices: [{ message: { content: ' 好 ' } }] });

  it('retries server errors and returns the reply', async () => {
    const responses = [reply(500, {}), reply(429, {}), ok];
    const sleeps: number[] = [];
    const result = await chat(request, { fetch: async () => responses.shift()!, sleep: async ms => { sleeps.push(ms); } });
    expect(result).toBe('好');
    expect(sleeps).toEqual([1000, 2000]);
  });

  it('does not retry auth or model errors', async () => {
    let calls = 0;
    const fail = (status: number, body: unknown) => chat(request, { fetch: async () => { calls++; return reply(status, body); }, sleep: async () => undefined });
    await expect(fail(401, {})).rejects.toMatchObject({ kind: 'auth' });
    await expect(fail(404, { error: { code: 'model_not_found' } })).rejects.toMatchObject({ kind: 'model' });
    expect(calls).toBe(2);
  });

  it('gives up after maxAttempts and requires a key', async () => {
    let calls = 0;
    await expect(chat(request, { fetch: async () => { calls++; throw new TypeError('offline'); }, sleep: async () => undefined })).rejects.toMatchObject({ kind: 'network' });
    expect(calls).toBe(3);
    await expect(chat({ ...request, apiKey: ' ' })).rejects.toBeInstanceOf(AiError);
  });

  it('sends nothing for a request that is already cancelled', async () => {
    let calls = 0;
    const controller = new AbortController();
    controller.abort();
    await expect(chat({ ...request, signal: controller.signal }, { fetch: async () => { calls++; return ok.clone(); }, sleep: async () => undefined })).rejects.toMatchObject({ kind: 'cancelled' });
    expect(calls).toBe(0);
  });

  it('reports a cancel or timeout while the reply is arriving as such, not as an empty reply', async () => {
    const controller = new AbortController();
    const slowBody = () => ({ ok: true, status: 200, text: () => new Promise<string>((_, reject) => { setTimeout(() => reject(new DOMException('aborted', 'AbortError')), 30); }) }) as unknown as Response;
    const cancelled = chat({ ...request, signal: controller.signal }, { fetch: async () => { setTimeout(() => controller.abort(), 5); return slowBody(); }, sleep: async () => undefined });
    await expect(cancelled).rejects.toMatchObject({ kind: 'cancelled' });
    let calls = 0;
    const timedOut = chat({ ...request, timeoutMs: 10, maxAttempts: 2 }, { fetch: async () => { calls++; return slowBody(); }, sleep: async () => undefined });
    await expect(timedOut).rejects.toMatchObject({ kind: 'timeout' });
    expect(calls).toBe(2); // a timeout is retried
  });

  it('stops waiting between retries as soon as the request is cancelled', async () => {
    const controller = new AbortController();
    const started = Date.now();
    const result = chat({ ...request, signal: controller.signal }, {
      fetch: async () => { setTimeout(() => controller.abort(), 5); return reply(500, {}); },
      sleep: () => new Promise(resolve => setTimeout(resolve, 5000)),
    });
    await expect(result).rejects.toMatchObject({ kind: 'cancelled' });
    expect(Date.now() - started).toBeLessThan(1000);
  });
});

describe('parseWords edge cases', () => {
  it('keeps English sentences, typographic apostrophes and object replies', () => {
    expect(parseWords('["How are you?","I’m fine.","Mr. Li","T-shirt"]', 'english')).toEqual(['How are you', "I'm fine", 'Mr Li', 'T-shirt']);
    expect(parseWords('{"words":["apple","take care"]}', 'english')).toEqual(['apple', 'take care']);
  });

  it('drops pinyin after Chinese words but keeps bracketed words', () => {
    expect(parseWords('["春天(chūn tiān)","（秋天）"]', 'chinese')).toEqual(['春天', '秋天']);
  });

  it('reads an object that contains an array', () => {
    expect(extractJson('好的：{"question":"Pick one","options":["a","b"],"answer":"a"}')).toEqual({ question: 'Pick one', options: ['a', 'b'], answer: 'a' });
  });
});

describe('model replies, round 2', () => {
  it('reads JSON with real line breaks inside strings', () => {
    expect(parseQuestions('[{"question": "Which word?\nA. cake\nB. map", "answer": "B"}]')).toEqual([{ text: 'Which word?\nA. cake\nB. map', answer: 'B' }]);
  });

  it('reads questions and underlines wrapped in an object, and options in their own field', () => {
    expect(parseQuestions('{"questions":[{"question":"Q1","answer":"A"}]}')).toEqual([{ text: 'Q1', answer: 'A' }]);
    expect(parseUnderlineHits('{"underlines":[{"question":1,"word":"cake","underlined":"a"}]}')).toHaveLength(1);
    expect(parseGenerated('{"question":"Pick one","options":["A. cat","B. dog"],"answer":"A"}')).toEqual({ text: 'Pick one\nA. cat\nB. dog', answer: 'A' });
  });

  it('skips a bracket pair of the wrong shape', () => {
    expect(parseGenerated('第[1]题：{"question":"Q","answer":"A"}')).toEqual({ text: 'Q', answer: 'A' });
  });

  it('keeps words joined by a slash and phrases with brackets', () => {
    expect(parseWords('["his/her","(be) good at","look (at)"]', 'english')).toEqual(['his her', 'be good at', 'look at']);
  });
});

describe('model replies, round 3', () => {
  it('tries a later bracket when text before the JSON contains one', () => {
    expect(extractJson('注意[见下] 结果：["a","b"]')).toEqual(['a', 'b']);
    expect(parseWords('第[1]组：["apple","pear"]', 'english')).toEqual(['apple', 'pear']);
    expect(parseWords('{"words": []}', 'english')).toEqual([]);
  });

  it('reads a single underline object', () => {
    expect(parseUnderlineHits('{"question": 1, "word": "cake", "underlined": "a"}')).toEqual([{ word: 'cake', underlined: 'a', question: 0 }]);
  });

  it('prefers the wrapped list over reading the wrapper as one question', () => {
    expect(parseQuestions('{"text": "以下是题目", "questions": [{"question": "Q1", "answer": "A"}, {"question": "Q2", "answer": "B"}]}'))
      .toEqual([{ text: 'Q1', answer: 'A' }, { text: 'Q2', answer: 'B' }]);
  });

  it('finds a phrase joined by a full-width space when routing underlines', () => {
    const result = applyHits([{ text: 'go by\u3000ship', answer: '' }], [{ word: 'by ship', underlined: 'ship', question: null }]);
    expect(result).toMatchObject({ texts: ['go by\u3000__ship__'], marks: 1, missed: 0 });
  });
});

describe('model replies, round 4', () => {
  it('joins an answer given as a list, one per blank', () => {
    expect(parseQuestions('[{"question":"Tom ___ to school and ___ home.","answer":["goes","comes"]}]')).toEqual([{ text: 'Tom ___ to school and ___ home.', answer: 'goes; comes' }]);
    expect(parseGenerated('{"question":"Q ___","answer":["a"]}')).toEqual({ text: 'Q ___', answer: 'a' });
  });

  it('drops punctuation around Chinese words in a JSON list', () => {
    expect(parseWords('["认真学习。","春天，","（秋天）"]', 'chinese')).toEqual(['认真学习', '春天', '秋天']);
  });
});

describe('model replies, round 5', () => {
  it('keeps the whole list when a note with brackets follows the JSON', () => {
    const reply = '[{"question":"Q1 ___","answer":"a"},{"question":"Q2","answer":"b"},{"question":"Q3","answer":"c"}]\n注：第[2]题图片有些模糊。';
    expect(parseQuestions(reply).map(question => question.text)).toEqual(['Q1 ___', 'Q2', 'Q3']);
    expect(parseUnderlineHits('[{"question":1,"word":"cake","underlined":"a"},{"question":2,"word":"map","underlined":"a"}]\n(题目[3]没有划线)')).toHaveLength(2);
  });

  it('keeps English sentences that end with full-width punctuation', () => {
    expect(parseWords('["How are you？","Good morning！","look forward to…","apple"]', 'english')).toEqual(['How are you', 'Good morning', 'look forward to', 'apple']);
  });

  it('drops a bracketed note after a translation', () => {
    expect(cleanTranslation('苹果（复数）')).toBe('苹果');
    expect(cleanTranslation('（秋天）')).toBe('秋天');
  });

  it('asks for blanks of three underscores when generating', () => {
    expect(generationPrompt(defaultSettings(), { text: 'w___t___r', answer: 'water' })).toContain('三个下划线');
  });
});

describe('model replies, round 6', () => {
  it('keeps options given as objects with their question', () => {
    const one = '{"question":"Which is a fruit?","options":[{"label":"A","text":"apple"},{"label":"B","text":"desk"}],"answer":"A"}';
    expect(parseQuestions(one)).toEqual([{ text: 'Which is a fruit?\nA. apple\nB. desk', answer: 'A' }]);
    expect(parseGenerated('{"question":"Pick one","options":{"A":"cat","B":"dog"},"answer":"B"}')).toEqual({ text: 'Pick one\nA. cat\nB. dog', answer: 'B' });
    expect(parseQuestions(`[${one}]`)).toEqual([{ text: 'Which is a fruit?\nA. apple\nB. desk', answer: 'A' }]);
  });

  it('repairs a trailing comma and never returns part of a broken list', () => {
    expect(parseQuestions('[{"question":"Q1","answer":"a"},{"question":"Q2","answer":"b"},]').map(question => question.text)).toEqual(['Q1', 'Q2']);
    expect(parseWords('["take care","look at",]', 'english')).toEqual(['take care', 'look at']);
    expect(parseQuestions('[{"question":"Q1","answer":"a"},{"question":"Tom says "Hello" to ___.","answer":"b"}]')).toEqual([]);
  });

  it('drops the numbers of a numbered word list', () => {
    expect(parseWords('["1. 春天","2、温暖","(3) 柳树"]', 'chinese')).toEqual(['春天', '温暖', '柳树']);
    expect(parseWords('["1. apple","2) take care"]', 'english')).toEqual(['apple', 'take care']);
  });

  it('routes an underline only to a question that has the word on its own', () => {
    const result = applyHits([{ text: 'I care about it.', answer: '' }, { text: 'Are you OK?', answer: '' }], [{ word: 'are', underlined: 'are', question: null }]);
    expect(result).toMatchObject({ texts: ['I care about it.', '__Are__ you OK?'], marks: 1, missed: 0 });
  });
});

describe('model replies, round 7', () => {
  it('never underlines a number inside a longer number', () => {
    const result = applyHits([{ text: 'There are 12 boys and 2 girls.', answer: '' }], [{ word: '2', underlined: '2', question: 0 }]);
    expect(result).toMatchObject({ texts: ['There are 12 boys and __2__ girls.'], marks: 1 });
  });

  it('gives no words for a broken list, and keeps English phrases in plain text', () => {
    expect(parseWords('["take care", "He said "Hi" to me", "look at"]', 'english')).toEqual([]);
    expect(parseWords('apple\ntake care\nlook at', 'english')).toEqual(['apple', 'take care', 'look at']);
  });
});
