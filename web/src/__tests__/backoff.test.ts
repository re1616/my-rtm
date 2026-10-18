// WEB-S2: retry delays grow from ~1 s to 5 min with jitter.
import { describe, it, expect } from 'vitest';
import { backoffDelay, BACKOFF_MAX_MS } from '../features/sync/backoff';

describe('WEB-S2: backoff', () => {
  it('starts around one second and doubles', () => {
    expect(backoffDelay(0, () => 1)).toBe(1000);
    expect(backoffDelay(0, () => 0)).toBe(500);
    expect(backoffDelay(1, () => 1)).toBe(2000);
    expect(backoffDelay(4, () => 1)).toBe(16000);
  });

  it('never exceeds five minutes and keeps jitter at the cap', () => {
    for (let a = 0; a < 40; a++) {
      const d = backoffDelay(a);
      expect(d).toBeGreaterThanOrEqual(500);
      expect(d).toBeLessThanOrEqual(BACKOFF_MAX_MS);
    }
    expect(backoffDelay(30, () => 0)).toBe(BACKOFF_MAX_MS / 2);
  });
});
