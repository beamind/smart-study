// Checks a deployed trial proxy (docs/trial-proxy.md): status, translations, CORS, refused requests, and the
// requests used to check how the client IP is read. Spends three translations (together well under 0.01 yuan) and
// three of the IP's daily allowance.
//
//   node tools/check_trial.mjs https://<函数地址>/
const url = process.argv[2];
if (!url || !/^https?:\/\//.test(url)) {
  console.error('用法：node tools/check_trial.mjs <试用中转服务的地址>');
  process.exit(2);
}

const device = `check-${crypto.randomUUID()}`;
let failed = 0;
const check = (ok, what, detail = '') => {
  console.log(`${ok ? '通过' : '失败'}  ${what}${detail ? `：${detail}` : ''}`);
  if (!ok) failed++;
};
const json = async response => { try { return JSON.parse(await response.text()); } catch { return null; } };
const post = (body, headers = {}) => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });

const before = await fetch(`${url}?device=${device}`);
const status = await json(before);
check(before.ok && status?.remaining?.translate > 0, '查询剩余次数', JSON.stringify(status));
check(before.headers.get('access-control-allow-origin') === '*', '允许 App 跨域调用');

const options = await fetch(url, { method: 'OPTIONS', headers: { Origin: 'https://localhost', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type' } });
check(options.status === 204 && options.headers.get('access-control-allow-origin') === '*', '跨域预检（OPTIONS）', String(options.status));

const translated = await post({ device, task: 'translate', text: 'apple' });
const reply = await json(translated);
check(translated.ok && typeof reply?.reply === 'string' && reply.reply.length > 0, '翻译 apple', JSON.stringify(reply));
check(reply?.remaining?.translate === status?.remaining?.translate - 1, '翻译后次数减一', JSON.stringify(reply?.remaining));

const refused = await post({ device, task: 'chat', text: 'write a poem' });
check(refused.status === 400, '拒绝不支持的任务', String(refused.status));

// Two more translations, one claiming another address in X-Forwarded-For. If the proxy counts them under the
// same IP as the first, the address is read from what the gateway saw; a separate row means the client chose it.
const plain = await post({ device, task: 'translate', text: 'banana' });
const forged = await post({ device, task: 'translate', text: 'cherry' }, { 'X-Forwarded-For': '203.0.113.9' });
check(plain.ok && forged.ok, '再翻译两次（用于检查 IP）', `${plain.status} ${forged.status}`);

console.log(`\n设备标识：${device}`);
console.log('请到表格存储的 trial_counters 表里核对（见 docs/trial-proxy.md 第 7 步）：');
console.log(`  device:${device}:translate 应为 3；`);
console.log('  ip:<今天>:…:basic 的行里，应该只有一行的 n 比运行前多了 3。如果是两行分别多了 2 和 1，说明 IP 取错了，先不要发布。');
process.exit(failed ? 1 : 0);
