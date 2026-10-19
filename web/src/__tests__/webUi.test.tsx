// WEB-F1…F5 through the UI (QA-1: public interface). The whole app is rendered over a fake
// IndexedDB; the user types, clicks and presses keys like a person would.
// Also the regressions found in review: escaped text in the list, impossible "uncomplete",
// a new task not appearing until reload, Inbox showing every task.
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, within, waitFor, cleanup, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom/vitest';
import App from '../App';
import store from '../store/taskStore';
import { getDb } from '../db/database';
import { createList, createTask, getPendingSyncOps } from '../entities/taskRepository';
import { dismissUndo } from '../features/undo/undo';

async function resetDb() {
  const db = getDb();
  await Promise.all([db.tasks, db.lists, db.tags, db.syncQueue, db.settings, db.notes, db.attachments, db.locations].map((t) => t.clear()));
  // These scenarios start after the first-run introduction (covered in settingsUi.test.tsx)
  await db.settings.put({ key: 'prefs', value: JSON.stringify({ onboarded: true, locale: 'ru' }) });
}

function resetStore() {
  store.setState({
    currentView: { kind: 'system', view: 'inbox' },
    tab: 'open',
    cursorId: null,
    openTaskId: null,
    selectedTaskIds: [],
    dialog: null,
    toast: null,
    sortBy: 'createdAt',
    sortAsc: true,
    session: null,
    isOnline: true,
  });
}

async function renderApp() {
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('navigation', { name: 'Навигация' });
  return user;
}

const rows = () => screen.queryAllByRole('listitem').filter((el) => el.classList.contains('task-row'));
const rowNamed = (name: string) => rows().find((r) => within(r).queryByText(name) !== null);
const smartAdd = () => screen.getByRole('combobox', { name: 'Новая задача' });

beforeEach(async () => {
  dismissUndo();
  resetStore();
  await resetDb();
});

afterEach(() => {
  cleanup();
});

describe('task list and Smart Add (WEB-F1, F2)', () => {
  it('shows readable empty state and offline text (no escaped \\u sequences)', async () => {
    store.setState({ isOnline: false });
    await renderApp();
    expect(screen.getByText('Всё разобрано')).toBeInTheDocument();
    expect(screen.getByText(/Нет подключения/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\\u04/);
  });

  it('a task added with Smart Add appears at once, the field keeps focus', async () => {
    const user = await renderApp();
    await user.click(smartAdd());
    await user.type(smartAdd(), 'Купить молоко !1{Enter}');
    await waitFor(() => expect(rowNamed('Купить молоко')).toBeDefined());
    await waitFor(() => expect(smartAdd()).toHaveValue(''));
    expect(smartAdd()).toHaveFocus();
    const ops = await getPendingSyncOps();
    expect(ops.map((o) => o.type)).toContain('create');
  });

  it('"#List" files the task into that list; Inbox shows only inbox tasks', async () => {
    await createList('Work');
    const user = await renderApp();
    await user.type(smartAdd(), 'Отчёт #Work{Enter}');
    await user.type(smartAdd(), 'Позвонить{Enter}');
    await waitFor(() => expect(rowNamed('Позвонить')).toBeDefined());
    expect(rowNamed('Отчёт')).toBeUndefined();

    await user.click(screen.getByRole('button', { name: /^Work/ }));
    await waitFor(() => expect(rowNamed('Отчёт')).toBeDefined());
    expect(rowNamed('Позвонить')).toBeUndefined();
  });

  it('suggests lists and tags after "#" and inserts the choice', async () => {
    await createList('Покупки');
    const user = await renderApp();
    await user.type(smartAdd(), 'Хлеб #Пок');
    const option = await screen.findByRole('option', { name: /Покупки/ });
    expect(option).toBeInTheDocument();
    await user.keyboard('{Tab}');
    expect(smartAdd()).toHaveValue('Хлеб #Покупки ');
  });

  it('completing and reopening with the checkbox (regression: uncomplete was impossible)', async () => {
    await createTask({ name: 'Повторить' });
    const user = await renderApp();
    await user.click(await screen.findByRole('button', { name: 'Выполнить «Повторить»' }));
    await waitFor(() => expect(rowNamed('Повторить')).toBeUndefined());

    await user.click(screen.getByRole('tab', { name: 'Выполненные' }));
    await user.click(await screen.findByRole('button', { name: 'Вернуть «Повторить» в работу' }));
    await user.click(screen.getByRole('tab', { name: 'Невыполненные' }));
    await waitFor(() => expect(rowNamed('Повторить')).toBeDefined());
    const ops = (await getPendingSyncOps()).map((o) => o.type);
    expect(ops).toEqual(expect.arrayContaining(['complete', 'uncomplete']));
  });
});

describe('keyboard and mass actions (WEB-F4)', () => {
  it('J / C / Ctrl+Z: complete the task under the pointer and undo it', async () => {
    await createTask({ name: 'Первая' });
    await createTask({ name: 'Вторая' });
    const user = await renderApp();
    await screen.findByText('Первая');
    await user.keyboard('j');
    // Both tasks were created in the same millisecond, so the order is not fixed: use the row under the pointer
    const pointed = await waitFor(() => {
      const r = rows().find((el) => el.getAttribute('aria-current') === 'true');
      expect(r).toBeDefined();
      return r!;
    });
    const name = pointed.querySelector('.task-row-name')!.textContent!;
    await user.keyboard('c');
    await waitFor(() => expect(rowNamed(name)).toBeUndefined());
    expect(screen.getByText('Выполнено: 1 задача')).toBeInTheDocument();
    await user.keyboard('{Control>}z{/Control}');
    await waitFor(() => expect(rowNamed(name)).toBeDefined());
  });

  it('priority keys, select all, delete and undo the deletion', async () => {
    await createTask({ name: 'A' });
    await createTask({ name: 'B' });
    const user = await renderApp();
    await screen.findByText('A');
    await user.keyboard('j2');
    await waitFor(() => expect(rows().filter((r) => r.classList.contains('task-row--priority-2'))).toHaveLength(1));

    await user.keyboard('a');
    expect(screen.getByRole('toolbar', { name: 'Действия с выбранными задачами' })).toHaveTextContent('Выбрано: 2');
    await user.keyboard('{Delete}');
    await waitFor(() => expect(rows()).toHaveLength(0));
    await user.keyboard('{Control>}z{/Control}');
    await waitFor(() => expect(rows()).toHaveLength(2));
    const types = (await getPendingSyncOps()).map((o) => o.type);
    expect(types).toEqual(expect.arrayContaining(['delete', 'restore']));
  });

  it('the mass-actions bar applies priority to every selected task', async () => {
    await createTask({ name: 'X' });
    await createTask({ name: 'Y' });
    await createTask({ name: 'Z' });
    const user = await renderApp();
    await screen.findByText('X');
    await user.keyboard('{Control>}');
    await user.click(rowNamed('X')!);
    await user.click(rowNamed('Y')!);
    await user.keyboard('{/Control}');
    const bar = await screen.findByRole('toolbar', { name: 'Действия с выбранными задачами' });
    await user.click(within(bar).getByRole('button', { name: 'Приоритет 1' }));
    await waitFor(() => {
      expect(rowNamed('X')).toHaveClass('task-row--priority-1');
      expect(rowNamed('Y')).toHaveClass('task-row--priority-1');
    });
    expect(rowNamed('Z')).not.toHaveClass('task-row--priority-1');
  });

  it('M moves the task to another list, ? shows the shortcut table', async () => {
    await createList('Дом');
    await createTask({ name: 'Полить цветы' });
    const user = await renderApp();
    await screen.findByText('Полить цветы');
    await user.keyboard('j');
    await user.keyboard('m');
    const dialog = await screen.findByRole('dialog', { name: 'Переместить в список' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Найти список' }), 'Дом{Enter}');
    await waitFor(() => expect(rowNamed('Полить цветы')).toBeUndefined());
    const ops = await getPendingSyncOps();
    expect(ops.at(-1)).toMatchObject({ type: 'move' });

    await user.keyboard('?');
    const help = await screen.findByRole('dialog', { name: 'Горячие клавиши' });
    expect(within(help).getByText('Переместить в список')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('task card (WEB-F3)', () => {
  it('opens on click, renames inline, renders notes as safe markdown', async () => {
    await createTask({ name: 'Черновик' });
    const user = await renderApp();
    await user.click(await screen.findByText('Черновик'));
    const card = await screen.findByRole('complementary', { name: 'Задача: Черновик' });

    await user.click(within(card).getByRole('button', { name: 'Название: изменить' }));
    const title = within(card).getByRole('textbox', { name: 'Название' });
    await user.clear(title);
    await user.type(title, 'Чистовик{Enter}');
    await waitFor(() => expect(rowNamed('Чистовик')).toBeDefined());

    const card2 = screen.getByRole('complementary', { name: 'Задача: Чистовик' });
    const note = within(card2).getByRole('textbox', { name: 'Новая заметка' });
    await user.click(note);
    await user.paste('**важно** [клик](javascript:alert(1)) <img src=x onerror=alert(1)> [ok](https://example.com)');
    await user.click(within(card2).getByRole('button', { name: 'Добавить заметку' }));
    const body = await waitFor(() => {
      const el = card2.querySelector('.card-note-body');
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    expect(body.querySelector('strong')).toHaveTextContent('важно');
    expect(body.querySelector('img')).toBeNull();
    expect(body.textContent).toContain('<img src=x onerror=alert(1)>');
    const links = [...body.querySelectorAll('a')];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['https://example.com/']);
    expect(links[0]).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect((await getPendingSyncOps()).some((o) => o.entity === 'note' && o.type === 'create')).toBe(true);
    // The row shows the note icon
    expect(within(rowNamed('Чистовик')!).getByLabelText('есть заметки')).toBeInTheDocument();
  });

  it('subtasks: progress in the row, at most three levels', async () => {
    await createTask({ name: 'Проект' });
    const user = await renderApp();
    await user.click(await screen.findByText('Проект'));
    let card = await screen.findByRole('complementary', { name: 'Задача: Проект' });
    await user.type(within(card).getByRole('combobox', { name: 'Новая подзадача' }), 'Этап{Enter}');
    await waitFor(() => expect(within(rowNamed('Проект')!).getByText('0 из 1')).toBeInTheDocument());
    // The subtask is shown in the card, not as a separate row
    expect(rowNamed('Этап')).toBeUndefined();

    await user.click(within(card).getByRole('button', { name: 'Этап' }));
    card = await screen.findByRole('complementary', { name: 'Задача: Этап' });
    await user.type(within(card).getByRole('combobox', { name: 'Новая подзадача' }), 'Шаг{Enter}');
    await user.click(await within(card).findByRole('button', { name: 'Шаг' }));
    card = await screen.findByRole('complementary', { name: 'Задача: Шаг' });
    expect(within(card).queryByRole('combobox', { name: 'Новая подзадача' })).toBeNull();
    expect(within(card).getByText(/не больше трёх уровней/)).toBeInTheDocument();
  });

  it('attachments accept only http(s) links', async () => {
    await createTask({ name: 'Файлы' });
    const user = await renderApp();
    await user.click(await screen.findByText('Файлы'));
    const card = await screen.findByRole('complementary', { name: 'Задача: Файлы' });
    await user.type(within(card).getByRole('textbox', { name: 'Ссылка на файл' }), 'javascript:alert(1)');
    await user.click(within(card).getByRole('button', { name: 'Прикрепить ссылку' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent('http');

    const url = within(card).getByRole('textbox', { name: 'Ссылка на файл' });
    await user.clear(url);
    await user.type(url, 'https://drive.example.com/doc');
    await user.type(within(card).getByRole('textbox', { name: 'Название вложения' }), 'Договор');
    await user.click(within(card).getByRole('button', { name: 'Прикрепить ссылку' }));
    const link = await within(card).findByRole('link', { name: /Договор/ });
    expect(link).toHaveAttribute('href', 'https://drive.example.com/doc');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });
});

describe('smart lists (WEB-F5)', () => {
  it('shows the query problem, previews matches and saves into the sidebar', async () => {
    await createTask({ name: 'Срочное', priority: 1 });
    await createTask({ name: 'Потом', priority: 3 });
    const user = await renderApp();
    await user.click(screen.getByRole('button', { name: 'Новый умный список' }));
    const query = await screen.findByRole('textbox', { name: 'Запрос' });

    await user.type(query, 'priority:1 AND )');
    expect(await screen.findByRole('alert')).toHaveTextContent('Ошибка');
    expect(query).toHaveAttribute('aria-invalid', 'true');

    await user.clear(query);
    await user.type(query, 'priority:1');
    expect(await screen.findByText('Подходит задач: 1')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Название умного списка' }), 'Горит');
    await user.click(screen.getByRole('button', { name: 'Сохранить умный список' }));

    const item = await screen.findByRole('button', { name: /Горит/ });
    expect(item).toHaveAttribute('aria-current', 'page');
    await waitFor(() => expect(rowNamed('Срочное')).toBeDefined());
    expect(rowNamed('Потом')).toBeUndefined();
    const listOp = (await getPendingSyncOps()).find((o) => o.entity === 'list');
    expect(listOp?.fields).toMatchObject({ name: 'Горит', isSmart: true, query: 'priority:1' });
  });

  it('the builder composes a query from conditions', async () => {
    const user = await renderApp();
    await user.click(screen.getByRole('button', { name: 'Новый умный список' }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Поле условия 1' }), 'priority');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Значение условия 1' }), '2');
    await user.click(screen.getByRole('button', { name: '+ Условие' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Поле условия 2' }), 'completed');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Значение условия 2' }), 'true');
    await user.click(screen.getAllByRole('checkbox')[1]);
    expect(screen.getByRole('textbox', { name: 'Запрос' })).toHaveValue('priority:2 AND NOT completed:true');
  });
});

// Keep act() warnings out of the output for async store updates fired after a test ends
afterEach(async () => {
  await act(async () => {});
});
