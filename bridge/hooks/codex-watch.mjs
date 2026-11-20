// Codex CLI / Desktop 状态采集：监听 ~/.codex/sessions 下最新的 rollout jsonl，
// 把事件映射成外屏状态后 POST 到 Bridge 的 /agent 接口。
//
// 为什么用「读日志」而不是 hook：
//   Codex 只有 notify（每轮结束时回调一次），拿不到「思考中 / 执行工具」这种过程态；
//   而 rollout 日志里 task_started / function_call / exec_command_begin 等事件是实时的。
//
// 启动：node bridge/hooks/codex-watch.mjs
//   BRIDGE=http://127.0.0.1:8765   Bridge 地址（默认本机）

import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.BRIDGE_PORT || process.env.PORT || 8765);
const BRIDGE = process.env.BRIDGE || `http://127.0.0.1:${PORT}`;
const GUARD_PORT = Number(process.env.WATCH_GUARD_PORT || (Number(new URL(BRIDGE).port || 80) + 1));
const CODEX_HOME = process.env.CODEX_HOME || path.join(os.homedir(), '.codex');
/** 默认只跟进启动后新增的事件；设 CODEX_REPLAY=1 可从头回放历史会话 */
const REPLAY = process.env.CODEX_REPLAY === '1';
const SESSIONS = path.join(CODEX_HOME, 'sessions');
const POLL_MS = 800;

/** Bridge 要求令牌：读 bridge/token.txt */
function readToken() {
  if (process.env.BRIDGE_TOKEN) return process.env.BRIDGE_TOKEN;
  try {
    return fs.readFileSync(path.join(HERE, '..', 'token.txt'), 'utf8').trim();
  } catch {
    return '';
  }
}

const TOKEN = readToken();

let currentFile = '';
let offset = 0;
let sessionId = '';
let lastSent = '';

function post(packet) {
  const body = JSON.stringify(packet);
  const url = new URL('/agent', BRIDGE);
  const req = http.request(
    {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        'x-bridge-token': TOKEN,
      },
      timeout: 1200,
    },
    (res) => res.resume(),
  );
  // Bridge 没起时静默失败，绝不阻塞 Codex
  req.on('error', () => {});
  req.on('timeout', () => req.destroy());
  req.end(body);
}

function emit(state, label) {
  const key = state + '|' + label;
  if (key === lastSent) return;
  lastSent = key;
  post({ agent: 'codex', session_id: sessionId.slice(0, 8) || 'codex', state, label });
}

/** 递归找出目录下最新修改的 rollout 文件 */
function newestRollout(dir) {
  let best = null;
  let stack = [dir];
  while (stack.length) {
    const d = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(d, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) stack.push(p);
      else if (e.name.endsWith('.jsonl')) {
        try {
          const st = fs.statSync(p);
          if (!best || st.mtimeMs > best.mtimeMs) best = { path: p, mtimeMs: st.mtimeMs, size: st.size };
        } catch {
          /* 文件被删，跳过 */
        }
      }
    }
  }
  return best;
}

const clip = (v, n = 28) => String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, n);

/** 单条 rollout 记录 → { state, label } */
function mapRecord(rec) {
  if (rec.type === 'session_meta') {
    sessionId = String(rec.payload?.session_id || rec.payload?.id || sessionId);
    return null;
  }

  const p = rec.payload || {};

  if (rec.type === 'event_msg') {
    switch (p.type) {
      case 'task_started':
        return { state: 'thinking', label: '开始处理' };
      case 'user_message':
        return { state: 'thinking', label: '收到指令：' + clip(p.message || p.text) };
      case 'agent_reasoning':
        return { state: 'thinking', label: clip(p.text) ? '思考：' + clip(p.text) : '思考中' };
      case 'agent_message':
        return { state: 'thinking', label: '整理回复…' };
      case 'exec_command_begin': {
        const cmd = Array.isArray(p.command) ? p.command.join(' ') : p.command;
        return { state: 'tool', label: '执行：' + clip(cmd) };
      }
      case 'exec_command_end':
        return p.exit_code && p.exit_code !== 0
          ? { state: 'error', label: '命令失败（exit ' + p.exit_code + '）' }
          : { state: 'thinking', label: '命令完成，继续思考' };
      case 'task_complete':
        return { state: 'done', label: clip(p.last_agent_message) || '任务完成' };
      case 'turn_aborted':
        return { state: 'error', label: '已中断' };
      case 'error':
        return { state: 'error', label: clip(p.message) || '出错' };
      default:
        return null;
    }
  }

  if (rec.type === 'response_item') {
    switch (p.type) {
      case 'function_call':
        return { state: 'tool', label: '执行：' + clip(p.name) + ' ' + clip(p.arguments) };
      case 'function_call_output':
        return { state: 'thinking', label: '工具返回，继续思考' };
      case 'local_shell_call':
        return { state: 'tool', label: '执行：本地命令' };
      case 'reasoning':
        return { state: 'thinking', label: '思考中' };
      default:
        return null;
    }
  }

  return null;
}

function consume(file) {
  let st;
  try {
    st = fs.statSync(file);
  } catch {
    return;
  }
  if (st.size < offset) offset = 0;          // 文件被截断 / 重建
  if (st.size === offset) return;

  const start = offset;
  const len = st.size - start;
  const buf = Buffer.alloc(len);
  const fd = fs.openSync(file, 'r');
  try {
    fs.readSync(fd, buf, 0, len, start);
  } finally {
    fs.closeSync(fd);
  }

  // 只消费到最后一个换行：剩下的半行留在文件里，下轮补齐后再解析。
  // 否则 offset 越过半行后，下一轮从行中间开始，这条记录就永久丢了。
  const lastNewline = buf.lastIndexOf(0x0a);
  if (lastNewline < 0) return;
  offset = start + lastNewline + 1;

  for (const line of buf.subarray(0, lastNewline + 1).toString('utf8').split('\n')) {
    if (!line.trim()) continue;
    let rec;
    try {
      rec = JSON.parse(line);
    } catch {
      continue;                              // 坏行（极少见）：跳过
    }
    const mapped = mapRecord(rec);
    if (mapped) emit(mapped.state, mapped.label);
  }
}

/** 从 rollout 首行 session_meta 补齐 sessionId（REPLAY=0 跳过历史时否则拿不到） */
function readHeaderSessionId(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const buf = Buffer.alloc(4096);
      const n = fs.readSync(fd, buf, 0, buf.length, 0);
      const text = buf.subarray(0, n).toString('utf8');
      const nl = text.indexOf('\n');
      const first = (nl >= 0 ? text.slice(0, nl) : text).trim();
      if (!first) return '';
      const rec = JSON.parse(first);
      if (rec && rec.type === 'session_meta') {
        return String(rec.payload?.session_id || rec.payload?.id || '');
      }
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    /* ignore */
  }
  return '';
}

function tick() {
  const newest = newestRollout(SESSIONS);
  if (!newest) return;
  if (newest.path !== currentFile) {
    currentFile = newest.path;
    // 默认跳过已有历史，避免一启动就把几天前的任务状态又推一遍
    offset = REPLAY ? 0 : newest.size;
    lastSent = '';
    sessionId = REPLAY ? '' : readHeaderSessionId(currentFile);
    console.log('[codex] watch', currentFile, REPLAY ? '(replay)' : '(follow new events)');
  }
  consume(currentFile);
}

console.log('[codex] watching', SESSIONS, '->', BRIDGE);

// 单实例保护：重复双击启动脚本时，后来的进程直接退出，避免重复推送
const guard = net.createServer();
guard.once('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log('[codex] another watcher already running, exit');
    process.exit(0);
  }
  console.error('[codex] guard error', e);
});
guard.listen(GUARD_PORT, '127.0.0.1', () => {
  setInterval(tick, POLL_MS);
  tick();
});
