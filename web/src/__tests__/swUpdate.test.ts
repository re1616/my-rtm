// WEB-S8: a new version waits for the user's go-ahead and never reloads during typing.
import { describe, it, expect, vi } from 'vitest';
import { SwUpdateController, type RegisterSW } from '../features/sync/swUpdateStrategy';

function setup(focused: { value: boolean }) {
  let needRefresh: (() => void) | undefined;
  const updateSW = vi.fn(async (_reload?: boolean) => {});
  const register: RegisterSW = (opts) => {
    needRefresh = opts.onNeedRefresh;
    return updateSW;
  };
  const blurListeners = new Set<() => void>();
  const controller = new SwUpdateController(register, {
    isInputFocused: () => focused.value,
    onInputBlur: (fn) => {
      blurListeners.add(fn);
      return () => blurListeners.delete(fn);
    },
  });
  return { controller, updateSW, newVersion: () => needRefresh?.(), blur: () => blurListeners.forEach((f) => f()) };
}

describe('WEB-S8: app update', () => {
  it('does nothing until a new version is waiting, then asks', async () => {
    const t = setup({ value: false });
    await t.controller.apply();
    expect(t.updateSW).not.toHaveBeenCalled();

    const states: string[] = [];
    t.controller.onChange((s) => states.push(s));
    t.newVersion();
    expect(t.controller.state).toBe('update-ready');
    expect(t.updateSW).not.toHaveBeenCalled(); // no reload without consent

    await t.controller.apply();
    expect(t.updateSW).toHaveBeenCalledWith(true);
    expect(states).toEqual(['update-ready', 'updating']);
  });

  it('waits for the user to leave the field before reloading', async () => {
    const focused = { value: true };
    const t = setup(focused);
    t.newVersion();
    const applying = t.controller.apply();
    await Promise.resolve();
    expect(t.controller.state).toBe('waiting-for-input');
    expect(t.updateSW).not.toHaveBeenCalled();

    t.blur(); // still typing in another field
    await Promise.resolve();
    expect(t.updateSW).not.toHaveBeenCalled();

    focused.value = false;
    t.blur();
    await applying;
    expect(t.updateSW).toHaveBeenCalledWith(true);
  });

  it('"later" hides the prompt without updating', () => {
    const t = setup({ value: false });
    t.newVersion();
    t.controller.dismiss();
    expect(t.controller.state).toBe('idle');
    expect(t.updateSW).not.toHaveBeenCalled();
  });
});
