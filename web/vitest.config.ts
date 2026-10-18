import { defineConfig } from 'vitest/config';
import { sharedKmpAliases } from './vite.config';

export default defineConfig({
  resolve: {
    alias: sharedKmpAliases,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/__tests__/setup.ts'],
  },
});
