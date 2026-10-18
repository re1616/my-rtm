/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  /** Google OAuth client id; the Google button is hidden when unset. */
  readonly VITE_GOOGLE_CLIENT_ID?: string;
}
