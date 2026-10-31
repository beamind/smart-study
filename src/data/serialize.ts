/**
 * Serialise a data file with one array element per line: almost as small as compact JSON,
 * still readable and line-diffable when a parent opens the file on a computer.
 */
export function stringifyData(value: unknown): string {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return `${JSON.stringify(value)}\n`;
  const fields = Object.entries(value).filter(([, field]) => field !== undefined).map(([key, field]) => {
    const name = JSON.stringify(key);
    if (Array.isArray(field) && field.length) {
      return `  ${name}: [\n${field.map(item => `    ${JSON.stringify(item)}`).join(',\n')}\n  ]`;
    }
    return `  ${name}: ${JSON.stringify(field)}`;
  });
  return `{\n${fields.join(',\n')}\n}\n`;
}
