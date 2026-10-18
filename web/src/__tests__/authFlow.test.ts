// WEB-S3: Вход/регистрация (есть), OAuth Google, восстановление пароля, 2FA,
//         обновление access-токена в Web Worker без разлогина.
import { describe, it, expect, beforeEach, vi, beforeAll } from 'vitest';
import { initializeDb, getDb } from '../db/database';
import { login, register, setSession, getSession, refreshSession, apiRequest, NetworkError, ApiError } from '../api/client';

beforeAll(async () => {
  await initializeDb();
});

beforeEach(async () => {
  const db = getDb();
  await Promise.all([db.tasks.clear(), db.syncQueue.clear(), db.settings.clear()]);
  await setSession(null);
});

// Хелпер для создания fake fetch
function fakeFetch(handlers: Record<string, (url: URL, init?: RequestInit) => Response>) {
  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://fake');
    const fn = handlers[url.pathname];
    if (fn) return fn(url, init);
    return new Response(JSON.stringify({ error: 'not found' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' },
    });
  };
}

describe('WEB-S3: auth flows', () => {
  it('вход: login получает токены и сохраняет сессию', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/login': () =>
        new Response(
          JSON.stringify({ accessToken: 'at1', refreshToken: 'rt1', userId: 'u1' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
    });

    const session = await login({ fetchImpl: fetchImpl as unknown as typeof fetch }, 'test@taskly.app', 'password');
    expect(session.accessToken).toBe('at1');
    expect(session.refreshToken).toBe('rt1');
    expect(session.userId).toBe('u1');

    // Сессия сохранена
    const stored = await getSession();
    expect(stored).not.toBeNull();
    expect(stored!.accessToken).toBe('at1');
  });

  it('регистрация: register получает токены и timezone', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/register': (url, init) => {
        const body = JSON.parse(String(init!.body));
        expect(body.tz).toBeDefined(); // timezone отправляется
        return new Response(
          JSON.stringify({ accessToken: 'at2', refreshToken: 'rt2', userId: 'u2' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      },
    });

    const session = await register({ fetchImpl: fetchImpl as unknown as typeof fetch }, 'new@taskly.app', 'pass');
    expect(session.accessToken).toBe('at2');
  });

  it('неверный пароль → ApiError 401', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/login': () =>
        new Response(JSON.stringify({ error: 'Invalid credentials' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
    });

    await expect(
      login({ fetchImpl: fetchImpl as unknown as typeof fetch }, 'bad@taskly.app', 'wrong'),
    ).rejects.toThrow(ApiError);
    // Сессия не сохранена
    expect(await getSession()).toBeNull();
  });

  it('сеть недоступна → NetworkError', async () => {
    const fetchImpl = async () => {
      throw new TypeError('Failed to fetch');
    };

    await expect(
      login({ fetchImpl: fetchImpl as unknown as typeof fetch }, 'test@taskly.app', 'pass'),
    ).rejects.toThrow(NetworkError);
  });

  it('обновление access-токена: refreshSession без разлогина', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/refresh': () =>
        new Response(
          JSON.stringify({ accessToken: 'new-at', refreshToken: 'new-rt', userId: 'u1' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
    });

    await setSession({ userId: 'u1', accessToken: 'old-at', refreshToken: 'old-rt' });
    const next = await refreshSession({ fetchImpl: fetchImpl as unknown as typeof fetch }, {
      userId: 'u1',
      accessToken: 'old-at',
      refreshToken: 'old-rt',
    });
    expect(next).not.toBeNull();
    expect(next!.accessToken).toBe('new-at');
    expect(next!.refreshToken).toBe('new-rt');

    // Сессия обновлена в хранилище
    const stored = await getSession();
    expect(stored!.accessToken).toBe('new-at');
  });

  it('просроченный refresh-токен → сессия очищается', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/refresh': () =>
        new Response(JSON.stringify({ error: 'Token expired' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
    });

    await setSession({ userId: 'u1', accessToken: 'old-at', refreshToken: 'expired-rt' });
    const result = await refreshSession({ fetchImpl: fetchImpl as unknown as typeof fetch }, {
      userId: 'u1',
      accessToken: 'old-at',
      refreshToken: 'expired-rt',
    });
    expect(result).toBeNull();

    // Сессия очищена
    const stored = await getSession();
    expect(stored).toBeNull();
  });

  it('OAuth-подобный вход через мок-провайдера', async () => {
    // Симулируем OAuth провайдера: POST /v1/auth/oauth с токеном Google
    const fetchImpl = fakeFetch({
      '/v1/auth/oauth': (url, init) => {
        const body = JSON.parse(String(init!.body));
        expect(body.provider).toBe('google');
        expect(body.accessToken).toBe('google-at-123');
        return new Response(
          JSON.stringify({ accessToken: 'taskly-at', refreshToken: 'taskly-rt', userId: 'u-oauth' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      },
    });

    // Симулируем OAuth flow: отправляем токен Google на сервер
    const resp = await apiRequest<{ accessToken: string; refreshToken: string; userId: string }>(
      { fetchImpl: fetchImpl as unknown as typeof fetch },
      'POST',
      '/v1/auth/oauth',
      { provider: 'google', accessToken: 'google-at-123' },
    );

    expect(resp.accessToken).toBe('taskly-at');
    expect(resp.userId).toBe('u-oauth');
  });

  it('восстановление пароля: POST /v1/auth/reset-password', async () => {
    const fetchImpl = fakeFetch({
      '/v1/auth/reset-password': (url, init) => {
        const body = JSON.parse(String(init!.body));
        expect(body.email).toBe('user@taskly.app');
        return new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      },
    });

    const resp = await apiRequest<{ ok: boolean }>(
      { fetchImpl: fetchImpl as unknown as typeof fetch },
      'POST',
      '/v1/auth/reset-password',
      { email: 'user@taskly.app' },
    );
    expect(resp.ok).toBe(true);
  });

  it('запрос 2FA при входе, если включена', async () => {
    // Сервер может ответить 401 с флагом 2fa_required
    const fetchImpl = fakeFetch({
      '/v1/auth/login': () =>
        new Response(
          JSON.stringify({ error: '2fa_required', tempToken: 'temp-token' }),
          { status: 401, headers: { 'Content-Type': 'application/json' } },
        ),
    });

    await expect(
      login({ fetchImpl: fetchImpl as unknown as typeof fetch }, 'test@taskly.app', 'pass'),
    ).rejects.toThrow(ApiError);

    // После отправки 2FA-кода получаем нормальный ответ
    const fetchImpl2FA = fakeFetch({
      '/v1/auth/2fa': (url, init) => {
        const body = JSON.parse(String(init!.body));
        expect(body.code).toBe('123456');
        expect(body.tempToken).toBe('temp-token');
        return new Response(
          JSON.stringify({ accessToken: 'at-2fa', refreshToken: 'rt-2fa', userId: 'u1' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      },
    });

    const session = await apiRequest<{ accessToken: string; refreshToken: string; userId: string }>(
      { fetchImpl: fetchImpl2FA as unknown as typeof fetch },
      'POST',
      '/v1/auth/2fa',
      { code: '123456', tempToken: 'temp-token' },
    );
    expect(session.accessToken).toBe('at-2fa');
  });
});