import TableStore from 'tablestore';
import type { Counters } from './proxy';

/**
 * Counters in a Tablestore table with a string primary key `id` and an integer column `n`.
 * Adding is a conditional write (the row must still hold the value just read), retried when another request
 * changed it in between, so concurrent requests never lose a count.
 */
export class TablestoreCounters implements Counters {
  private readonly client: TableStore.Client;

  constructor(options: { endpoint: string; instance: string; table: string; accessKeyId: string; accessKeySecret: string; securityToken?: string }, private readonly table = options.table) {
    this.client = new TableStore.Client({
      accessKeyId: options.accessKeyId,
      secretAccessKey: options.accessKeySecret,
      stsToken: options.securityToken,
      endpoint: options.endpoint,
      instancename: options.instance,
      // SDK 5.6.6 retries reads (GetRow is on its list of repeatable APIs) on HTTP 500/502/503 and network errors.
      // It never retries writes: PutRow and UpdateRow are not on that list, and its retry-by-error-code rules do not
      // apply because it parses error codes into HTTP status numbers (see isConditionFailure). So its retries never
      // add twice. A write whose answer was lost is reported by change(); the proxy gives the uses back. Recheck
      // lib/retry/retry_util.js after an SDK upgrade: the codes retried for every API all mean "refused, not done".
      maxRetries: 2,
      // An answer that never comes would otherwise hold the request (and its reservation) forever.
      httpOptions: { timeout: 5000 },
    });
  }

  private call<T>(method: 'getRow' | 'putRow' | 'updateRow', params: object): Promise<T> {
    return new Promise((resolve, reject) => {
      (this.client[method] as (params: object, callback: (error: unknown, data: T) => void) => void)(params, (error, data) => (error ? reject(error) : resolve(data)));
    });
  }

  /** The value, or null when the row does not exist yet. */
  private async read(key: string): Promise<number | null> {
    const data = await this.call<{ row?: { primaryKey?: unknown; attributes?: { columnName: string; columnValue: { toNumber(): number } | number }[] } }>(
      'getRow', { tableName: this.table, primaryKey: [{ id: key }], maxVersions: 1, columnsToGet: ['n'] });
    if (!data.row?.primaryKey) return null;
    const cell = data.row.attributes?.find(item => item.columnName === 'n')?.columnValue;
    return cell === undefined ? 0 : typeof cell === 'number' ? cell : cell.toNumber();
  }

  async get(keys: string[]): Promise<number[]> {
    return Promise.all(keys.map(async key => (await this.read(key)) ?? 0));
  }

  add(key: string, amount: number): Promise<void> {
    return this.change(key, () => amount).then(() => undefined);
  }

  addWithin(key: string, amount: number, limit: number): Promise<boolean> {
    return this.change(key, current => (current + amount <= limit ? amount : null));
  }

  /**
   * Read, then write the new value on condition that the row still holds what was read; on a clash with another
   * request, wait a random few milliseconds and start again. `amountFor` returns null to leave the value as it is.
   */
  private async change(key: string, amountFor: (current: number) => number | null): Promise<boolean> {
    const Long = TableStore.Long;
    for (let attempt = 0; attempt < 12; attempt++) {
      if (attempt > 0) await new Promise(resolve => setTimeout(resolve, 5 + Math.random() * 20 * attempt));
      const current = await this.read(key);
      const amount = amountFor(current ?? 0);
      if (amount === null) return false;
      try {
        if (current === null) {
          await this.call('putRow', {
            tableName: this.table, primaryKey: [{ id: key }], attributeColumns: [{ n: Long.fromNumber(amount) }],
            condition: new TableStore.Condition(TableStore.RowExistenceExpectation.EXPECT_NOT_EXIST, null),
          });
        } else {
          await this.call('updateRow', {
            tableName: this.table, primaryKey: [{ id: key }],
            updateOfAttributeColumns: [{ PUT: [{ n: Long.fromNumber(current + amount) }] }],
            condition: new TableStore.Condition(TableStore.RowExistenceExpectation.EXPECT_EXIST,
              new TableStore.SingleColumnCondition('n', Long.fromNumber(current), TableStore.ComparatorType.EQUAL)),
          });
        }
        return true;
      } catch (error) {
        if (!isConditionFailure(error)) throw error;
      }
    }
    throw new Error(`计数 ${key} 一直在被同时修改，没有改成`);
  }
}

/**
 * Whether a write failed only because its condition did not hold. SDK 5.6.6 tries to parse error bodies as XML with a
 * parser it does not ship, so `code` ends up as the HTTP status (403) and the error code is only inside `message`,
 * which holds the raw protobuf body. Newer versions may fix that and set `code` properly; both are accepted.
 */
export function isConditionFailure(error: unknown): boolean {
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  return code === 'OTSConditionCheckFail' || String(message ?? '').includes('OTSConditionCheckFail');
}
