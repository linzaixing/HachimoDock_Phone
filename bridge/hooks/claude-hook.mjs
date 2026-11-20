// Claude Code / 通用 Agent 的 hook 转发脚本
//
// 用法（在 Claude Code 的 settings.json 里注册）：
//   node "F:/vibe_coding/general_app/bridge/hooks/claude-hook.mjs" PreToolUse
//
// Claude Code 会把事件 JSON 从 stdin 传进来，这里加上事件名转发给本地 Bridge。
// 任何异常都吞掉并以 0 退出，绝不影响 Claude Code 本身。

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const event = process.argv[2] || '';
const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Bridge 要求令牌：读 bridge/token.txt（Bridge 首次启动会自动生成） */
function readToken() {
  if (process.env.BRIDGE_TOKEN) return process.env.BRIDGE_TOKEN;
  try {
    return fs.readFileSync(path.join(HERE, '..', 'token.txt'), 'utf8').trim();
  } catch {
    return '';
  }
}

const TOKEN = readToken();
const PORT = Number(process.env.BRIDGE_PORT || process.env.PORT || 8765);

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', () => {
  let payload = {};
  try {
    payload = raw.trim() ? JSON.parse(raw) : {};
  } catch {
    payload = { raw: raw.slice(0, 200) };
  }
  payload.agent = payload.agent || 'claude';
  if (event) payload.hook_event_name = payload.hook_event_name || event;

  const body = JSON.stringify(payload);
  const req = http.request(
    {
      host: '127.0.0.1',
      port: PORT,
      path: '/hook',
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        'x-bridge-token': TOKEN,
      },
      timeout: 1200,
    },
    (res) => { res.resume(); res.on('end', () => process.exit(0)); },
  );
  req.on('error', () => process.exit(0));
  req.on('timeout', () => { req.destroy(); process.exit(0); });
  req.end(body);
});

// stdin 为空时也要能退出
setTimeout(() => process.exit(0), 2000).unref();
