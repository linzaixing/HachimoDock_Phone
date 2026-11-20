// Trae 桌面端活动监听（自动兜底）：
//   Trae 没有 hook，但每次对话/工具调用都会写 `ModularData/ai-agent/database.db-wal`，
//   这里只盯它的修改时间，判断「Trae 正在交互 / 已空闲」，推给 Bridge。
//
// 粒度是粗的（只知道有没有活动，不知道具体在干什么），
// 精细文案仍由 `.trae/rules/hachimodo-bridge.md` 让模型自己上报。
//
// 用法：node bridge/hooks/trae-watch.mjs
//   BRIDGE=http://127.0.0.1:8765   空闲判定：25s 无写入

import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.BRIDGE_PORT || process.env.PORT || 8765);
const BRIDGE = process.env.BRIDGE || `http://127.0.0.1:${PORT}`;
const GUARD_PORT = Number(process.env.TRAE_GUARD_PORT || 8768);
const IDLE_AFTER_MS = Number(process.env.TRAE_IDLE_MS || 25000);
const POLL_MS = 700;

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

const appData = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
// Trae CN / Trae 国际版安装目录不同，两个都盯；少一个就会「看起来有 Trae 但外屏一直待机」
const walPaths = ['Trae CN', 'Trae'].map((dir) =>
  path.join(appData, dir, 'ModularData', 'ai-agent', 'database.db-wal'));

let lastMtime = 0;
let active = false;
let lastChangeAt = 0;
/** 最近 6s 内的写入时刻：Trae 有后台心跳（约 10 分钟一次），单次写入不算「在交互」 */
const changeTimes = [];
const BURST_WINDOW_MS = 6000;
const BURST_COUNT = 2;

function post(state, label) {
  const body = JSON.stringify({
    agent: 'trae',
    session_id: 'trae-desktop',
    state,
    label,
  });
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
  req.on('error', () => {});
  req.on('timeout', () => req.destroy());
  req.end(body);
}

function mtimeOf(p) {
  try {
    return fs.statSync(p).mtimeMs;
  } catch {
    return 0;
  }
}

/** 如果 Bridge 上已经有 45s 内的 trae 详细状态（规则上报的），就不要用粗粒度文案盖掉它 */
async function hasFreshTraeState() {
  try {
    const res = await fetch(new URL('/health', BRIDGE), {
      headers: { 'x-bridge-token': TOKEN },
    });
    const data = await res.json();
    const list = Array.isArray(data && data.states) ? data.states : [];
    const s = list.find((item) => item && item.agent === 'trae')
      || (data && data.state && data.state.agent === 'trae' ? data.state : null);
    return !!(s && Date.now() - (s.ts || 0) < 45000);
  } catch {
    return false;
  }
}

/** 任务结束后先报 done（手机要放汪汪叫 + 完成动画），过一会儿再回待机 */
let doneUntil = 0;
const DONE_HOLD_MS = 8000;

function tick() {
  const now = Date.now();
  const mtime = Math.max(...walPaths.map(mtimeOf));

  if (mtime && mtime !== lastMtime) {
    lastMtime = mtime;
    lastChangeAt = now;
    changeTimes.push(now);
    while (changeTimes.length && now - changeTimes[0] > BURST_WINDOW_MS) changeTimes.shift();
    if (!active && changeTimes.length >= BURST_COUNT) {
      active = true;
      doneUntil = 0;
      hasFreshTraeState().then((fresh) => {
        if (fresh) return;            // 已有更详细的上报，跳过
        post('thinking', 'Trae 思考中');
        console.log('[trae] active');
      });
    }
    return;
  }

  if (active && now - lastChangeAt > IDLE_AFTER_MS) {
    active = false;
    doneUntil = now + DONE_HOLD_MS;
    post('done', '任务完成');
    console.log('[trae] done');
    return;
  }

  if (!active && doneUntil && now > doneUntil) {
    doneUntil = 0;
    post('idle', '待机');
    console.log('[trae] idle');
  }
}

const guard = net.createServer();
guard.once('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log('[trae] another watcher already running, exit');
    process.exit(0);
  }
  console.error('[trae] guard error', e);
});
guard.listen(GUARD_PORT, '127.0.0.1', () => {
  console.log('[trae] watching', walPaths[0], '->', BRIDGE);
  setInterval(tick, POLL_MS);
  tick();
});
