import { expect, test, type Page } from '@playwright/test';
import { appFile, at, open, seed, solveSum, tinyPng } from './helpers';

// Phase 3: the English error book with in-memory storage and the fake model (?ai=fake).

const user = { id: 'u1', name: '测试' };
const question = (id: string, text: string, answer: string, attempts: boolean[] = [false]) =>
  ({ id, text, answer, origin: 'photo', createdAt: at, attempts: attempts.map(correct => ({ sessionId: 's1', correct })) });

async function seedBook(page: Page, questions: ReturnType<typeof question>[]) {
  await seed(page, {
    'data/app.json': appFile([user]),
    'data/users/u1/error-book.json': {
      schema: 'smart-study.error-book/1',
      sessions: [{ id: 's1', kind: 'legacy', at, questionIds: questions.map(item => item.id) }],
      questions,
    },
  });
  await open(page);
  await page.getByRole('button', { name: /英语错题本/ }).click();
}

const foggy = question('q1', 'It was __foggy__ yesterday. (对画线部分提问)', 'What was the weather like yesterday?');

test('capture: photo → recognise → duplicate marked → underlines → edit → save → undo', async ({ page }) => {
  await seedBook(page, [foggy]);
  await expect(page.locator('u', { hasText: 'foggy' })).toBeVisible();
  await page.getByRole('button', { name: '录入', exact: true }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '相册' }).click();
  await (await chooser).setFiles({ name: 'paper.png', mimeType: 'image/png', buffer: tinyPng });
  await page.getByRole('button', { name: '识别' }).click();

  await expect(page.getByRole('heading', { name: '确认错题' })).toBeVisible();
  await expect(page.getByText(/^已在错题本（/)).toBeVisible();
  await expect(page.getByLabel('保存第 1 题')).not.toBeChecked();
  await expect(page.getByRole('button', { name: '保存 2 道错题' })).toBeVisible();

  await page.getByRole('button', { name: '识别划线' }).click();
  await expect(page.getByText('标出了 3 处划线。请对照照片检查。')).toBeVisible();
  await expect(page.locator('u', { hasText: /^a$/ })).toHaveCount(3);

  await page.getByRole('button', { name: '修改第 3 题' }).click();
  await page.getByLabel('正确答案').fill('rides');
  await page.getByRole('button', { name: '确定' }).click();
  await expect(page.getByText('答案：rides')).toBeVisible();

  await page.getByRole('button', { name: '保存 2 道错题' }).click();
  await expect(page.getByText('已保存 2 道错题')).toBeVisible();
  await expect(page.getByText('3 道', { exact: true })).toBeVisible();
  await expect(page.getByText(/最近一次：.*录入 2 道/)).toBeVisible();
  await page.getByRole('status').getByRole('button', { name: '撤销' }).click();
  await expect(page.getByText('已撤销', { exact: true })).toBeVisible();
  await expect(page.getByText('1 道', { exact: true })).toBeVisible();
});

test('manual entry with the underline button, edit, and delete', async ({ page }) => {
  await seedBook(page, [foggy]);
  await page.getByRole('button', { name: '录入', exact: true }).click();
  await page.getByRole('button', { name: '手动输入' }).click();
  const text = page.getByLabel('题目');
  await text.fill('There are some apples on the table.');
  await text.evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(6, 9));
  await page.getByRole('button', { name: '划线' }).click();
  await expect(text).toHaveValue('There __are__ some apples on the table.');
  await page.getByLabel('正确答案').fill('Are there any apples on the table?');
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await page.getByRole('button', { name: '保存 1 道错题' }).click();
  await expect(page.getByText('已保存 1 道错题')).toBeVisible();
  await expect(page.locator('u', { hasText: 'are' })).toBeVisible();
  await expect(page.getByText(/手动输入/)).toBeVisible();

  await page.getByRole('button', { name: /foggy/ }).click();
  await page.getByLabel('正确答案').fill('What was the weather like?');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByText('答案：What was the weather like?', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '选择' }).click();
  await page.getByRole('button', { name: /apples/ }).click();
  await page.getByRole('button', { name: '删除 1 道' }).click();
  await solveSum(page);
  await page.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByText('已删除 1 道题')).toBeVisible();
  await expect(page.getByText('1 道', { exact: true })).toBeVisible();
});

test('practice: sample, answer one by one, mark, save; can be resumed after reloading', async ({ page }) => {
  await seedBook(page, [foggy, question('q2', 'Tom ______ (go) to school.', 'goes', [true]), question('q3', 'She ______ (have) a cat.', 'has', [false, true])]);
  await page.getByRole('button', { name: '练习' }).click();
  await page.getByRole('button', { name: '抽 3 道题开始' }).click();
  await expect(page.getByText(/第\s*1\s*\/ 3 题/)).toBeVisible();
  await expect(page.getByLabel(/剩余 \d+ 秒/)).toBeVisible();
  await page.getByRole('button', { name: '下一题' }).click();
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '先离开，下次接着做' }).click();
  await expect(page.getByText('上次的错题练习还没做完（第 2 / 3 题）')).toBeVisible();

  await page.reload();
  await page.getByRole('button', { name: /英语错题本/ }).click();
  await page.getByRole('button', { name: '继续做' }).click();
  await expect(page.getByText(/第\s*2\s*\/ 3 题/)).toBeVisible();
  await page.getByRole('button', { name: '下一题' }).click();
  await page.getByRole('button', { name: '做完了，对答案' }).click();
  await page.getByRole('button', { name: /goes/ }).click();
  await page.getByRole('button', { name: '提交（做错 1 题）' }).click();
  await expect(page.getByText('已保存：3 题，做错 1 题')).toBeVisible();
  await expect(page.getByText('上次的错题练习', { exact: false })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /goes/ }).getByText('错 1 / 2')).toBeVisible();
  await expect(page.getByText(/最近一次：.*练习 3 道，做错 1 道/)).toBeVisible();
});

test('举一反三: failures are shown and can be retried, only wrong new questions are saved', async ({ page }) => {
  await seedBook(page, [foggy, question('q2', 'Tom ______ (go) to school. [fail-once]', 'goes')]);
  await page.getByRole('button', { name: '举一反三' }).click();
  await page.getByRole('button', { name: '出 2 道新题' }).click();
  await expect(page.getByText('1 道题没有出成功。', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: '开始练习（1 题）' })).toBeEnabled();
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(page.getByRole('button', { name: '开始练习（2 题）' })).toBeEnabled();
  await page.getByRole('button', { name: '开始练习（2 题）' }).click();
  await page.getByRole('button', { name: '下一题' }).click();
  await page.getByRole('button', { name: '做完了，对答案' }).click();
  await page.getByRole('button', { name: /新题：Tom/ }).click();
  await page.getByRole('button', { name: '提交（做错 1 题）' }).click();
  await expect(page.getByText('已把做错的 1 道新题保存到错题本（共 2 题）')).toBeVisible();
  await expect(page.getByText('3 道', { exact: true })).toBeVisible();
  await expect(page.getByText(/录入 · 举一反三$/)).toBeVisible();
  await page.getByRole('status').getByRole('button', { name: '撤销' }).click();
  await expect(page.getByText('2 道', { exact: true })).toBeVisible();
});

test('举一反三 with everything right saves nothing', async ({ page }) => {
  await seedBook(page, [foggy]);
  await page.getByRole('button', { name: '举一反三' }).click();
  await page.getByRole('button', { name: '出 1 道新题' }).click();
  await page.getByRole('button', { name: '开始练习（1 题）' }).click();
  await page.getByRole('button', { name: '做完了，对答案' }).click();
  await page.getByRole('button', { name: '提交（全对）' }).click();
  await expect(page.getByText('全对！1 道新题都做对了，不需要保存')).toBeVisible();
  await expect(page.getByText('1 道', { exact: true })).toBeVisible();
});

test('a practice draft whose submission is already in the file is not offered again', async ({ page }) => {
  const draft = { kind: 'practice', items: [{ key: 'k1', text: 'Q', answer: 'A', questionId: 'q1' }], index: 0, phase: 'answers', wrong: [], startedAt: at, sessionId: 's_restored' };
  await seed(page, {
    'data/app.json': appFile([{ id: 'u1', name: '乐乐' }]),
    'data/users/u1/error-book.json': {
      schema: 'smart-study.error-book/1',
      sessions: [{ id: 's_cap', kind: 'capture', at, questionIds: ['q1'] }, { id: 's_restored', kind: 'practice', at, questionIds: ['q1'] }],
      questions: [{ id: 'q1', text: 'Q', answer: 'A', origin: 'manual', createdAt: at, attempts: [{ sessionId: 's_cap', correct: false }, { sessionId: 's_restored', correct: true }] }],
    },
  });
  await page.addInitScript(value => {
    if (sessionStorage.getItem('seeded-draft')) return;
    sessionStorage.setItem('seeded-draft', '1');
    localStorage.setItem('CapacitorStorage.draft:error-book:u1', value);
  }, JSON.stringify(draft));
  await open(page);
  await page.getByRole('button', { name: /英语错题本/ }).click();
  await expect(page.getByText('已经恢复到错题本里了')).toBeVisible();
  await expect(page.getByText(/上次的错题练习还没提交/)).toHaveCount(0);
});

test('a draft whose save may come back from the temp file keeps its marks and cannot be discarded', async ({ page }) => {
  const draft = { kind: 'practice', items: [{ key: 'k1', text: 'Q', answer: 'A', questionId: 'q1' }], index: 0, phase: 'answers', wrong: ['k1'], startedAt: at, sessionId: 's_pending', saveFailed: true };
  await seed(page, {
    'data/app.json': appFile([{ id: 'u1', name: '乐乐' }]),
    'data/users/u1/error-book.json': {
      schema: 'smart-study.error-book/1',
      sessions: [{ id: 's_cap', kind: 'capture', at, questionIds: ['q1'] }],
      questions: [{ id: 'q1', text: 'Q', answer: 'A', origin: 'manual', createdAt: at, attempts: [{ sessionId: 's_cap', correct: false }] }],
    },
  });
  await page.addInitScript(value => {
    if (sessionStorage.getItem('seeded-draft')) return;
    sessionStorage.setItem('seeded-draft', '1');
    localStorage.setItem('CapacitorStorage.draft:error-book:u1', value);
  }, JSON.stringify(draft));
  await open(page);
  await page.getByRole('button', { name: /英语错题本/ }).click();
  await expect(page.getByText(/重新打开 App 后会自动恢复/)).toBeVisible();
  await expect(page.getByRole('button', { name: '放弃', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '继续对答案' }).click();
  await expect(page.getByRole('button', { name: /^1/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: '再试一次提交' })).toBeVisible();
  await page.getByRole('button', { name: '再试一次提交' }).click();
  await expect(page.getByText(/已保存：1 题，做错 1 题/)).toBeVisible();
});
