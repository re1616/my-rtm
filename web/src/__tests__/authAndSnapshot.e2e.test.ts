// End-to-end WEB-S3 / WEB-S4 against a real Taskly server (CI starts it with `./gradlew :server:run`).
// Skipped unless TASKLY_E2E_URL is set; fails instead of skipping when TASKLY_E2E_REQUIRED=true.
import { describe, it, expect, beforeEach } from 'vitest';
import { createHmac } from 'node:crypto';
import { getDb, initializeDb } from '../db/database';
import { syncNow } from '../features/sync/SyncService';
import {
  ApiError,
  apiRequest,
  disableTotp,
  enableTotp,
  forgotPassword,
  getAccount,
  login,
  register,
  resetPassword,
  setupTotp,
} from '../api/client';

const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const baseUrl = env.TASKLY_E2E_URL;
if (!baseUrl && env.TASKLY_E2E_REQUIRED === 'true') {
  throw new Error('TASKLY_E2E_REQUIRED=true but TASKLY_E2E_URL is not set — start the server first');
}
const deps = { baseUrl };

/** RFC 6238 code computed independently of the server, as an authenticator app would. */
function totp(base32: string, at = Date.now()): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of base32.replace(/=+$/, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const hash = createHmac('sha1', key).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const bin = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
  return String(bin % 1_000_000).padStart(6, '0');
}

describe.skipIf(!baseUrl)('auth and first sync against the real server', () => {
  beforeEach(async () => {
    await initializeDb();
    const db = getDb();
    await Promise.all([db.tasks.clear(), db.lists.clear(), db.tags.clear(), db.syncQueue.clear(), db.settings.clear()]);
  });

  it('two-factor auth: enabled from the client, then required at login', async () => {
    const email = `e2e-2fa-${crypto.randomUUID()}@taskly.test`;
    const session = await register(deps, email, 'E2e-password-1');
    const { secret, otpauthUri } = await setupTotp(deps, session);
    expect(otpauthUri).toContain('otpauth://totp/');
    await enableTotp(deps, session, totp(secret));
    expect((await getAccount(deps, session)).totpEnabled).toBe(true);

    const err = await login(deps, email, 'E2e-password-1').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('totp_required');

    const signedIn = await login(deps, email, 'E2e-password-1', totp(secret));
    expect(signedIn.userId).toBe(session.userId);

    await disableTotp(deps, signedIn, totp(secret));
    expect((await login(deps, email, 'E2e-password-1')).userId).toBe(session.userId);
  });

  it('password reset: unknown and invalid tokens are refused without leaking accounts', async () => {
    await forgotPassword(deps, `nobody-${crypto.randomUUID()}@taskly.test`); // same answer as for a real account
    const err = await resetPassword(deps, 'forged-token', 'Long-enough-1').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
  });

  it('a new device loads existing data through the snapshot', async () => {
    const session = await register(deps, `e2e-snap-${crypto.randomUUID()}@taskly.test`, 'E2e-password-1');
    const list = await apiRequest<{ id: string }>(deps, 'POST', '/v1/lists', { name: 'Дом' }, session.accessToken);
    for (const name of ['Купить хлеб', 'Позвонить маме', 'Оплатить счёт']) {
      await apiRequest(deps, 'POST', '/v1/tasks', { name, listId: list.id }, session.accessToken);
    }

    expect(await syncNow(deps)).toBe('idle');

    const db = getDb();
    const names = (await db.tasks.toArray()).map((t) => t.name).sort();
    expect(names).toEqual(['Купить хлеб', 'Оплатить счёт', 'Позвонить маме']);
    expect((await db.lists.get(list.id))?.name).toBe('Дом');
    expect(Number((await db.settings.get('syncCursor'))?.value)).toBeGreaterThan(0);
  });
});
