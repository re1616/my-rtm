// Taskly Web — Service Worker update strategy.
//
// WEB-S8: При обнаружении новой версии SW → показать UI «новая версия —
// обновить сейчас»; skipWaiting() только по подтверждению пользователя;
// НЕ вызывать location.reload() при активном вводе (проверка focus на input/textarea).
//
// Используется из main.tsx и/или отдельного модуля инициализации SW.

/** Состояния процесса обновления Service Worker. */
export type SWUpdateState =
  | 'idle'
  | 'update-ready'       // новая версия загружена, ждём подтверждения
  | 'activating'         // skipWaiting вызван, ждём activated
  | 'activated';         // новый SW активирован, можно перезагрузить

export type SWUpdateListener = (state: SWUpdateState) => void;

/**
 * SWUpdateStrategy — управление жизненным циклом обновления Service Worker.
 *
 * @example
 * const strategy = new SWUpdateStrategy(registration);
 * strategy.onStateChange((state) => {
 *   if (state === 'update-ready') showUpdateBanner();
 * });
 */
export class SWUpdateStrategy {
  private _state: SWUpdateState = 'idle';
  private listeners: SWUpdateListener[] = [];
  private registration: ServiceWorkerRegistration;
  private reloadFn: (() => void) | null;

  constructor(
    registration: ServiceWorkerRegistration,
    reloadFn?: () => void,
  ) {
    this.registration = registration;
    this.reloadFn = reloadFn ?? (() => {
      window.location.reload();
    });

    // Подписываемся на обнаружение новой версии
    this.registration.addEventListener('updatefound', () => {
      this._setState('update-ready');
    });
  }

  get state(): SWUpdateState {
    return this._state;
  }

  /** Подписаться на изменения состояния. */
  onStateChange(fn: SWUpdateListener): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== fn);
    };
  }

  /**
   * Пользователь подтвердил обновление.
   * Вызываем skipWaiting() и ждём активации.
   */
  async confirmUpdate(): Promise<void> {
    if (this._state !== 'update-ready') return;

    this._setState('activating');

    const installing = this.registration.installing;
    // skipWaiting() существует на ServiceWorker в браузерах, но TS DOM typings
    // могут его не включать — используем каст через unknown
    const sw = installing as unknown as Record<string, unknown>;
    if (installing && typeof sw.skipWaiting === 'function') {
      await (sw.skipWaiting as () => Promise<void>)();

      // Ждём перехода в activated через statechange
      if (installing.state !== 'activated') {
        await new Promise<void>((resolve) => {
          const onStateChange = () => {
            if (installing.state === 'activated') {
              installing.removeEventListener('statechange', onStateChange);
              resolve();
            }
          };
          installing.addEventListener('statechange', onStateChange);
        });
      }
    }

    this._setState('activated');

    // Перезагрузка только если пользователь НЕ редактирует поле ввода
    if (!isInputFocused()) {
      try {
        this.reloadFn?.();
      } catch {
        // reload может быть недоступен в тестовом окружении (jsdom)
      }
    }
  }

  /**
   * Сбросить состояние (например, если обновление уже неактуально).
   */
  reset(): void {
    this._setState('idle');
  }

  private _setState(next: SWUpdateState): void {
    this._state = next;
    for (const fn of this.listeners) fn(next);
  }
}

/**
 * Проверяет, находится ли фокус на элементе ввода (input, textarea, select).
 * Если пользователь активно печатает — НЕ перезагружаем страницу.
 */
export function isInputFocused(): boolean {
  try {
    const el = document.activeElement;
    if (!el) return false;
    const tag = el.tagName?.toLowerCase() ?? '';
    return (
      tag === 'input' ||
      tag === 'textarea' ||
      tag === 'select' ||
      el.getAttribute('contenteditable') === 'true' ||
      el.getAttribute('role') === 'textbox'
    );
  } catch {
    return false;
  }
}