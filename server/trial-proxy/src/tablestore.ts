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
      maxRetries: 2,
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

  async add(key: string, amount: number): Promise<void> {
    const Long = TableStore.Long;
    for (let attempt = 0; attempt < 8; attempt++) {
      const current = await this.read(key);
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
        return;
      } catch (error) {
        if (!isConditionFailure(error)) throw error;
      }
    }
    throw new Error(`计数 ${key} 一直在被同时修改，没有加上`);
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
