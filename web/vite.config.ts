import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath } from 'node:url';

// Kotlin/JS build of the shared module (./gradlew :shared:webLibrary)
export const sharedKmp = fileURLToPath(
  new URL('../shared/build/dist/js/productionLibrary/taskly-shared.mjs', import.meta.url),
);

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
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
  resolve: {
    alias: {
      '@': '/src',
      '@taskly/shared': sharedKmp,
    },
  },
});