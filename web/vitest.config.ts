import { defineConfig } from 'vitest/config';
import { sharedKmp } from './vite.config';

export default defineConfig({
  resolve: {
    alias: {
      '@taskly/shared': sharedKmp,
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/__tests__/setup.ts'],
  },
});
