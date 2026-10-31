// Bundles the trial proxy into one file for Function Compute: npm run trial:build → server/trial-proxy/dist/index.cjs
import { build } from 'esbuild';

await build({
  entryPoints: ['server/trial-proxy/src/index.ts'],
  outfile: 'server/trial-proxy/dist/index.cjs',
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'cjs',
  logLevel: 'info',
  // tsconfig's "strict" implies alwaysStrict, which makes esbuild put "use strict" at the top of the whole bundle.
  // The Tablestore SDK assigns undeclared variables (`for (key in …)`) and fails under strict mode with
  // "ReferenceError: key is not defined", so the bundle must stay sloppy. Our own code is still type-checked strictly.
  tsconfigRaw: { compilerOptions: { alwaysStrict: false } },
});
