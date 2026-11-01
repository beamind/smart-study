import { expect, test, type Page } from '@playwright/test';
import { appFile, at, open, seed, solveSum, tinyPng } from './helpers';

// Phase 2: dictation with in-memory storage, silent speech and the fake model (?ai=fake).

const user = { id: 'u1', name: '测试' };

async function finishRun(page: Page, count: number) {
  for (let i = 1; i < count; i++) await page.getByRole('button', { name: '下一个' }).click();
  await page.getByRole('button', { name: '写完了' }).click();
}

test.describe('dictation', () => {
  test.beforeEach(async ({ page }) => {
    await seed(page, { 'data/app.json': appFile([user]) });
    await open(page);
  });

  test('English: photo → crop → recognise → translations → read → mark → save → undo', async ({ page }) => {
    await page.getByRole('button', { name: /英语听写/ }).click();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: '相册' }).click();
    await (await chooser).setFiles({ name: 'words.png', mimeType: 'image/png', buffer: tinyPng });
    await page.getByRole('button', { name: '识别' }).click();

    await expect(page.getByRole('heading', { name: '确认词语' })).toBeVisible();
    await expect(page.getByLabel('第 1 个词', { exact: true })).toHaveValue('apple');
    await expect(page.getByLabel('第 2 个词的译文')).toHaveValue('译:take care');
    await page.getByRole('button', { name: '删除 vegetable' }).click();
    await page.getByRole('button', { name: /开始英语听写（3 个）/ }).click();

    await expect(page.getByText('正在朗读中文…')).toBeVisible();
    await expect(page.getByText('请写下来')).toBeVisible();
    await finishRun(page, 3);
    await page.getByRole('button', { name: /chef/ }).click();
    await page.getByRole('button', { name: '提交（写错 1 个）' }).click();
    await expect(page.getByText('已保存：写对 2 / 3 个')).toBeVisible();
    await expect(page.getByText('对 2 / 3', { exact: true })).toBeVisible();
    await expect(page.getByText(/错词本共 3 个词，其中 1 个错过/)).toBeVisible();

    await page.getByRole('button', { name: '撤销', exact: true }).click();
    await expect(page.getByText('已撤销这次听写')).toBeVisible();
    await expect(page.getByText('还没有记录。')).toBeVisible();
    await expect(page.getByText('错词本还是空的', { exact: false })).toBeVisible();
  });

  test('Chinese: manual input, all correct, then the word book: rename-merge, add, delete', async ({ page }) => {
    await page.getByRole('button', { name: /语文听写/ }).click();
    await page.getByRole('button', { name: '手动输入' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('春天，温暖\n柳树');
    await page.getByRole('button', { name: '下一步（3 个词）' }).click();
    await page.getByRole('button', { name: /开始语文听写（3 个）/ }).click();
    await finishRun(page, 3);
    await page.getByRole('button', { name: '提交（全对）' }).click();
    await expect(page.getByText('全对！3 个词都写对了')).toBeVisible();

    await page.getByRole('button', { name: /错词本/ }).click();
    await expect(page.getByText('3 个', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /温暖/ }).click();
    await page.getByRole('textbox', { name: '词语', exact: true }).fill('春天');
    await page.getByRole('button', { name: '保存' }).click();
    await expect(page.getByText('已和"春天"合并')).toBeVisible();
    await expect(page.getByRole('button', { name: /春天/ })).toContainText('错 0 / 1'); // both were in one dictation, which counts once
    await expect(page.getByText('2 个', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '添加词语' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('燕子 春天');
    await page.getByRole('button', { name: '添加 2 个' }).click();
    await expect(page.getByText('添加了 1 个，1 个已在错词本里')).toBeVisible();
    await page.getByRole('button', { name: '错过的' }).click();
    await expect(page.getByText('1 个', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '全部' }).click();
    await page.getByRole('button', { name: '选择' }).click();
    await page.getByRole('button', { name: /柳树/ }).click();
    await page.getByRole('button', { name: '删除 1 个' }).click();
    await solveSum(page);
    await page.getByRole('button', { name: '删除', exact: true }).click();
    await expect(page.getByText('已删除 1 个词')).toBeVisible();
    await expect(page.getByText('2 个', { exact: true })).toBeVisible();
  });

  test('an interrupted dictation can be resumed, even after reloading', async ({ page }) => {
    await page.getByRole('button', { name: /语文听写/ }).click();
    await page.getByRole('button', { name: '手动输入' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('一\n二\n三');
    await page.getByRole('button', { name: '下一步（3 个词）' }).click();
    await page.getByRole('button', { name: /开始语文听写/ }).click();
    await page.getByRole('button', { name: '下一个' }).click();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '先离开，下次接着听写' }).click();
    await expect(page.getByText('上次的听写还没完成（第 2 / 3 个）')).toBeVisible();

    await page.reload();
    await page.getByRole('button', { name: /语文听写/ }).click();
    await page.getByRole('button', { name: '继续听写' }).click();
    await expect(page.getByText(/第\s*2\s*\/ 3 个/)).toBeVisible();
    await page.getByRole('button', { name: '下一个' }).click();
    await page.getByRole('button', { name: '写完了' }).click();
    await page.keyboard.press('Escape');
    await expect(page.getByText('这次听写的结果还没有保存。')).toBeVisible();
    await page.getByRole('button', { name: '放弃这次听写' }).click();
    await expect(page.getByText('上次的听写还没', { exact: false })).toHaveCount(0);
  });

  test('pausing stops the countdown and reading', async ({ page }) => {
    await page.getByRole('button', { name: /语文听写/ }).click();
    await page.getByRole('button', { name: '手动输入' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('一\n二');
    await page.getByRole('button', { name: '下一步（2 个词）' }).click();
    await page.getByRole('button', { name: /开始语文听写/ }).click();
    // Pause between the first and the second reading of the word (1 s gap): the second one must not come.
    await expect(page.getByText(/正在朗读/)).toBeVisible();
    await page.getByRole('button', { name: '暂停' }).click();
    const spoken = await page.evaluate(() => window.__fakeSpeechCount ?? 0);
    expect(spoken).toBe(1);
    const left = await page.getByLabel(/剩余 \d+ 秒/).getAttribute('aria-label');
    await page.waitForTimeout(2200);
    expect(await page.evaluate(() => window.__fakeSpeechCount ?? 0)).toBe(spoken);
    await expect(page.getByLabel(/剩余 \d+ 秒/)).toHaveAttribute('aria-label', left!);
    await expect(page.getByRole('button', { name: '下一个' })).toBeDisabled();
    await page.getByRole('button', { name: '继续' }).click();
    await expect(page.getByRole('button', { name: '下一个' })).toBeEnabled();
  });
});

test('review dictation picks words from the word book, re-listen reuses a past dictation', async ({ page }) => {
  const words = ['春天', '夏天', '秋天', '冬天', '温暖', '柳树', '燕子', '桃花'];
  await seed(page, {
    'data/app.json': appFile([user]),
    'data/users/u1/dictation-chinese.json': {
      schema: 'smart-study.dictation/1', language: 'chinese',
      sessions: [{ id: 's1', kind: 'dictation', at, wordIds: words.map((_, i) => `w${i}`) }],
      words: words.map((text, i) => ({ id: `w${i}`, text, createdAt: at, attempts: [{ sessionId: 's1', correct: i % 2 === 0 }] })),
    },
  });
  await open(page);
  await page.getByRole('button', { name: /语文听写/ }).click();
  await expect(page.getByText(/错词本共 8 个词，其中 4 个错过/)).toBeVisible();
  await page.getByRole('button', { name: '5', exact: true }).click();
  await page.getByRole('button', { name: '抽 5 个词开始' }).click();
  await expect(page.locator('.confirm-row')).toHaveCount(5);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '放弃' }).click();
  await page.getByRole('button', { name: '再听一遍' }).click();
  await expect(page.locator('.confirm-row')).toHaveCount(8);
});

test('a draft in an unexpected shape shows the error page, which can discard it without touching other children', async ({ page }) => {
  await seed(page, { 'data/app.json': appFile([{ id: 'u1', name: '乐乐' }, { id: 'u2', name: '小红' }]) });
  await page.addInitScript(() => {
    if (sessionStorage.getItem('seeded-drafts')) return;
    sessionStorage.setItem('seeded-drafts', '1');
    // An older or broken shape: no words list.
    localStorage.setItem('CapacitorStorage.draft:dictation:u1:chinese', JSON.stringify({ phase: 'running', index: 0, startedAt: '2026-10-22T20:44:25+08:00' }));
    localStorage.setItem('CapacitorStorage.draft:dictation:u2:chinese', JSON.stringify({ kind: 'dictation', readMode: 'translation', words: [{ key: 'k', text: '春天', translation: '' }], index: 0, phase: 'answers', wrong: [], startedAt: '2026-10-22T20:44:25+08:00' }));
  });
  await open(page);
  page.on('pageerror', () => undefined);
  await page.getByRole('button', { name: /语文听写/ }).click();
  await expect(page.getByRole('heading', { name: '这个页面出错了' })).toBeVisible();
  await page.getByRole('button', { name: '放弃没做完的听写和练习…' }).click();
  await page.getByRole('button', { name: '放弃乐乐没做完的听写和练习' }).click();
  await expect(page.locator('.user-switch', { hasText: '乐乐' })).toBeVisible();
  await page.getByRole('button', { name: /语文听写/ }).click();
  await expect(page.getByRole('heading', { name: '新的听写' })).toBeVisible();
  // The other child's draft is still there.
  expect(await page.evaluate(() => localStorage.getItem('CapacitorStorage.draft:dictation:u2:chinese'))).not.toBeNull();
});
