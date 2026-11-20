// 轻量状态上报：给 Trae 规则用（比 PowerShell Invoke-RestMethod 快，约几十毫秒）
//
// 用法：node bridge/report.mjs <state> <label>
//   state: thinking | tool | waiting | done | error | idle
//   label: 不超过 32 字的说明
//
// 例：node bridge/report.mjs tool "执行：构建 hachimo APK"
// 任何失败都静默以 0 退出，不会阻塞调用方。

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const state = process.argv[2] || 'thinking';
const label = (process.argv.slice(3).join(' ') || '').slice(0, 48);
const port = Number(process.env.BRIDGE_PORT || process.env.PORT || 8765);

/** Bridge 要求令牌：读 bridge/token.txt */
function readToken() {
  if (process.env.BRIDGE_TOKEN) return process.env.BRIDGE_TOKEN;
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    return fs.readFileSync(path.join(here, 'token.txt'), 'utf8').trim();
  } catch {
    return '';
  }
}

const token = readToken();

const body = JSON.stringify({
  agent: process.env.AGENT_NAME || 'trae',
  session_id: process.env.AGENT_SESSION || 'general_app',
  state,
  label,
});

const req = http.request(
  {
    host: '127.0.0.1',
    port,
    path: '/agent',
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'content-length': Buffer.byteLength(body),
      'x-bridge-token': token,
    },
    timeout: 800,
  },
  (res) => {
    res.resume();
    res.on('end', () => process.exit(0));
  },
);
req.on('error', () => process.exit(0));
req.on('timeout', () => {
  req.destroy();
  process.exit(0);
});
req.end(body);
