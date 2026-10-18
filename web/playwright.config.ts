// Browser tests against the production build (`vite preview`, proxied to the API server).
// CI runs them with the server started by `./gradlew :server:run`.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.pw.ts',
  timeout: 60_000,
  retries: 0,
  reporter: [['list'], ['github'], ['junit', { outputFile: 'playwright-junit.xml' }]],
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
    // Local runs may point at a preinstalled Chromium of another revision (CI installs its own)
    ...(process.env.PW_CHROMIUM_PATH ? { launchOptions: { executablePath: process.env.PW_CHROMIUM_PATH } } : {}),
  },
  webServer: {
    command: 'npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
