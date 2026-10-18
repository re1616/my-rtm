// Taskly Web — minimal API client for the Taskly server.
import { getDb } from '../db/database';
import type { Session } from '../types';

/** The server could not be reached (offline, DNS, CORS, timeout). The op stays queued. */
export class NetworkError extends Error {}

/** The server answered with a non-2xx status. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    /** Machine-readable reason from the server, e.g. `totp_required`. */
    public readonly code?: string,
  ) {
    super(message);
  }
}

export interface ApiDeps {
  /** Injected for tests and to simulate offline; defaults to global fetch. */
  fetchImpl?: typeof fetch;
  /** API origin, e.g. "https://api.taskly.app". Empty = same origin (Vite dev proxy). */
  baseUrl?: string;
  timeoutMs?: number;
}

export function defaultBaseUrl(): string {
  const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  return env?.VITE_API_URL ?? '';
}

export async function apiRequest<T>(
  deps: ApiDeps,
  method: string,
  path: string,
  body?: unknown,
  token?: string,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const fetchImpl = deps.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), deps.timeoutMs ?? 15000);
  let resp: Response;
  try {
    resp = await fetchImpl(`${deps.baseUrl ?? defaultBaseUrl()}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
        ...(extraHeaders ?? {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
  } catch (e) {
    throw new NetworkError(e instanceof Error ? e.message : String(e));
  } finally {
    clearTimeout(timer);
  }
  const text = await resp.text();
  if (!resp.ok) {
    let message = text;
    let code: string | undefined;
    try {
      const body = JSON.parse(text) as { error?: string; code?: string };
      message = body.error ?? text;
      code = body.code;
    } catch {
      /* not JSON */
    }
    throw new ApiError(resp.status, message || resp.statusText, code);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

// ── Session (stored in IndexedDB settings) ──

const SESSION_KEY = 'session';

export async function getSession(): Promise<Session | null> {
  const row = await getDb().settings.get(SESSION_KEY);
  if (!row) return null;
  try {
    return JSON.parse(row.value) as Session;
  } catch {
    return null;
  }
}

export async function setSession(session: Session | null): Promise<void> {
  if (session) {
    await getDb().settings.put({ key: SESSION_KEY, value: JSON.stringify(session) });
  } else {
    await getDb().settings.delete(SESSION_KEY);
  }
}

interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
}

/**
 * Password sign-in. When the account has two-factor auth enabled the first call fails with
 * `ApiError` code `totp_required`; call again with the authenticator code.
 */
export async function login(deps: ApiDeps, email: string, password: string, totpCode?: string): Promise<Session> {
  const r = await apiRequest<AuthResponse>(deps, 'POST', '/v1/auth/login', {
    email,
    password,
    ...(totpCode ? { totpCode } : {}),
  });
  const session = { ...r, email };
  await setSession(session);
  return session;
}

/** Signs in with a Google Identity Services credential (ID token). */
export async function loginWithGoogle(deps: ApiDeps, idToken: string, totpCode?: string): Promise<Session> {
  const r = await apiRequest<AuthResponse>(deps, 'POST', '/v1/auth/google', {
    idToken,
    ...(totpCode ? { totpCode } : {}),
  });
  const session: Session = { ...r };
  await setSession(session);
  const account = await getAccount(deps, session).catch(() => null);
  if (account) {
    session.email = account.email;
    await setSession(session);
  }
  return session;
}

/** Sends a reset link if the email is registered (the answer is the same either way). */
export async function forgotPassword(deps: ApiDeps, email: string): Promise<void> {
  await apiRequest(deps, 'POST', '/v1/auth/password/forgot', { email });
}

/** Sets a new password with the token from the reset email. All sessions are signed out. */
export async function resetPassword(deps: ApiDeps, token: string, newPassword: string): Promise<void> {
  await apiRequest(deps, 'POST', '/v1/auth/password/reset', { token, newPassword });
}

export interface Account {
  userId: string;
  email: string;
  totpEnabled: boolean;
  hasPassword: boolean;
  googleLinked: boolean;
}

export function getAccount(deps: ApiDeps, session: Session): Promise<Account> {
  return apiRequest<Account>(deps, 'GET', '/v1/auth/account', undefined, session.accessToken);
}

/** Starts two-factor setup: returns the secret and an otpauth:// URI for the authenticator app. */
export function setupTotp(deps: ApiDeps, session: Session): Promise<{ secret: string; otpauthUri: string }> {
  return apiRequest(deps, 'POST', '/v1/auth/2fa/setup', undefined, session.accessToken);
}

export async function enableTotp(deps: ApiDeps, session: Session, code: string): Promise<void> {
  await apiRequest(deps, 'POST', '/v1/auth/2fa/enable', { code }, session.accessToken);
}

export async function disableTotp(deps: ApiDeps, session: Session, code: string): Promise<void> {
  await apiRequest(deps, 'POST', '/v1/auth/2fa/disable', { code }, session.accessToken);
}

export async function register(deps: ApiDeps, email: string, password: string): Promise<Session> {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const r = await apiRequest<AuthResponse>(deps, 'POST', '/v1/auth/register', { email, password, tz });
  const session = { ...r, email };
  await setSession(session);
  return session;
}

/** Exchanges the refresh token; returns null when the session is no longer valid. */
export async function refreshSession(deps: ApiDeps, session: Session): Promise<Session | null> {
  try {
    const r = await apiRequest<AuthResponse>(deps, 'POST', '/v1/auth/refresh', {
      refreshToken: session.refreshToken,
    });
    const next = { ...session, ...r };
    await setSession(next);
    return next;
  } catch (e) {
    if (e instanceof ApiError && (e.status === 400 || e.status === 401)) {
      await setSession(null);
      return null;
    }
    throw e;
  }
}