// Emulator smoke test for the SAF data folder (docs/decisions/002):
// install -> authorise Documents -> read existing data -> write -> uninstall -> files remain
// -> reinstall -> authorise again -> same data.
//
//   node tools/smoke_android.mjs emulator-5554 [apk] [data folder containing data/]
//
// Without a data folder, the test builds its own data: two children with about 1,900 words and
// 500 questions, about the size of a year of use. A given folder needs at least two users, and the
// current user needs at least one English error-book question.
// Only emulator serials are accepted: the test uninstalls the app and replaces
// /sdcard/Documents/smartstudy on the device.
import { _android } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const version = JSON.parse(readFileSync('package.json', 'utf8')).version;
const [serial, apk = `artifacts/smart-study-${version}-debug.apk`, dataArg] = process.argv.slice(2);
if (!/^emulator-\d+$/.test(serial ?? '')) { console.error('用法：node tools/smoke_android.mjs emulator-5554 [apk] [数据文件夹]'); process.exit(1); }
// Checked before touching the emulator: a data folder given as the second argument would otherwise be taken for the APK.
if (!apk.endsWith('.apk') || (existsSync(apk) && !statSync(apk).isFile())) { console.error(`第二个参数应是 APK 文件：${apk}（数据文件夹写在第三个参数）`); process.exit(1); }
// The same places tools/build_android.ps1 looks in.
const adbName = process.platform === 'win32' ? 'adb.exe' : 'adb';
const sdk = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, path.join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk')]
  .find(dir => dir && existsSync(path.join(dir, 'platform-tools', adbName)));
if (!sdk) { console.error('找不到 adb：请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT'); process.exit(1); }
const adbPath = path.join(sdk, 'platform-tools', adbName);
const pkg = 'com.smartstudy.dev'; // debug build (applicationIdSuffix .dev)
const out = 'artifacts/android-smoke';
mkdirSync(out, { recursive: true });

const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const step = text => console.log(`\n▶ ${text}`);
const check = (ok, text) => { console.log(`  ${ok ? '✓' : '✗'} ${text}`); if (!ok) throw new Error(text); };

/** Find a node in the native UI (system folder picker) and tap it. */
async function tapNative(pattern, { timeoutMs = 15000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml');
    const xml = adb('shell', 'cat', '/sdcard/ui.xml');
    for (const node of xml.matchAll(/<node [^>]*>/g)) {
      const attrs = Object.fromEntries([...node[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
      if (pattern.test(attrs.text ?? '') || pattern.test(attrs['content-desc'] ?? '')) {
        const [, x1, y1, x2, y2] = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(attrs.bounds).map(Number);
        adb('shell', 'input', 'tap', String(Math.round((x1 + x2) / 2)), String(Math.round((y1 + y2) / 2)));
        return attrs.text || attrs['content-desc'];
      }
    }
    await sleep(700);
  }
  throw new Error(`系统界面上找不到 ${pattern}`);
}

/** Connect Playwright to the WebView of the app's current process (not a stale one from before a reinstall). */
let device;
async function connect() {
  if (!device) {
    device = (await _android.devices()).find(item => item.serial() === serial);
    check(!!device, `Playwright 连上了 ${serial}`);
  }
  let webView;
  for (let i = 0; i < 60 && !webView; i++) {
    const pid = Number(adb('shell', 'pidof', pkg).trim() || 0);
    webView = pid ? device.webViews().find(view => view.pkg() === pkg && view.pid() === pid) : undefined;
    if (!webView) await sleep(500);
  }
  check(!!webView, '连上了当前进程的 WebView');
  const page = await webView.page();
  await page.waitForLoadState('domcontentloaded');
  return { browser: { close: async () => undefined }, page };
}

const launch = () => adb('shell', 'am', 'start', '-W', '-n', `${pkg}/com.smartstudy.MainActivity`);
const deviceFile = file => adb('shell', 'cat', `/sdcard/Documents/smartstudy/${file}`);

/** Constructed data in the app's format (docs/data-contract.md): no real children's data needed. */
function buildFixture(root) {
  rmSync(root, { recursive: true, force: true });
  const at = day => `2026-${String(1 + Math.floor(day / 28)).padStart(2, '0')}-${String(1 + (day % 28)).padStart(2, '0')}T19:00:00+08:00`;
  const write = (relative, value) => { const target = path.join(root, 'data', relative); mkdirSync(path.dirname(target), { recursive: true }); writeFileSync(target, JSON.stringify(value)); };
  write('app.json', { schema: 'smart-study.app/1', users: [{ id: 'u-main', name: '小明', createdAt: at(0) }, { id: 'u-guest', name: '游客', createdAt: at(0) }], currentUserId: 'u-main' });
  const dictation = (language, count, prefix) => {
    const sessions = Array.from({ length: Math.ceil(count / 10) }, (_, i) => ({ id: `${prefix}s${i}`, kind: 'dictation', at: at(i % 300), wordIds: [], ...(language === 'english' ? { readMode: 'translation' } : {}) }));
    const words = Array.from({ length: count }, (_, i) => {
      const session = sessions[Math.floor(i / 10)];
      session.wordIds.push(`${prefix}w${i}`);
      return { id: `${prefix}w${i}`, text: language === 'chinese' ? `词语${i}` : `word ${i}`, ...(language === 'english' ? { translation: `译文${i}` } : {}),
        createdAt: session.at, attempts: [{ sessionId: session.id, correct: i % 3 !== 0 }] };
    });
    return { schema: 'smart-study.dictation/1', language, sessions, words };
  };
  write('users/u-main/dictation-chinese.json', dictation('chinese', 1200, 'c'));
  write('users/u-main/dictation-english.json', dictation('english', 700, 'e'));
  const sessions = Array.from({ length: 100 }, (_, i) => ({ id: `qs${i}`, kind: 'capture', at: at(i * 3), questionIds: [] }));
  const questions = Array.from({ length: 500 }, (_, i) => {
    const session = sessions[Math.floor(i / 5)];
    session.questionIds.push(`q${i}`);
    return { id: `q${i}`, text: `Question ${i}: Tom ____ (go) to school by __bus__.\nA. go B. goes C. going`, answer: 'B', origin: 'photo', createdAt: session.at, attempts: [{ sessionId: session.id, correct: false }] };
  });
  write('users/u-main/error-book.json', { schema: 'smart-study.error-book/1', sessions, questions });
  write('users/u-guest/dictation-chinese.json', dictation('chinese', 14, 'g'));
}

const dataRoot = dataArg ?? path.join(out, 'fixture');
if (!dataArg) buildFixture(dataRoot);
const seeded = JSON.parse(readFileSync(path.join(dataRoot, 'data', 'app.json'), 'utf8').replace(/^\uFEFF/, ''));
const mainUser = seeded.users.find(user => user.id === seeded.currentUserId)?.name;
const otherUser = seeded.users.find(user => user.id !== seeded.currentUserId)?.name;
if (!mainUser || !otherUser) { console.error('数据文件夹需要至少两个用户，并且设置了当前用户'); process.exit(1); }
// The test opens the current user's English error book and waits for a question.
const book = path.join(dataRoot, 'data', 'users', seeded.currentUserId, 'error-book.json');
if (!existsSync(book) || !JSON.parse(readFileSync(book, 'utf8').replace(/^\uFEFF/, '')).questions?.length) {
  console.error(`当前用户的英语错题本需要至少一道题：${book}`);
  process.exit(1);
}

async function authorise(page) {
  await page.getByRole('button', { name: '选择文件夹' }).click();
  const used = await tapNative(/^(USE THIS FOLDER|Use this folder|使用此文件夹)$/i);
  console.log(`  点了系统按钮：${used}`);
  console.log(`  点了系统按钮：${await tapNative(/^(ALLOW|Allow|允许)$/i)}`);
}

/** Skip the API key step: "先免费试用" in builds with the trial proxy, "以后再填" without it. Spends nothing. */
async function skipKey(page) {
  await page.getByRole('button', { name: /^(先免费试用|以后再填)$/ }).click({ timeout: 20000 });
}

let browser;
try {
  step('准备：安装 APK，把测试数据放进 Documents/smartstudy');
  if (!existsSync(apk)) throw new Error(`找不到 ${apk}，先运行 npm run android:build`);
  try { adb('uninstall', pkg); } catch { /* not installed */ }
  adb('shell', 'rm', '-rf', '/sdcard/Documents/smartstudy');
  adb('shell', 'mkdir', '-p', '/sdcard/Documents/smartstudy');
  adb('push', path.join(dataRoot, 'data'), '/sdcard/Documents/smartstudy/');
  adb('install', apk);
  check(adb('shell', 'ls', '/sdcard/Documents/smartstudy/data').includes('app.json'), '数据已放到设备上');

  step('首次启动：授权 Documents，读取已有数据');
  launch();
  const first = await connect();
  browser = first.browser;
  let page = first.page;
  await authorise(page);
  await skipKey(page);
  await page.locator('.user-switch', { hasText: mainUser }).waitFor({ timeout: 20000 });
  check(true, `授权后读到了用户 ${mainUser}，没有重新创建数据`);
  await page.screenshot({ path: `${out}/01-home.png` });

  step('读取大文件：学习记录和错题本');
  let started = Date.now();
  await page.getByRole('button', { name: /学习记录/ }).click();
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await page.locator('.summary-grid').waitFor({ timeout: 30000 });
  const summary = (await page.locator('.summary-grid').textContent()).replace(/\s+/g, ' ');
  check(true, `学习记录读取三个数据文件用时 ${Date.now() - started} ms：${summary}`);
  await page.screenshot({ path: `${out}/02-records.png` });
  await page.getByRole('button', { name: '返回' }).click();
  started = Date.now();
  await page.getByRole('button', { name: /英语错题本/ }).click();
  await page.locator('.question-row').first().waitFor({ timeout: 30000 });
  check(true, `错题本显示 ${await page.getByText(/^\d+ 道$/).textContent()}，用时 ${Date.now() - started} ms`);
  await page.getByRole('button', { name: '返回' }).click();

  step(`写入：切换到"${otherUser}"，再添加一个孩子`);
  const homeUser = name => page.locator('.user-switch', { hasText: name });
  await homeUser(mainUser).click();
  await page.getByRole('button', { name: `${otherUser.slice(0, 1)} ${otherUser}` }).click();
  await homeUser(otherUser).waitFor();
  await homeUser(otherUser).click();
  await page.getByRole('button', { name: '添加孩子' }).click();
  await page.getByLabel('名字').fill('模拟器测试');
  await page.getByRole('button', { name: '保存' }).click();
  await page.getByText('模拟器测试', { exact: true }).waitFor();
  const appText = deviceFile('data/app.json');
  const app = JSON.parse(appText);
  check(app.users.some(user => user.name === '模拟器测试'), 'app.json 已写入新用户');
  check(app.users.find(user => user.id === app.currentUserId)?.name === otherUser, `app.json 当前用户是${otherUser}`);
  const history = adb('shell', 'ls', '/sdcard/Documents/smartstudy/data/history/app').trim().split(/\s+/).filter(Boolean);
  check(history.length >= 1, `旧版本已存入 history/app（${history.length} 个）`);
  check(!adb('shell', 'ls', '/sdcard/Documents/smartstudy/data').includes('app.json.tmp'), '没有残留临时文件');
  await browser.close(); browser = undefined;

  step('卸载 App：数据文件仍在');
  adb('uninstall', pkg);
  const after = deviceFile('data/app.json');
  check(after === appText, '卸载后 Documents/smartstudy/data/app.json 仍在且内容不变');

  step('重新安装：重新授权后数据完整');
  adb('install', apk);
  launch();
  ({ browser, page } = await connect());
  await page.getByRole('button', { name: '选择文件夹' }).waitFor({ timeout: 20000 });
  check(true, '重装后要求重新授权（授权随卸载失效，符合预期）');
  await authorise(page);
  await skipKey(page);
  await page.locator('.user-switch', { hasText: otherUser }).waitFor({ timeout: 20000 });
  check(true, `重装后读到了卸载前写入的数据（当前用户仍是${otherUser}）`);
  await page.locator('.user-switch', { hasText: otherUser }).click();
  await page.getByText('模拟器测试', { exact: true }).waitFor();
  await page.screenshot({ path: `${out}/03-after-reinstall.png` });

  step('冷启动：已授权时直接进入首页');
  await browser.close(); browser = undefined;
  adb('shell', 'am', 'force-stop', pkg);
  launch();
  ({ browser, page } = await connect());
  await page.locator('.user-switch', { hasText: otherUser }).waitFor({ timeout: 20000 });
  check(true, '强制结束后重新打开，不再询问文件夹');

  writeFileSync(`${out}/result.txt`, `passed ${new Date().toISOString()}\n`);
  console.log('\n全部通过');
} catch (error) {
  console.error(`\n失败：${error.message}`);
  try { execFileSync(adbPath, ['-s', serial, 'shell', 'screencap', '-p', '/sdcard/fail.png']); adb('pull', '/sdcard/fail.png', `${out}/fail.png`); } catch { /* ignore */ }
  process.exitCode = 1;
} finally {
  // Closing the Playwright device connection can hang; never let that keep the script running.
  await Promise.race([device?.close().catch(() => undefined), sleep(5000)]);
  process.exit(process.exitCode ?? 0);
}
