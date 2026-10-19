import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath } from 'node:url';

// Kotlin/JS build of the shared module (./gradlew :shared:webLibrary)
export const sharedKmp = fileURLToPath(
  new URL('../shared/build/dist/js/productionLibrary/taskly-shared.mjs', import.meta.url),
);

/**
 * The KMP output lives outside web/, so its own npm imports (kotlinx-datetime → js-joda)
 * would be looked up next to it. Point them at web/node_modules.
 */
export const sharedKmpAliases: Record<string, string> = {
  '@taskly/shared': sharedKmp,
  '@js-joda/core': fileURLToPath(new URL('./node_modules/@js-joda/core', import.meta.url)),
};

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // WEB-S8: a new version waits for the user's go-ahead (see swUpdateStrategy.ts);
      // the app registers the worker itself via virtual:pwa-register.
      registerType: 'prompt',
      injectRegister: false,
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        // Web Push: notifications with actions (public/push-sw.js)
        importScripts: ['push-sw.js'],
      },
    }),
  ],
  server: {
    // Dev: talk to a local `./gradlew :server:run` without CORS
    proxy: {
      '/v1': { target: 'http://localhost:8080', ws: true },
      '/health': 'http://localhost:8080',
    },
  },
  // Same proxy for `vite preview` (Playwright runs against the production build)
  preview: {
    proxy: {
      '/v1': { target: process.env.TASKLY_API ?? 'http://localhost:8080', ws: true },
      '/health': process.env.TASKLY_API ?? 'http://localhost:8080',
    },
  },
  resolve: {
    alias: {
      '@': '/src',
      ...sharedKmpAliases,
    },
  },
});