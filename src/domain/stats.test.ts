import { describe, expect, it } from 'vitest';
import type { DictationFile, ErrorBookFile } from '../data/schema';
import { itemStats, sessionTimes, summarizeItems, summarizeSessions, totals } from './stats';

const chinese: DictationFile = {
  schema: 'smart-study.dictation/1', language: 'chinese',
  sessions: [
    { id: 's1', kind: 'dictation', at: '2026-10-01T19:00:00+08:00', wordIds: ['w1', 'w2'] },
    { id: 's2', kind: 'review', at: '2026-10-03T19:00:00+08:00', wordIds: ['w1'] },
  ],
  words: [
    { id: 'w1', text: '春天', createdAt: '2026-10-01T19:00:00+08:00', attempts: [{ sessionId: 's1', correct: false }, { sessionId: 's2', correct: true }] },
    { id: 'w2', text: '夏天', createdAt: '2026-10-01T19:00:00+08:00', attempts: [{ sessionId: 's1', correct: true }] },
  ],
};
const errorBook: ErrorBookFile = {
  schema: 'smart-study.error-book/1',
  sessions: [{ id: 'e1', kind: 'capture', at: '2026-10-02T20:00:00+08:00', questionIds: ['q1'] }],
  questions: [{ id: 'q1', text: 'Q', answer: 'A', origin: 'photo', createdAt: '2026-10-02T20:00:00+08:00', attempts: [{ sessionId: 'e1', correct: false }] }],
};

describe('stats', () => {
  it('counts attempts and the latest time per item', () => {
    expect(itemStats(chinese.words[0].attempts, sessionTimes(chinese))).toEqual({ total: 2, wrong: 1, lastMs: Date.parse('2026-10-03T19:00:00+08:00') });
  });

  it('lists sessions newest first with their items and scores', () => {
    const sessions = summarizeSessions({ chinese, errorBook });
    expect(sessions.map(session => [session.id, session.total, session.wrong])).toEqual([['s2', 1, 0], ['e1', 1, 1], ['s1', 2, 1]]);
    expect(sessions[2].items.map(item => item.text)).toEqual(['春天', '夏天']);
    expect(totals(sessions)).toMatchObject({ dictation: { sessions: 2, total: 3, wrong: 1 }, errorBook: { sessions: 0, total: 0, rate: null } });
  });

  it('summarises each item over a date range only', () => {
    const from = Date.parse('2026-10-02T00:00:00+08:00');
    const to = Date.parse('2026-10-04T00:00:00+08:00');
    expect(summarizeItems({ chinese, errorBook }, from, to).map(item => [item.kind, item.text, item.wrong, item.total])).toEqual([
      ['errorBook', 'Q', 1, 1], ['chinese', '春天', 0, 1],
    ]);
  });
});
