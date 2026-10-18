// WEB-S8: Service Worker: стратегия «новая версия готова — обновить»,
//         без принудительной перезагрузки посреди ввода.
//
// Тестируем реальную реализацию SWUpdateStrategy из src/.
// Mock только на границе (ServiceWorkerRegistration, ServiceWorker) —
// логика состояний и проверка активного ввода тестируется настоящая.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SWUpdateStrategy, isInputFocused } from '../features/sync/swUpdateStrategy';

describe('WEB-S8: Service Worker update strategy', () => {
  let mockRegistration: Record<string, unknown>;
  let mockSW: Record<string, unknown>;
  let updateFoundHandlers: Array<() => void>;
  let stateChangeHandlers: Array<() => void>;
  let skipWaitingResolved: boolean;

  beforeEach(() => {
    updateFoundHandlers = [];
    stateChangeHandlers = [];
    skipWaitingResolved = false;

    mockSW = {
      state: 'installing',
      addEventListener: vi.fn((event: string, handler: unknown) => {
        if (event === 'statechange') stateChangeHandlers.push(handler as () => void);
      }),
      removeEventListener: vi.fn(),
      skipWaiting: vi.fn().mockImplementation(() => {
        skipWaitingResolved = true;
        return Promise.resolve();
      }),
    };

    // Создаём registration с правильными полями, типами
    mockRegistration = {
      installing: mockSW,
      waiting: null,
      active: { state: 'activated' },
      updateViaCache: 'none',
      addEventListener: vi.fn((event: string, handler: unknown) => {
        if (event === 'updatefound') updateFoundHandlers.push(handler as () => void);
      }),
      removeEventListener: vi.fn(),
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('при updatefound переходит в состояние update-ready', () => {
    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
    );

    expect(strategy.state).toBe('idle');

    // Симулируем updatefound
    expect(updateFoundHandlers.length).toBe(1);
    updateFoundHandlers[0]();

    expect(strategy.state).toBe('update-ready');
  });

  it('confirmUpdate: вызывает skipWaiting и переходит в activated', async () => {
    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
    );

    // Сначала получаем updatefound → update-ready
    expect(updateFoundHandlers.length).toBe(1);
    updateFoundHandlers[0]();
    expect(strategy.state).toBe('update-ready');

    // Подтверждаем
    const confirmPromise = strategy.confirmUpdate();

    // Симулируем statechange → activated
    mockSW.state = 'activated';
    stateChangeHandlers.forEach((h) => h());

    await confirmPromise;
    expect(strategy.state).toBe('activated');
    expect(skipWaitingResolved).toBe(true);
  });

  it('confirmUpdate без updatefound — ничего не делает', async () => {
    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
    );

    // Состояние idle — confirmUpdate должен быть no-op
    await strategy.confirmUpdate();
    expect(strategy.state).toBe('idle');
    expect(skipWaitingResolved).toBe(false);
  });

  it('isInputFocused: true когда фокус на input', () => {
    // Создаём input и ставим на него фокус
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();

    expect(isInputFocused()).toBe(true);

    document.body.removeChild(input);
  });

  it('isInputFocused: false когда фокус на div', () => {
    const div = document.createElement('div');
    document.body.appendChild(div);
    div.focus();

    expect(isInputFocused()).toBe(false);

    document.body.removeChild(div);
  });

  it('isInputFocused: true когда фокус на textarea', () => {
    const ta = document.createElement('textarea');
    document.body.appendChild(ta);
    ta.focus();

    expect(isInputFocused()).toBe(true);

    document.body.removeChild(ta);
  });

  it('isInputFocused: true когда contenteditable', () => {
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    document.body.appendChild(editable);
    editable.focus();

    expect(isInputFocused()).toBe(true);

    document.body.removeChild(editable);
  });

  it('стратегия: при activated и НЕ вводе вызывается reload', async () => {
    const reloadSpy = vi.fn();

    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
      reloadSpy,
    );

    // Фокус НЕ на input
    const div = document.createElement('div');
    div.setAttribute('id', 'non-input');
    document.body.appendChild(div);
    div.focus();

    // updatefound → confirm
    expect(updateFoundHandlers.length).toBe(1);
    updateFoundHandlers[0]();
    expect(strategy.state).toBe('update-ready');

    const confirmPromise = strategy.confirmUpdate();

    // activated
    mockSW.state = 'activated';
    stateChangeHandlers.forEach((h) => h());

    await confirmPromise;
    expect(reloadSpy).toHaveBeenCalledTimes(1);

    document.body.removeChild(div);
  });

  it('стратегия: при activated и фокусе на input reload НЕ вызывается', async () => {
    const reloadSpy = vi.fn();

    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
      reloadSpy,
    );

    // Фокус на input — reload вызываться НЕ должен
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();

    // updatefound → confirm
    expect(updateFoundHandlers.length).toBe(1);
    updateFoundHandlers[0]();
    expect(strategy.state).toBe('update-ready');

    const confirmPromise = strategy.confirmUpdate();

    // activated
    mockSW.state = 'activated';
    stateChangeHandlers.forEach((h) => h());

    await confirmPromise;
    // reload НЕ вызван, т.к. пользователь печатает
    expect(reloadSpy).not.toHaveBeenCalled();

    document.body.removeChild(input);
  });

  it('onStateChange можно отписаться', async () => {
    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
    );
    const fn = vi.fn();
    const unsubscribe = strategy.onStateChange(fn);
    unsubscribe();

    updateFoundHandlers[0]();
    expect(fn).not.toHaveBeenCalled();
  });

  it('reset возвращает в idle из любого состояния', () => {
    const strategy = new SWUpdateStrategy(
      mockRegistration as unknown as ServiceWorkerRegistration,
    );

    updateFoundHandlers[0]();
    expect(strategy.state).toBe('update-ready');

    strategy.reset();
    expect(strategy.state).toBe('idle');
  });
});