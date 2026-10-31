/**
 * Copy one user's data into another (new or existing) user, e.g. to try things on a test account.
 *
 *   npx tsx tools/clone-user.ts <smartstudy folder> <from name> <to name> [--replace]
 *
 * The source user's files are only read. Without --replace the target name must not exist yet;
 * with --replace an existing target user's data is overwritten (the old version goes to history/).
 * The target becomes the current user.
 */
import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { nowIso } from '../src/core/time';
import { emptyDictationFile, emptyErrorBookFile } from '../src/data/defaults';
import { paths, type DictationLanguage } from '../src/data/schema';
import { checkAppFile, checkDictationFile, checkErrorBookFile, type Checked } from '../src/data/validate';
import { JsonFiles } from '../src/storage/jsonFiles';
import { UndecodableTextError } from '../src/storage/folder';
import { NodeFolder } from './nodeFolder';

const args = process.argv.slice(2);
const replace = args.includes('--replace');
const [root, fromName, toName] = args.filter(arg => !arg.startsWith('--'));
if (!root || !fromName || !toName) {
  console.error('用法：npx tsx tools/clone-user.ts <smartstudy 文件夹> <源用户名> <目标用户名> [--replace]');
  process.exit(1);
}
if (!existsSync(path.join(root, 'data', 'app.json'))) {
  console.error(`${root} 下没有 data/app.json`);
  process.exit(1);
}

const files = new JsonFiles(new NodeFolder(path.resolve(root)));
const loaded = await files.load(paths.app, checkAppFile);
if (loaded.status !== 'ok') throw new Error(`无法读取 app.json：${loaded.status === 'unusable' ? loaded.error : '文件不存在'}`);
const app = loaded.value;
/** Set when loading a file already repaired it from a temp file or history version. */
let recovered = false;
if (loaded.recoveredFrom) { recovered = true; console.log(`  ${paths.app} 已从 ${loaded.recoveredFrom} 恢复（${loaded.problem}）`); }

const source = app.users.find(user => user.name === fromName);
if (!source) throw new Error(`找不到用户"${fromName}"，现有用户：${app.users.map(user => user.name).join('、')}`);
let target = app.users.find(user => user.name === toName.trim());
if (target && !replace) throw new Error(`已经有用户"${toName}"。要用"${fromName}"的数据覆盖它，请加 --replace`);
if (target?.id === source.id) throw new Error('源用户和目标用户相同');
target ??= { id: randomUUID(), name: toName.trim(), createdAt: nowIso() };
// Checked before any data file is written, so a bad target name never leaves a copy that app.json does not list.
const users = app.users.some(user => user.id === target!.id) ? app.users : [...app.users, target];
const nextApp = checkAppFile({ ...app, users, currentUserId: target.id });
if (!nextApp.ok) throw new Error(`目标用户无法加入 app.json，没有复制任何数据：${nextApp.error}`);

type Copy = { from: string; to: string; check: (input: unknown) => Checked<unknown>; empty: () => unknown };
const copies: Copy[] = [
  ...(['chinese', 'english'] as DictationLanguage[]).map(language => ({
    from: paths.dictation(source.id, language), to: paths.dictation(target!.id, language), check: checkDictationFile, empty: () => emptyDictationFile(language),
  })),
  { from: paths.errorBook(source.id), to: paths.errorBook(target.id), check: checkErrorBookFile, empty: emptyErrorBookFile },
];

// Source files are read directly, without the recovery in JsonFiles.load, so the source account is never changed.
// All of them are checked before any target file is loaded, because loading a target may already repair it.
const folder = files.folder;
const openInApp = '请先在 App 里打开这个账号，让它从临时文件或历史版本恢复';
const readSource = async (relative: string, check: (input: unknown) => Checked<unknown>) => {
  let text: string | null;
  try { text = await folder.readText(relative); } catch (error) {
    if (error instanceof UndecodableTextError) throw new Error(`源文件不是 UTF-8 文本，没有复制任何数据（${openInApp}）：${relative}`);
    throw error;
  }
  if (text === null) {
    const leftovers = await folder.list(path.posix.dirname(relative)).then(entries => entries.some(entry => entry.name === `${path.posix.basename(relative)}.tmp`));
    if (leftovers || (await files.history(relative)).length > 0) throw new Error(`源文件缺失，但有临时文件或历史版本，没有复制任何数据（${openInApp}）：${relative}`);
    return { status: 'missing' as const };
  }
  let parsed: Checked<unknown>;
  try { parsed = check(JSON.parse(text.replace(/^\uFEFF/, ''))); } catch (error) { parsed = { ok: false, reason: 'invalid', error: (error as Error).message }; }
  if (!parsed.ok && parsed.reason === 'newer') throw new Error(`源文件来自更新版本的 App，没有复制任何数据（请先升级 App 和本工具）：${relative}：${parsed.error}`);
  if (!parsed.ok) throw new Error(`源文件无法使用，没有复制任何数据（${openInApp}）：${relative}：${parsed.error}`);
  return { status: 'ok' as const, value: parsed.value };
};
const sources: { copy: Copy; data: Awaited<ReturnType<typeof readSource>> }[] = [];
for (const copy of copies) sources.push({ copy, data: await readSource(copy.from, copy.check) });

// A target file that cannot be read (or is from a newer app) is not overwritten.
const plan: { copy: Copy; value: unknown }[] = [];
for (const { copy, data } of sources) {
  const existing = await files.load(copy.to, copy.check);
  if (existing.status === 'unusable') {
    const why = existing.reason === 'newer' ? '来自更新版本的 App' : '无法使用';
    throw new Error(`目标文件${why}，没有复制任何数据${recovered ? '（读取时已恢复的文件见上方提示）' : ''}：${copy.to}：${existing.error}`);
  }
  if (existing.status === 'ok' && existing.recoveredFrom) { recovered = true; console.log(`  ${copy.to} 已从 ${existing.recoveredFrom} 恢复（${existing.problem}）`); }
  if (data.status === 'missing') {
    // --replace resets the target completely, so a type the source does not have becomes empty.
    if (existing.status === 'ok') plan.push({ copy, value: copy.empty() });
    continue;
  }
  plan.push({ copy, value: data.value });
}
for (const { copy, value } of plan) {
  await files.save(copy.to, value);
  console.log(`  已写入 ${copy.to}`);
}

await files.save(paths.app, nextApp.value);
console.log(`完成："${target.name}"现在有一份"${source.name}"的数据，并已设为当前用户。`);
