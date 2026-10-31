/** ISO 8601 timestamps with the local UTC offset, e.g. 2026-03-01T19:30:12+08:00. */
export type IsoTime = string;

const pad = (value: number, size = 2) => String(Math.trunc(Math.abs(value))).padStart(size, '0');

/** Format a time in a fixed UTC offset (minutes east of UTC). Defaults to the device offset. */
export function toIso(ms: number, offsetMinutes = -new Date(ms).getTimezoneOffset()): IsoTime {
  const shifted = new Date(ms + offsetMinutes * 60_000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`
    + `T${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:${pad(shifted.getUTCSeconds())}`
    + `${sign}${pad(offsetMinutes / 60)}:${pad(offsetMinutes % 60)}`;
}

export const nowIso = (): IsoTime => toIso(Date.now());

const isoPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?(Z|[+-]\d{2}:\d{2})$/;

export function isIsoTime(value: unknown): value is IsoTime {
  return typeof value === 'string' && isoPattern.test(value) && Number.isFinite(Date.parse(value));
}

export const isoToMs = (value: IsoTime): number => Date.parse(value);

/** Parse "+08:00" / "-05:30" into minutes east of UTC. */
export function parseOffset(value: string): number {
  const match = /^([+-])(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error(`时区格式应为 +08:00，收到：${value}`);
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === '-' ? -minutes : minutes;
}

/**
 * Whole calendar days between two instants, counted in the device's local time zone.
 * Used for the "days since last attempt" sampling weight.
 */
export function calendarDaysBetween(earlierMs: number, laterMs: number): number {
  const startOfDay = (ms: number) => {
    const date = new Date(ms);
    return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  };
  return Math.round((startOfDay(laterMs) - startOfDay(earlierMs)) / 86_400_000);
}

/**
 * Compact sortable stamp for history file names, in UTC so that the order does not change with the
 * time zone or daylight saving time, e.g. 20261031-121503-123.
 */
export function fileStamp(ms: number): string {
  const date = new Date(ms);
  return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}`
    + `-${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}-${pad(date.getUTCMilliseconds(), 3)}`;
}

/** The time in a name that starts with a fileStamp, or null. */
export function parseFileStamp(name: string): number | null {
  const match = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-(\d{3})/.exec(name);
  if (!match) return null;
  const [year, month, day, hour, minute, second, ms] = match.slice(1).map(Number);
  const value = Date.UTC(year, month - 1, day, hour, minute, second, ms);
  return Number.isFinite(value) ? value : null;
}
