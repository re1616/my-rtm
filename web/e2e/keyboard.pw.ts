// WEB-F1/F4 in a real browser, keyboard only (SPEC 4.4 scenarios 1–2, MVP criterion
// "every action from the hotkey table works without a mouse"), plus an axe accessibility scan.
import { test, expect, type Page } from '@playwright/test';
import { skipOnboarding } from './helpers';
import AxeBuilder from '@axe-core/playwright';

const rows = (page: Page) => page.locator('.task-list-rows [role="listitem"]');
const row = (page: Page, name: string) => rows(page).filter({ hasText: name });

async function open(page: Page) {
  await page.goto('/');
  await expect(page.getByRole('navigation', { name: 'Навигация' })).toBeVisible({ timeout: 15_000 });
  await skipOnboarding(page);
}

test('quick add and inbox triage without the mouse', async ({ page }) => {
  await open(page);

  // A list to file things into (sidebar "+", then Enter)
  await page.getByRole('button', { name: 'Новый список' }).click();
  await page.keyboard.type('Покупки');
  await page.keyboard.press('Enter');
  await page.getByRole('button', { name: /^Входящие/ }).click();

  // Scenario 1: T → Smart Add → Enter; the field stays focused for the next task
  await page.locator('body').press('t');
  const input = page.getByRole('combobox', { name: 'Новая задача' });
  await expect(input).toBeFocused();
  await page.keyboard.type('Купить молоко ^завтра #Покупки !2');
  await expect(page.locator('.smartadd-preview')).toContainText('приоритет');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Позвонить маме');
  await page.keyboard.press('Enter');
  await expect(row(page, 'Позвонить маме')).toHaveCount(1);
  await expect(row(page, 'Купить молоко')).toHaveCount(0); // went to "Покупки"
  await expect(input).toBeFocused();

  // Scenario 2: J → D (due) → M (move) → inbox is empty
  await page.keyboard.press('Escape');
  await page.keyboard.press('j');
  await expect(row(page, 'Позвонить маме')).toHaveAttribute('aria-current', 'true');
  await page.keyboard.press('d');
  await page.getByRole('dialog', { name: 'Срок' }).getByRole('textbox').fill('завтра');
  await page.keyboard.press('Enter');
  await page.keyboard.press('m');
  await page.getByRole('dialog', { name: 'Переместить в список' }).getByRole('textbox').fill('Пок');
  await page.keyboard.press('Enter');
  await expect(page.getByText('Всё разобрано')).toBeVisible();

  await page.getByRole('button', { name: /^Покупки/ }).click();
  await expect(rows(page)).toHaveCount(2);
  await expect(row(page, 'Купить молоко')).toHaveClass(/task-row--priority-2/);
  await expect(row(page, 'Позвонить маме').locator('.task-row-due')).toHaveText(/Завтра/);

  // A / C / Ctrl+Z
  await page.keyboard.press('a');
  await expect(page.getByRole('toolbar', { name: 'Действия с выбранными задачами' })).toContainText('Выбрано: 2');
  await page.keyboard.press('c');
  await expect(rows(page)).toHaveCount(0);
  await page.keyboard.press('Control+z');
  await expect(rows(page)).toHaveCount(2);

  // Card from the keyboard: J, Enter, Escape
  // Right after Ctrl+Z the restored rows re-render; retry the keys until the list has taken them
  await expect(async () => {
    await page.keyboard.press('j');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('complementary', { name: /^Задача:/ })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 10_000 });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('complementary', { name: /^Задача:/ })).toHaveCount(0);

  // Shortcut help
  await page.keyboard.press('Shift+Slash');
  await expect(page.getByRole('dialog', { name: 'Горячие клавиши' })).toBeVisible();
  await page.keyboard.press('Escape');
});

test('no critical accessibility violations on the main screens (axe)', async ({ page }) => {
  await open(page);
  await page.locator('body').press('t');
  await page.keyboard.type('Проверить доступность !1');
  await page.keyboard.press('Enter');
  await row(page, 'Проверить доступность').click();
  await expect(page.getByRole('complementary', { name: /^Задача:/ })).toBeVisible();

  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
  const serious = results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious');
  expect(serious.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
});
