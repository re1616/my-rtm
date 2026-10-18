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
  SyncState: {} as Record<string, string>,
}));

describe('WEB-S6: sync state indicator', () => {
  beforeEach(() => {
    // Очищаем Zustand store
    store.setState({
      session: null,
      syncState: 'idle',
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

  it('кнопка ручной синхронизации вызывает triggerSync', async () => {
    const syncModule = await import('../features/sync/SyncService');
    const triggerSpy = vi.mocked(syncModule.triggerSync);

    store.setState({
      session: { userId: 'u1', accessToken: 'at', refreshToken: 'rt', email: 'test@taskly.app' },
      syncState: 'offline',
    });
    const AuthPanel = (await import('../features/sync/AuthPanel')).default;
    render(<AuthPanel />);

    const btn = screen.getByText('Синхронизировать');
    fireEvent.click(btn);
    expect(triggerSpy).toHaveBeenCalled();
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