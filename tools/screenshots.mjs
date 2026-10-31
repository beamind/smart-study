// Capture the main screens with a data folder injected into the in-memory storage.
//   node tools/screenshots.mjs <folder containing data/> [output dir]
// Starts its own Vite dev server and uses the fake model and silent speech. The data stays local:
// it is read from disk and injected into the page, nothing is uploaded and nothing is written back.
import { chromium } from '@playwright/test';
import { mkdirSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { createServer } from 'vite';

const [root = '.local/pc-test/smartstudy', out = 'artifacts/screenshots'] = process.argv.slice(2);
const server = await createServer({ server: { port: 5174, strictPort: true, host: '127.0.0.1' }, logLevel: 'error' });
await server.listen();
const base = 'http://127.0.0.1:5174/?storage=memory&speech=fake&ai=fake';

const seed = {};
const walk = dir => {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) walk(full);
    else seed[path.relative(root, full).split(path.sep).join('/')] = readFileSync(full, 'utf8');
  }
};
walk(path.join(root, 'data'));
mkdirSync(out, { recursive: true });

const devices = { tablet: { viewport: { width: 1280, height: 800 } }, phone: { viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } };
const browser = await chromium.launch();
try {
  for (const [device, options] of Object.entries(devices)) {
    const page = await (await browser.newContext(options)).newPage();
    await page.addInitScript(files => { window.__SMART_STUDY_SEED__ = files; }, seed);
    const shot = async name => { await page.waitForTimeout(300); await page.screenshot({ path: path.join(out, `${device}-${name}.png`) }); };
    const home = async () => { await page.goto(base); await page.getByRole('button', { name: '设置' }).waitFor(); };

    await home(); await shot('01-home');
    await page.getByRole('button', { name: /语文听写/ }).click(); await page.getByText(/错词本共/).waitFor(); await shot('02-dictation');
    await page.getByRole('button', { name: '手动输入' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('春天\n温暖\n柳树');
    await page.getByRole('button', { name: /下一步/ }).click(); await shot('03-dictation-confirm');
    await page.getByRole('button', { name: /开始语文听写/ }).click(); await page.getByText('请写下来').waitFor(); await shot('04-dictation-running');
    await home(); await page.getByRole('button', { name: /英语错题本/ }).click(); await page.locator('.question-row').first().waitFor(); await shot('05-error-book');
    await page.getByRole('button', { name: '练习', exact: true }).click(); await page.getByRole('button', { name: /抽 \d+ 道题开始/ }).click(); await shot('06-practice');
    await home(); await page.getByRole('button', { name: /学习记录/ }).click(); await page.getByRole('button', { name: '全部', exact: true }).click();
    await page.locator('.summary-grid').waitFor(); await shot('07-records');
    await page.getByRole('button', { name: '按词和题' }).click(); await shot('08-records-items');
    await home(); await page.getByRole('button', { name: '设置' }).click(); await shot('09-settings');
  }
} finally {
  await browser.close();
  await server.close();
}
console.log(`screenshots in ${out}`);
