/** The parts of the Tablestore SDK (npm "tablestore", no bundled types) that tablestore.ts uses. */
declare module 'tablestore' {
  namespace TableStore {
    class Client {
      constructor(options: object);
      getRow: unknown;
      putRow: unknown;
      updateRow: unknown;
    }
    const Long: { fromNumber(value: number): unknown };
    class Condition { constructor(expectation: unknown, columnCondition: unknown) }
    class SingleColumnCondition { constructor(name: string, value: unknown, comparator: unknown) }
    const RowExistenceExpectation: { IGNORE: unknown; EXPECT_EXIST: unknown; EXPECT_NOT_EXIST: unknown };
    const ComparatorType: { EQUAL: unknown };
  }
  export default TableStore;
}
