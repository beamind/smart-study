const pad = (value: number) => String(value).padStart(2, '0');

/** 10月24日 20:30 */
export function formatTime(iso: string): string {
  const date = new Date(iso);
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 今天 20:30 / 昨天 20:30 / 10月24日 20:30 */
export function formatRelative(iso: string, nowMs = Date.now()): string {
  const date = new Date(iso);
  const today = new Date(nowMs);
  const startOfDay = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const days = Math.round((startOfDay(today) - startOfDay(date)) / 86_400_000);
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (days === 0) return `今天 ${time}`;
  if (days === 1) return `昨天 ${time}`;
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
}
