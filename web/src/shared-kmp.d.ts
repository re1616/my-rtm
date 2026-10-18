// Types for the Kotlin/JS build of the shared module (shared/src/jsMain/.../js/WebApi.kt).
// The module itself is resolved by the Vite/Vitest alias "@taskly/shared"
// (build it with `npm run shared` / `./gradlew :shared:webLibrary`).
declare module '@taskly/shared' {
  /** Smart Add (SPEC F-2). Returns JSON: { name, tokens[], dueInstant?, dueDate?, ... }. */
  export function parseSmartAdd(
    input: string,
    today: string,
    timeZone: string,
    fallbackOffset: string,
    listNames: string[],
    tagNames: string[],
  ): string;
  /** null when the smart-list query is valid, otherwise the parser's message. */
  export function validateQuery(query: string): string | null | undefined;
  export function todayIn(epochMillis: number, timeZone: string, fallbackOffset: string): string;
}
