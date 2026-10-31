import { calendarDaysBetween } from '../core/time';

export interface Candidate<T> {
  item: T;
  total: number;
  wrong: number;
  /** Time of the latest attempt; 0 counts as "today". */
  lastMs: number;
}

/**
 * Pick `count` items for review (错词听写、错题练习、举一反三), as documented in docs/data-contract.md:
 * p = clamp(max(error rate, days since last attempt × 0.01), 0, 1); up to count × 20 rounds;
 * then fill randomly; finally shuffle.
 */
export function sample<T>(candidates: Candidate<T>[], count: number, nowMs: number, random: () => number = Math.random): T[] {
  if (count <= 0 || !candidates.length) return [];
  const chosen = new Set<number>();
  const probability = candidates.map(candidate => {
    const rate = candidate.total ? candidate.wrong / candidate.total : 0;
    const days = candidate.lastMs > 0 ? Math.max(0, calendarDaysBetween(candidate.lastMs, nowMs)) : 0;
    return Math.min(1, Math.max(0, Math.max(rate, days * 0.01)));
  });
  // Visit the candidates in a new random order every round: walking them in file order would give items near
  // the start (the oldest words and questions) the first chance in every round, so later ones were rarely picked.
  for (let round = 0; round < count * 20 && chosen.size < count; round++) {
    for (const index of shuffle(candidates.map((_, i) => i), random)) {
      if (chosen.size >= count) break;
      if (!chosen.has(index) && random() < probability[index]) chosen.add(index);
    }
  }
  const rest = shuffle(candidates.map((_, index) => index).filter(index => !chosen.has(index)), random);
  for (const index of rest) { if (chosen.size >= count) break; chosen.add(index); }
  return shuffle([...chosen], random).map(index => candidates[index].item);
}

export function shuffle<T>(items: T[], random: () => number = Math.random): T[] {
  const result = [...items];
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}
