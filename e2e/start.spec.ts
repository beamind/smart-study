import { expect, test } from '@playwright/test';

// Smoke tests: first run, users and settings, using in-memory storage.

test('first run: choose storage, skip the key, create a user, reach home', async ({ page }) => {
  await page.goto('/?storage=memory&speech=fake');
  await page.getByRole('button', { name: '先试用' }).click();
  await page.getByRole('button', { name: '以后再填' }).click();
  await page.getByLabel('名字').fill('小明');
  await page.getByRole('button', { name: '开始使用' }).click();
  await expect(page.getByRole('button', { name: /小明/ })).toBeVisible();
  for (const title of ['语文听写', '英语听写', '英语错题本', '学习记录']) await expect(page.getByRole('button', { name: new RegExp(title) })).toBeVisible();
});

test('first run with the example child: records, word book and error book have data', async ({ page }) => {
  await page.goto('/?storage=memory&speech=fake');
  await page.getByRole('button', { name: '先试用' }).click();
  await page.getByRole('button', { name: '以后再填' }).click();
  await page.getByRole('button', { name: '先看看示例（小华）' }).click();
  await expect(page.locator('.user-switch', { hasText: '小华' })).toBeVisible();
  await page.getByRole('button', { name: /学习记录/ }).click();
  // 6 dictations; the error book counts only practice sessions (1), all within the last 7 days.
  await expect(page.locator('.summary-grid')).toContainText(/听写44 个词6 次.*错题练习6 道题1 次/);
  await page.getByRole('button', { name: '返回' }).click();
  await page.getByRole('button', { name: /英语错题本/ }).click();
  await expect(page.locator('.question-row')).toHaveCount(9);
  await page.getByRole('button', { name: '返回' }).click();
  await page.locator('.user-switch').click();
  await expect(page.getByRole('button', { name: /添加示例孩子/ })).toHaveCount(0); // only one 小华
});

test.describe('with a seeded folder', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      const at = '2026-10-22T20:44:25+08:00';
      const user = { id: 'u1', name: '乐乐', createdAt: at };
      window.__SMART_STUDY_SEED__ = {
        'data/app.json': JSON.stringify({ schema: 'smart-study.app/1', users: [user, { id: 'u2', name: '游客', createdAt: at }], currentUserId: 'u1' }),
        'data/users/u1/error-book.json': JSON.stringify({
          schema: 'smart-study.error-book/1',
          sessions: [{ id: 's1', kind: 'capture', at, questionIds: ['q1'] }],
          questions: [{ id: 'q1', text: 'It was __foggy__ yesterday. (对画线部分提问)', answer: "What was the weather like yesterday?", origin: 'photo', createdAt: at, attempts: [{ sessionId: 's1', correct: false }] }],
        }),
      };
    });
    await page.goto('/?storage=memory&speech=fake');
  });

  test('switch, add, rename and delete users', async ({ page }) => {
    await page.getByRole('button', { name: /乐乐/ }).click();
    await page.getByRole('button', { name: '添加孩子' }).click();
    await page.getByLabel('名字').fill('乐乐');
    await page.getByRole('button', { name: '保存' }).click();
    await expect(page.getByText('已经有这个名字了')).toBeVisible();
    await page.getByLabel('名字').fill('小红');
    await page.getByRole('button', { name: '保存' }).click();
    await page.getByRole('button', { name: '改名 小红' }).click();
    await page.getByLabel('名字').fill('小红红');
    await page.getByRole('button', { name: '保存' }).click();
    await page.getByRole('button', { name: '删除 小红红' }).click();
    const sum = await page.getByText(/请计算后确认/).textContent();
    const [, a, op, b] = /(\d+) ([+-]) (\d+)/.exec(sum!)!;
    await page.getByRole('textbox').fill(String(op === '+' ? Number(a) + Number(b) : Number(a) - Number(b)));
    await page.getByRole('button', { name: '删除', exact: true }).click();
    await expect(page.getByText('小红红', { exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: /游客/ }).first().click();
    await expect(page.getByRole('button', { name: /游客/ })).toBeVisible();
  });

  test('settings save and survive navigation', async ({ page }) => {
    await page.getByRole('button', { name: '设置' }).click();
    await page.getByRole('button', { name: '不限时' }).first().click();
    await page.getByRole('button', { name: '返回' }).click();
    await page.getByRole('button', { name: '设置' }).click();
    await expect(page.getByRole('button', { name: '不限时' }).first()).toHaveClass(/selected/);
  });
});
