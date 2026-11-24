// 一键启动 PC 端全部组件（单窗口）：
//   1. 安装/刷新 Claude Code hooks（幂等，仓库路径变了也会自动纠正）
//   2. 安装/刷新 WorkBuddy hooks（未安装 WorkBuddy 时自动跳过）
//   3. 启动 Bridge（8765；若端口已被占用则复用，不重复启动）
//   4. 启动 Codex / Trae 状态监听（自带单实例守卫）
//
// 用法：node bridge/start-all.mjs      （Windows 可双击 bridge/start-bridge.bat）
// Ctrl+C 会一起停掉子进程。

import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const PORT = Number(process.env.PORT || 8765);

const children = new Set();
let shuttingDown = false;

const stamp = () => new Date().toLocaleTimeString('zh-CN', { hour12: false });
const log = (name, line) => {
  // 子进程自己已经带了 [bridge]/[codex] 前缀时就不再重复加
  const text = line.startsWith('[') ? line : `[${name}] ${line}`;
  process.stdout.write(`${stamp()} ${text}\n`);
};

function isPortBusy(port) {
  return new Promise((resolve) => {
    const socket = net.connect(port, '127.0.0.1');
    const done = (busy) => {
      socket.destroy();
      resolve(busy);
    };
    socket.once('connect', () => done(true));
    socket.once('error', () => done(false));
    setTimeout(() => done(false), 800);
  });
}

function pipeOutput(name, child) {
  for (const stream of [child.stdout, child.stderr]) {
    stream.setEncoding('utf8');
    let buffer = '';
    stream.on('data', (chunk) => {
      buffer += chunk;
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) if (line.trim()) log(name, line);
    });
  }
}

/** 每个子进程连续失败次数，成功跑过 30s 会清零 */
const failures = new Map();

function startChild(name, script, { restart = true } = {}) {
  const startedAt = Date.now();
  const child = spawn(process.execPath, [script], { cwd: ROOT, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  children.add(child);
  pipeOutput(name, child);

  child.on('exit', (code) => {
    children.delete(child);
    if (shuttingDown) return;
    const lived = Date.now() - startedAt;
    if (lived > 30000) failures.set(name, 0);
    log(name, `已退出（code=${code}，运行 ${Math.round(lived / 1000)}s）`);

    // code=0 通常是单实例守卫发现「已有同类进程」，属于正常退出，重启只会死循环
    if (code === 0) {
      log(name, '正常退出，不再重启');
      return;
    }
    if (!restart) return;

    const attempts = (failures.get(name) || 0) + 1;
    failures.set(name, attempts);
    if (attempts > 5) {
      log(name, '连续失败 5 次，放弃自动重启，请检查日志');
      return;
    }
    const delay = Math.min(30000, 3000 * attempts);
    log(name, `${Math.round(delay / 1000)}s 后重启（第 ${attempts} 次）`);
    setTimeout(() => {
      if (shuttingDown) return;
      startChild(name, script);
    }, delay);
  });
  return child;
}

function runOnce(name, script) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [script], { cwd: ROOT, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
    pipeOutput(name, child);
    child.on('exit', (code) => resolve(code === 0));
  });
}

async function main() {
  log('start-all', '安装/刷新 Claude Code hooks…');
  const hooksOk = await runOnce('hooks', 'bridge/hooks/install-claude-hooks.mjs');
  log('start-all', hooksOk ? 'Claude hooks 就绪' : 'Claude hooks 安装失败（不影响其它组件）');

  log('start-all', '检查 WorkBuddy hooks…');
  const workbuddyOk = await runOnce('workbuddy', 'bridge/hooks/install-workbuddy-hooks.mjs');
  log('start-all', workbuddyOk
    ? 'WorkBuddy hooks 检查完成（未安装 WorkBuddy 时会自动跳过）'
    : 'WorkBuddy hooks 安装失败（不影响其它组件）');

  if (await isPortBusy(PORT)) {
    log('start-all', `端口 ${PORT} 已在监听，复用现有 Bridge`);
  } else {
    log('start-all', `启动 Bridge（${PORT}）…`);
    startChild('bridge', 'bridge/src/server.mjs');
  }

  // 监听器自带单实例守卫：先探端口，已有实例就直接跳过，避免起一个又立刻退出的空转
  const codexGuard = Number(process.env.WATCH_GUARD_PORT || PORT + 1);
  if (await isPortBusy(codexGuard)) {
    log('start-all', 'Codex 监听已在运行，跳过');
  } else {
    log('start-all', '启动 Codex 状态监听…');
    startChild('codex', 'bridge/hooks/codex-watch.mjs');
  }

  const traeGuard = Number(process.env.TRAE_GUARD_PORT || 8768);
  if (await isPortBusy(traeGuard)) {
    log('start-all', 'Trae 监听已在运行，跳过');
  } else {
    log('start-all', '启动 Trae 活动监听…');
    startChild('trae', 'bridge/hooks/trae-watch.mjs');
  }

  log('start-all', '全部启动完成。手机 App 地址留空即可自动发现本机。');

  // 重复双击时所有组件都被复用，本进程没有子进程可管，提示后自行退出
  if (children.size === 0) {
    log('start-all', '所有组件已在运行，本窗口 5 秒后自动关闭（无需重复启动）');
    setTimeout(() => process.exit(0), 5000);
  }
}

function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  log('start-all', '正在停止…');
  for (const child of children) {
    try {
      child.kill();
    } catch {
      /* ignore */
    }
  }
  setTimeout(() => process.exit(0), 300);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('SIGHUP', shutdown);

main();
