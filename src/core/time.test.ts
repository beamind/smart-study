import { describe, expect, it } from 'vitest';
import { calendarDaysBetween, fileStamp, isIsoTime, parseFileStamp, parseOffset, toIso } from './time';

describe('time', () => {
  it('formats with an explicit offset', () => {
    expect(toIso(Date.parse('2026-03-01T11:30:12Z'), 480)).toBe('2026-03-01T19:30:12+08:00');
    expect(toIso(Date.parse('2026-03-01T11:30:12Z'), -330)).toBe('2026-03-01T06:00:12-05:30');
  });

  it('round-trips through Date.parse', () => {
    const ms = Date.parse('2026-10-31T20:15:03+08:00');
    expect(Date.parse(toIso(ms, 480))).toBe(ms);
  });

  it('validates ISO strings', () => {
    expect(isIsoTime('2026-03-01T19:30:12+08:00')).toBe(true);
    expect(isIsoTime('2026-03-01 19:30:12')).toBe(false);
  });

  it('parses offsets', () => {
    expect(parseOffset('+08:00')).toBe(480);
    expect(parseOffset('-05:30')).toBe(-330);
  });

  it('counts calendar days, not 24-hour periods', () => {
    const late = new Date(2026, 9, 30, 23, 50).getTime();
    const early = new Date(2026, 9, 31, 0, 10).getTime();
    expect(calendarDaysBetween(late, early)).toBe(1);
    expect(calendarDaysBetween(early, early)).toBe(0);
  });

  it('writes history stamps in UTC and reads them back', () => {
    const ms = Date.parse('2026-10-31T20:15:03.123+08:00');
    expect(fileStamp(ms)).toBe('20261031-121503-123');
    expect(parseFileStamp(`${fileStamp(ms)}.json`)).toBe(ms);
    expect(parseFileStamp('notes.json')).toBeNull();
  });
});
