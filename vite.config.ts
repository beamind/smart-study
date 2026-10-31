import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: './',
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  // Array.prototype.at, crypto.randomUUID and structuredClone are used as they are; Chrome 98 has all of them.
  build: { target: 'chrome98' },
  test: { include: ['src/**/*.test.ts', 'server/**/*.test.ts'], environment: 'node' },
});
