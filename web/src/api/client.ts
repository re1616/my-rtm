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
    try {
      message = (JSON.parse(text) as { error?: string }).error ?? text;
    } catch {
      /* not JSON */
    }
    throw new ApiError(resp.status, message || resp.statusText);
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

export async function login(deps: ApiDeps, email: string, password: string): Promise<Session> {
  const r = await apiRequest<AuthResponse>(deps, 'POST', '/v1/auth/login', { email, password });
  const session = { ...r, email };
  await setSession(session);
  return session;
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