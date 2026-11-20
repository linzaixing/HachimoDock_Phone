// 把 HachimoPhone Bridge 的 Claude Code hooks 合并进用户级 settings.json
//
// 用法：node bridge/hooks/install-claude-hooks.mjs
// 行为：读取 ~/.claude/settings.json（不存在则创建），合并 hooks，保留原有配置；
//      重复执行是幂等的（已存在同命令的 hook 会跳过）；改动前备份为 settings.json.bak。

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

// 相对本脚本定位 hook 脚本，仓库放在任何路径都能用
const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOOK_SCRIPT = path.join(HERE, 'claude-hook.mjs').replace(/\\/g, '/');
const settingsDir = path.join(os.homedir(), '.claude');
const settingsPath = path.join(settingsDir, 'settings.json');

const EVENTS = ['UserPromptSubmit', 'PreToolUse', 'PostToolUse', 'Notification', 'Stop', 'SubagentStop', 'SessionStart', 'SessionEnd'];

fs.mkdirSync(settingsDir, { recursive: true });

let settings = {};
let original = '';
if (fs.existsSync(settingsPath)) {
  original = fs.readFileSync(settingsPath, 'utf8');
  try {
    settings = original.trim() ? JSON.parse(original) : {};
  } catch (e) {
    console.error('[install] settings.json 不是合法 JSON，已中止：', e.message);
    process.exit(1);
  }
  try {
    fs.writeFileSync(settingsPath + '.bak', original, 'utf8');
  } catch (e) {
    // 备份失败（文件被占用/权限问题）不应阻塞 hook 安装
    console.warn('[install] 备份失败，继续安装：', e.message);
  }
}

settings.hooks = settings.hooks || {};

for (const event of EVENTS) {
  const command = `node "${HOOK_SCRIPT}" ${event}`;
  const groups = Array.isArray(settings.hooks[event]) ? settings.hooks[event] : [];
  // 仓库换路径后旧命令会失效：先把指向旧 claude-hook.mjs 的组清掉，避免日积月累
  const kept = groups.filter(
    (g) => !(g.hooks || []).some((h) => typeof h.command === 'string' && h.command.includes('claude-hook.mjs')),
  );
  kept.push({ hooks: [{ type: 'command', command, timeout: 5 }] });
  settings.hooks[event] = kept;
}

const next = JSON.stringify(settings, null, 2) + '\n';
if (next === original) {
  // 内容没变就不写，避免 Claude Code 正在运行时被文件锁挡住
  console.log('[install] hooks 已是最新，无需写入');
  process.exit(0);
}

try {
  fs.writeFileSync(settingsPath, next, 'utf8');
} catch (e) {
  console.error('[install] 写入 settings.json 失败（Claude Code 可能正占用该文件，关掉后重跑）：', e.message);
  process.exit(1);
}
console.log('[install] 已写入', settingsPath);
console.log('[install] hook 脚本', HOOK_SCRIPT);
