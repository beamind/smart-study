import { expect, test, type Page } from '@playwright/test';
import { appFile, open, seed, solveSum } from './helpers';

// Phase 4: the learning record with in-memory storage.

const user = { id: 'u1', name: '测试' };
// One fixed "now" for both the seeded data and the page (page.clock), so the test cannot break around midnight.
const now = (() => { const date = new Date(); date.setHours(12, 0, 0, 0); return date; })();
const daysAgo = (days: number, hour = 9) => { const date = new Date(now); date.setDate(date.getDate() - days); date.setHours(hour, 0, 0, 0); return date.toISOString(); };

async function seedRecords(page: Page) {
  await page.clock.setFixedTime(now);
  await seed(page, {
    'data/app.json': appFile([user]),
    'data/users/u1/dictation-chinese.json': {
      schema: 'smart-study.dictation/1', language: 'chinese',
      sessions: [
        { id: 'c1', kind: 'dictation', at: daysAgo(40), wordIds: ['w1'] },
        { id: 'c2', kind: 'dictation', at: daysAgo(0), wordIds: ['w3', 'w1', 'w2'] },
      ],
      words: [
        { id: 'w1', text: '春天', createdAt: daysAgo(40), attempts: [{ sessionId: 'c1', correct: false }, { sessionId: 'c2', correct: false }] },
        { id: 'w2', text: '夏天', createdAt: daysAgo(0), attempts: [{ sessionId: 'c2', correct: true }] },
        { id: 'w3', text: '秋天', createdAt: daysAgo(0), attempts: [{ sessionId: 'c2', correct: true }] },
      ],
    },
    'data/users/u1/error-book.json': {
      schema: 'smart-study.error-book/1',
      sessions: [{ id: 'e1', kind: 'practice', at: daysAgo(1), questionIds: ['q1', 'q2'] }],
      questions: [
        { id: 'q1', text: 'It was __foggy__.', answer: 'What was the weather like?', origin: 'photo', createdAt: daysAgo(1), attempts: [{ sessionId: 'e1', correct: true }] },
        { id: 'q2', text: 'Tom ____ (go) to school.', answer: 'goes', origin: 'photo', createdAt: daysAgo(1), attempts: [{ sessionId: 'e1', correct: false }] },
      ],
    },
  });
  await open(page);
  await page.getByRole('button', { name: /学习记录/ }).click();
}

test('sessions: totals by range, details, 再听一遍 in reading order, undo', async ({ page }) => {
  await seedRecords(page);
  await expect(page.locator('.summary-grid')).toContainText('3 个词');
  await expect(page.locator('.summary-grid')).toContainText('1 次 · 正确率 67%');
  await expect(page.locator('.summary-grid')).toContainText('2 道题');
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(page.locator('.summary-grid')).toContainText('2 次 · 正确率 50%');
  await page.getByRole('button', { name: '今天' }).click();
  await expect(page.locator('.list-row', { hasText: '错题练习' })).toHaveCount(0);

  await page.getByRole('button', { name: /语文听写/ }).click();
  await expect(page.getByRole('dialog').locator('.list-row')).toHaveCount(3);
  await page.getByRole('button', { name: '再听一遍' }).click();
  await expect(page.getByRole('heading', { name: '确认词语' })).toBeVisible();
  expect(await page.locator('.confirm-row input').evaluateAll(list => list.map(input => (input as HTMLInputElement).value))).toEqual(['秋天', '春天', '夏天']);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '放弃' }).click();
  await expect(page.getByRole('heading', { name: '学习记录' })).toBeVisible();

  await page.getByRole('button', { name: /语文听写/ }).click();
  await page.getByRole('button', { name: '撤销这次' }).click();
  await solveSum(page);
  await page.getByRole('button', { name: '撤销', exact: true }).click();
  await expect(page.getByText('已撤销')).toBeVisible();
  await expect(page.getByText('这段时间没有记录。')).toBeVisible();
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(page.locator('.summary-grid')).toContainText('1 个词');
});

test('items: counts only the range, deleting keeps records outside it, editing a word', async ({ page }) => {
  await seedRecords(page);
  await page.getByRole('button', { name: '按词和题' }).click();
  await expect(page.getByRole('button', { name: /春天/ })).toContainText('错 1 / 1');
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(page.getByRole('button', { name: /春天/ })).toContainText('错 2 / 2');
  await page.getByRole('button', { name: '最近 30 天' }).click();

  await page.getByRole('button', { name: '选择', exact: true }).click();
  await page.getByRole('button', { name: /春天/ }).click();
  await page.getByRole('button', { name: /夏天/ }).click();
  await page.getByRole('button', { name: '删除这段时间的记录' }).click();
  await expect(page.getByText(/将删除这些词在最近 30 天里的 2 条记录/)).toBeVisible();
  await solveSum(page);
  await page.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByText('已删除 2 条记录')).toBeVisible();
  await expect(page.getByRole('button', { name: /春天/ })).toHaveCount(0);
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(page.getByRole('button', { name: /春天/ })).toContainText('错 1 / 1'); // the 40-day-old record is kept
  await expect(page.getByRole('button', { name: /夏天/ })).toHaveCount(0); // no records left, so it is gone

  await page.getByRole('button', { name: /秋天/ }).click();
  await page.getByRole('textbox', { name: '词语', exact: true }).fill('秋季');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('已保存')).toBeVisible();
  await expect(page.getByRole('button', { name: /秋季/ })).toBeVisible();

  await page.getByRole('button', { name: /错题 2/ }).click();
  await page.getByRole('button', { name: /goes/ }).click();
  await page.getByLabel('正确答案').fill('walks');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('button', { name: /walks/ })).toBeVisible();
});

test('a custom date range', async ({ page }) => {
  await seedRecords(page);
  await page.getByRole('button', { name: '选择日期' }).click();
  const day = (iso: string) => { const date = new Date(iso); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; };
  await page.getByLabel('从').fill(day(daysAgo(45)));
  await page.getByLabel('到').fill(day(daysAgo(35)));
  await expect(page.locator('.summary-grid')).toContainText('1 次 · 正确率 0%');
  await expect(page.locator('.list-row', { hasText: '错题练习' })).toHaveCount(0);
});
