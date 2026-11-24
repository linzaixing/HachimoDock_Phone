// 把 HachimoPhone Bridge 的 WorkBuddy 状态 hooks 合并进 WorkBuddy 的 settings.json
//
// 用法：node bridge/hooks/install-workbuddy-hooks.mjs
// 行为：读取 ~/.workbuddy/settings.json（可用 WORKBUDDY_CONFIG_DIR 覆盖），
//      合并 hooks，保留原有配置；重复执行幂等；首次改动前备份为
//      settings.json.pet-manager-backup。
//      未安装/未启动过 WorkBuddy 或用户设置了 disableAllHooks 时只跳过，不创建配置。
// 参考：HachimoDock/pc/src-tauri/bridge/hooks/workbuddy-install.js

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOOK_SCRIPT = path.join(HERE, 'workbuddy-hook.mjs').replace(/\\/g, '/');
const MARKER = 'workbuddy-hook.mjs';

const root = (process.env.WORKBUDDY_CONFIG_DIR || '').trim()
  || path.join(os.homedir(), '.workbuddy');
const settingsPath = path.join(root, 'settings.json');

const EVENTS = [
  'SessionStart', 'SessionEnd', 'UserPromptSubmit', 'PreToolUse', 'PostToolUse',
  'PostToolUseFailure', 'PermissionRequest', 'Notification', 'Stop', 'StopFailure', 'PreCompact',
];

/** 首次启动 WorkBuddy 之前没有配置目录，此时不替用户创建 */
if (!fs.existsSync(root)) {
  console.log('[workbuddy] 未检测到', root, '，请先启动一次 WorkBuddy 再运行');
  process.exit(0);
}

let original = '';
let settings = {};
if (fs.existsSync(settingsPath)) {
  original = fs.readFileSync(settingsPath, 'utf8');
  try {
    settings = original.trim() ? JSON.parse(original) : {};
  } catch (e) {
    console.error('[workbuddy] settings.json 不是合法 JSON，已中止：', e.message);
    process.exit(1);
  }
}

if (settings.disableAllHooks === true) {
  console.log('[workbuddy] 已启用 disableAllHooks，保持用户设置不变');
  process.exit(0);
}

if (settings.hooks != null && (typeof settings.hooks !== 'object' || Array.isArray(settings.hooks))) {
  console.error('[workbuddy] hooks 字段格式异常，未做修改');
  process.exit(1);
}
settings.hooks = settings.hooks || {};

const command = `"${process.execPath}" "${HOOK_SCRIPT}"`;
let changed = false;

for (const event of EVENTS) {
  if (settings.hooks[event] != null && !Array.isArray(settings.hooks[event])) {
    console.error(`[workbuddy] hooks.${event} 格式异常，未做修改`);
    process.exit(1);
  }
  const entries = settings.hooks[event] || (settings.hooks[event] = []);
  const own = entries
    .flatMap((entry) => (Array.isArray(entry && entry.hooks) ? entry.hooks : []))
    .find((hook) => hook && hook.type === 'command'
      && typeof hook.command === 'string' && hook.command.includes(MARKER));

  if (own) {
    if (own.command !== command) {
      own.command = command;
      changed = true;
    }
  } else {
    entries.push({ matcher: '', hooks: [{ type: 'command', command, timeout: 2 }] });
    changed = true;
  }
}

if (!changed) {
  console.log('[workbuddy] hooks 已是最新，无需写入');
  process.exit(0);
}

// 首次改动前留一份恢复副本；已存在则不覆盖
if (original) {
  try {
    fs.writeFileSync(`${settingsPath}.pet-manager-backup`, original, { flag: 'wx', mode: 0o600 });
  } catch (e) {
    if (e.code !== 'EEXIST') throw e;
  }
}

const temporary = `${settingsPath}.hachimophone-${process.pid}.tmp`;
try {
  fs.writeFileSync(temporary, JSON.stringify(settings, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  fs.renameSync(temporary, settingsPath);
} finally {
  try {
    fs.unlinkSync(temporary);
  } catch {
    /* 临时文件可能已被 rename 掉 */
  }
}

console.log('[workbuddy] 已写入', settingsPath);
console.log('[workbuddy] hook 脚本', HOOK_SCRIPT);
