// WorkBuddy（CodeBuddy 系）的状态 hook 转发脚本
//
// 用法（由 install-workbuddy-hooks.mjs 注册到 WorkBuddy 的 settings.json）：
//   node "F:/vibe_coding/HachimoDock_Phone/bridge/hooks/workbuddy-hook.mjs"
//
// WorkBuddy 会把事件 JSON 从 stdin 传进来；这里只保留状态需要的最小字段，
// 转发给本机 Bridge 后立即输出 {} 并退出 0，不干预 WorkBuddy 的任何决策。
// 参考实现：HachimoDock/pc/src-tauri/bridge/hooks/workbuddy-pet-manager-hook.js

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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

const text = (value, limit) => (typeof value === 'string' ? value.slice(0, limit) : '');
const object = (value) => (value && typeof value === 'object' && !Array.isArray(value) ? value : {});

/** 只转发外屏能用到、且经过长度限制的字段，不转发工具输出、思考过程或凭据 */
function sanitize(raw) {
  const payload = object(raw);
  const input = object(payload.tool_input);
  const out = {
    agent: 'workbuddy',
    hook_event_name: text(payload.hook_event_name, 48),
    session_id: text(payload.session_id, 128),
    prompt: text(payload.prompt, 200),
    message: text(payload.message, 200),
    last_assistant_message: text(payload.last_assistant_message, 4000),
    generation_id: text(payload.generation_id, 512),
    tool_name: text(payload.tool_name, 80),
    transcript_path: text(payload.transcript_path, 512),
  };
  // 只要文件路径用来显示「执行：Edit xxx」，不转发命令与完整参数
  if (input.file_path) out.tool_input = { file_path: text(input.file_path, 200) };
  return out;
}

let raw = '';
let finished = false;
let size = 0;

function finish() {
  if (finished) return;
  finished = true;

  let payload = {};
  try {
    payload = raw.trim() ? JSON.parse(raw) : {};
  } catch {
    payload = {};
  }

  const done = () => {
    // WorkBuddy 约定 hook 输出 JSON；{} 表示不批准也不拒绝任何操作
    process.stdout.write('{}\n');
    process.exit(0);
  };

  const body = JSON.stringify(sanitize(payload));
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
      timeout: 800,
    },
    (res) => {
      res.resume();
      res.on('end', done);
    },
  );
  req.on('error', done);
  req.on('timeout', () => {
    req.destroy();
    done();
  });
  req.end(body);
}

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  size += chunk.length;
  if (size > 1024 * 1024) {
    finish();
    return;
  }
  raw += chunk;
});
process.stdin.on('error', finish);
process.stdin.on('end', finish);

// WorkBuddy 等不了太久：最多 1.5 秒必须返回，避免 UI 被 hook 拖住
setTimeout(finish, 1500).unref();
