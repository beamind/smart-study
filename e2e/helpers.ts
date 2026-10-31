import type { Page } from '@playwright/test';

export const at = '2026-10-22T20:44:25+08:00';

/** Seed the in-memory data folder before the app starts. */
export async function seed(page: Page, files: Record<string, unknown>) {
  await page.addInitScript(seedFiles => {
    window.__SMART_STUDY_SEED__ = Object.fromEntries(Object.entries(seedFiles).map(([path, value]) => [path, typeof value === 'string' ? value : JSON.stringify(value)]));
  }, files);
}

export const appFile = (users: { id: string; name: string }[], currentUserId = users[0].id) =>
  ({ schema: 'smart-study.app/1', users: users.map(user => ({ ...user, createdAt: at })), currentUserId });

/** Open the app with in-memory storage, silent speech and a fake model. */
export const open = (page: Page) => page.goto('/?storage=memory&speech=fake&ai=fake');

/** Solve the small sum that guards destructive actions. */
export async function solveSum(page: Page) {
  const text = await page.getByText(/请计算后确认/).textContent();
  const [, a, op, b] = /(\d+) ([+-]) (\d+)/.exec(text!)!;
  await page.getByRole('dialog').getByRole('textbox').fill(String(op === '+' ? Number(a) + Number(b) : Number(a) - Number(b)));
}

/** A tiny PNG for the photo chooser; the fake model ignores its content. */
export const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQKADxRH+GUNBx7AAAAAElFTkSuQmCC', 'base64');
