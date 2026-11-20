// 联调自检客户端：node bridge/test/client.mjs [ws://127.0.0.1:8765/ws] [秒数]
// 验证：握手、状态推送、心跳、confirm 回传。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Bridge 现在要令牌：默认读 bridge/token.txt */
function readToken() {
  if (process.env.BRIDGE_TOKEN) return process.env.BRIDGE_TOKEN;
  try {
    return fs.readFileSync(path.join(HERE, '..', 'token.txt'), 'utf8').trim();
  } catch {
    return '';
  }
}

const token = readToken();
const port = Number(process.env.BRIDGE_PORT || process.env.PORT || 8765);
const raw = process.argv[2] || `ws://127.0.0.1:${port}/ws`;
const url = token && !raw.includes('token=') ? raw + (raw.includes('?') ? '&' : '?') + 'token=' + token : raw;
const seconds = Number(process.argv[3] || 10);
const ws = new WebSocket(url);
let states = 0;

const timer = setTimeout(() => {
  console.log(`\n[test] 结束：收到 ${states} 条 state`);
  ws.close();
  process.exit(states > 0 ? 0 : 1);
}, seconds * 1000);

ws.onopen = () => {
  console.log('[test] connected', url);
  ws.send(JSON.stringify({ type: 'hello', v: 1, token, device: { name: 'test-client', cover: { w: 748, h: 720 } } }));
  ws.send(JSON.stringify({ type: 'ping', ts: Date.now() }));
  setTimeout(() => ws.send(JSON.stringify({ type: 'confirm', session_id: 's1' })), Math.min(3000, seconds * 300));
};

ws.onmessage = (e) => {
  const p = JSON.parse(e.data);
  if (p.type === 'state') {
    states++;
    console.log(`[test] state ${p.agent}/${p.state}  ${p.label || ''}`);
  } else {
    console.log('[test] recv', e.data.slice(0, 160));
  }
};

ws.onerror = (e) => console.log('[test] error', e.message || e);
ws.onclose = () => { clearTimeout(timer); console.log('[test] closed'); };
