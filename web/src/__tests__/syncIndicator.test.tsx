// WEB-S6: Индикатор «синхронизировано / N в очереди / офлайн / ошибка» + ручной повтор.
// Testing Library тест для AuthPanel (компонент статуса синхронизации).
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import store from '../store/taskStore';
import type { Session } from '../types';

// Мокаем api/client — импорты внутри AuthPanel
vi.mock('../api/client', () => ({
  login: vi.fn().mockResolvedValue({ userId: 'u1', accessToken: 'at', refreshToken: 'rt' }),
  register: vi.fn().mockResolvedValue({ userId: 'u1', accessToken: 'at', refreshToken: 'rt' }),
  setSession: vi.fn().mockResolvedValue(undefined),
  NetworkError: class NetworkError extends Error { constructor(m: string) { super(m); this.name = 'NetworkError'; } },
  ApiError: class ApiError extends Error { constructor(public status: number, m: string) { super(m); this.name = 'ApiError'; } },
}));

// Мокаем SyncService.triggerSync
vi.mock('../features/sync/SyncService', () => ({
  triggerSync: vi.fn().mockResolvedValue('idle' as const),
  requestSync: vi.fn(),
  clearRejections: vi.fn(),
  SyncState: {} as Record<string, string>,
}));

describe('WEB-S6: sync state indicator', () => {
  beforeEach(() => {
    // Очищаем Zustand store
    store.setState({
      session: null,
      syncState: 'idle',
      rejections: [],
      snapshotLoaded: null,
    });
  });

  it('показывает состояние без сессии: "Войдите, чтобы синхронизировать"', async () => {
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText(/Войдите, чтобы синхронизировать/)).toBeInTheDocument();
  });

  it('показывает "Синхронизировано" при idle и активной сессии', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'idle',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText('Синхронизировано')).toBeInTheDocument();
  });

  it('показывает email пользователя при активной сессии', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'user@taskly.app' },
      syncState: 'idle',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByTitle('user@taskly.app')).toBeInTheDocument();
  });

  it('показывает "Офлайн — изменения в очереди"', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'offline',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText(/Офлайн/)).toBeInTheDocument();
  });

  it('показывает "Ошибка синхронизации"', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'error',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText('Ошибка синхронизации')).toBeInTheDocument();
  });

  it('показывает кнопку ручной синхронизации при активной сессии', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'offline',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText('Синхронизировать')).toBeInTheDocument();
  });

  it('кнопка ручной синхронизации просит синхронизацию у вкладки-лидера', async () => {
    const syncModule = await import('../features/sync/SyncService');
    const requestSpy = vi.mocked(syncModule.requestSync);

    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'offline',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);

    fireEvent.click(screen.getByText('Синхронизировать'));
    expect(requestSpy).toHaveBeenCalled();
  });

  it('показывает отклонённые сервером изменения', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'idle',
      rejections: [{ entity: 'task', entityId: 't1', type: 'update', reason: 'priority must be 1, 2 or 3', at: '2026-10-18T10:00:00Z' }],
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByRole('alert').textContent).toContain('Сервер отклонил изменений: 1');
    expect(screen.getByRole('alert').textContent).toContain('priority must be 1, 2 or 3');
  });

  it('показывает "Требуется вход" при unauthenticated', async () => {
    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'unauthenticated',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    expect(screen.getByText('Требуется вход')).toBeInTheDocument();
  });

  it('показывает форму входа без сессии', async () => {
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);
    // Форма содержит поля email и password
    expect(screen.getByPlaceholderText('email')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('пароль')).toBeInTheDocument();
    expect(screen.getByText('Войти')).toBeInTheDocument();
    expect(screen.getByText('Регистрация')).toBeInTheDocument();
  });
});