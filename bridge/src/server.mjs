// HachimoDock Phone · PC Bridge（零依赖 WebSocket 服务）
//
// 作用：把 PC 上 Agent 的状态/会话推给手机外屏 App，并接收手机回传的确认、心跳。
// 依赖：只用 Node 内置模块（http / crypto），不安装 npm 包，方便离线运行。
//
// 启动：node bridge/src/server.mjs
//   PORT=8765        监听端口
//   MOCK=0           关闭内置 mock Agent（默认开启，便于没有真实 Agent 时联调）

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import dgram from 'node:dgram';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const PORT = Number(process.env.PORT || 8765);
/** 局域网发现广播端口：手机 App 没配置地址时会监听它来定位这台 PC */
const BEACON_PORT = Number(process.env.BEACON_PORT || 8767);
/** 默认关闭 mock，避免真实接入时被假状态干扰；联调时设 MOCK=1 */
const MOCK = process.env.MOCK === '1';
const WS_MAGIC = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const PROTOCOL_VERSION = 1;

// ---------------------------------------------------------------- 访问令牌
// 局域网里任何设备都能访问 8765，所以写操作的接口都要求同一个令牌；
// 令牌自动生成后存 bridge/token.txt（不入库），手机端在调试页手动填一次。
function loadToken() {
  if (process.env.BRIDGE_TOKEN) return process.env.BRIDGE_TOKEN;
  // 放在 bridge/token.txt：hooks / report.mjs 都从这里读，手机端也按这个路径提示
  const file = path.join(HERE, '..', 'token.txt');
  try {
    const existing = fs.readFileSync(file, 'utf8').trim();
    if (existing) return existing;
  } catch {
    /* 不存在就生成 */
  }
  const token = crypto.randomBytes(16).toString('hex');
  try {
    fs.writeFileSync(file, token + '\n', 'utf8');
  } catch (e) {
    console.warn('[bridge] 无法写入 token.txt，令牌仅在本次运行有效:', e.message);
  }
  return token;
}

const TOKEN = loadToken();

/**
 * 桥接指纹：Beacon 里公布、手机首连后绑定，后续自动发现只认同一指纹的广播。
 * 与令牌独立（指纹公开、令牌保密），伪造 Beacon 者拿不到正确指纹就无法冒充既有配对。
 */
const BRIDGE_FINGERPRINT = crypto.createHash('sha256')
  .update(`hachimodock:${TOKEN}:${PORT}`)
  .digest('hex')
  .slice(0, 16);

/** 令牌日志脱敏：只显示前 4 后 4，中间打星（短令牌整体打星） */
function maskToken(token) {
  if (typeof token !== 'string' || token.length < 8) return '***';
  return token.slice(0, 4) + '***' + token.slice(-4);
}

function authorized(req, url) {
  const given = req.headers['x-bridge-token'] || url.searchParams.get('token');
  return tokenEquals(given, TOKEN);
}

/**
 * 常量时间令牌比较，避免 === 的逐字节时序侧信道。
 * crypto.timingSafeEqual 要求两侧 Buffer 等长，长度不同直接判否（长度本身不是秘密）。
 */
function tokenEquals(given, expected) {
  if (typeof given !== 'string' || given.length === 0) return false;
  const a = Buffer.from(given);
  const b = Buffer.from(expected);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

/** 限制请求体，避免内存被撑爆 */
const MAX_BODY = 32 * 1024;
const MAX_WS_FRAME = 8 * 1024 * 1024;

/** 已连接的手机客户端 */
const clients = new Set();

/** 最近一次状态，新客户端连上后立即补发 */
let lastState = {
  type: 'state',
  agent: '',
  session_id: '',
  state: 'idle',
  label: '等待 Agent 接入',
  ts: Date.now(),
};

/** 每个 agent 的最近状态：允许多个 agent 同时用，互不覆盖 */
const agentStates = new Map();

/** 对话中的人体跟踪结果（手机本地姿态估计推上来），给云台/调试用 */
let lastVision = null;

/** 会话列表（外屏可用触摸切换，按收到的 state 自动累积） */
let sessions = [];

// ---------------------------------------------------------------- 帧编解码

const OP_TEXT = 0x1;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

function encodeFrame(payload, opcode = OP_TEXT) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  const len = body.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, body]);
}

/** 把 TCP 字节流切成完整帧：手机端发来的帧一定带 mask */
function createFrameParser(onMessage, onClose, maxFrame = 0) {
  let buf = Buffer.alloc(0);
  let fragments = [];
  let fragmentSize = 0;

  return (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (buf.length < 2) return;
      const fin = (buf[0] & 0x80) !== 0;
      const opcode = buf[0] & 0x0f;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f;
      let offset = 2;

      if (len === 126) {
        if (buf.length < offset + 2) return;
        len = buf.readUInt16BE(offset);
        offset += 2;
      } else if (len === 127) {
        if (buf.length < offset + 8) return;
        len = Number(buf.readBigUInt64BE(offset));
        offset += 8;
      }
      // 超大帧直接断开，避免一个客户端把 Bridge 内存吃满
      if (maxFrame && len > maxFrame) {
        buf = Buffer.alloc(0);
        onClose();
        return;
      }
      let mask;
      if (masked) {
        if (buf.length < offset + 4) return;
        mask = buf.subarray(offset, offset + 4);
        offset += 4;
      }
      if (buf.length < offset + len) return;

      let body = buf.subarray(offset, offset + len);
      buf = buf.subarray(offset + len);

      if (masked) {
        body = Buffer.from(body);
        for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
      }

      if (opcode === OP_CLOSE) { onClose(); return; }
      if (opcode === OP_PING) { onMessage({ opcode: OP_PONG, body }); continue; }
      if (opcode === OP_PONG) continue;

      if (opcode === 0x0) {
        fragments.push(body);
      } else {
        fragments = [body];
      }
      fragmentSize = fragments.reduce((n, b) => n + b.length, 0);
      if (maxFrame && fragmentSize > maxFrame) {
        fragments = [];
        buf = Buffer.alloc(0);
        onClose();
        return;
      }
      if (fin) {
        const text = Buffer.concat(fragments).toString('utf8');
        fragments = [];
        fragmentSize = 0;
        onMessage({ opcode: OP_TEXT, body: text });
      }
    }
  };
}

// ---------------------------------------------------------------- 广播

function send(conn, obj) {
  try {
    conn.socket.write(encodeFrame(JSON.stringify(obj)));
  } catch {
    /* 连接已断，交给 close 事件清理 */
  }
}

function broadcast(obj) {
  const text = JSON.stringify(obj);
  for (const conn of clients) {
    if (!conn.authorized) continue;
    try {
      conn.socket.write(encodeFrame(text));
    } catch {
      /* ignore */
    }
  }
}

/** 把 Claude Code / Codex 的 hook 事件映射成外屏状态 */
function mapAgentHook(p, prev) {
  const agent = String(p.agent || 'claude').toLowerCase();
  const event = p.hook_event_name || p.event || p.type || '';
  const tool = p.tool_name || '';
  /** 完整会话 id：只有它才能让 claude --resume 找回会话，不能截断 */
  const fullSession = String(p.session_id || p.session || '');
  const session = (fullSession || 's1').slice(0, 8);
  const target = p.tool_input && (p.tool_input.file_path || p.tool_input.command || p.tool_input.pattern);
  const detail = tool + (target ? ' ' + String(target).slice(-28) : '');
  const prevState = prev ? prev.state : '';
  const working = prevState === 'thinking' || prevState === 'tool';
  const make = (state, label) => ({ agent, session_id: session, resume_id: fullSession, state, label });

  switch (event) {
    case 'UserPromptSubmit':
      return make('thinking', '收到指令：' + String(p.prompt || '').slice(0, 32));
    case 'PreToolUse':
      return make('tool', '执行：' + (detail || '工具'));
    case 'PostToolUse':
      // 只有本来就在干活时才回到「思考中」，否则会盖掉 done/waiting/idle
      if (!working) return null;
      return make('thinking', (detail || '工具') + ' 完成');
    case 'Notification':
      return make('waiting', String(p.message || '需要你确认').slice(0, 48));
    case 'Stop':
      return make('done', '任务完成');
    case 'SubagentStop':
      // 子任务结束不代表主任务还在跑，交给 Stop/SessionEnd 决定
      return null;
    case 'SessionStart':
      return make('idle', '会话开始');
    case 'SessionEnd':
      // claude -p 结束时会连发 Stop + SessionEnd，别让「任务完成」只闪一下
      if (prev && prev.state === 'done' && Date.now() - (prev.ts || 0) < 15_000) return null;
      return make('idle', '会话结束');
    default:
      return null;
  }
}

/**
 * 从 Claude Code 的会话记录（transcript jsonl）里取最后一条助手回复。
 * hook 会带上 session_id / transcript_path，用它能拿到「被动接收」那一轮的答复内容。
 */
function lastAssistantText(sessionId, transcriptPath) {
  let file = transcriptPath && insideTranscriptRoot(transcriptPath) && fs.existsSync(transcriptPath)
    ? transcriptPath
    : '';
  if (!file && sessionId) {
    const base = transcriptRoot();
    try {
      for (const dir of fs.readdirSync(base)) {
        const candidate = path.join(base, dir, sessionId + '.jsonl');
        if (fs.existsSync(candidate)) {
          file = candidate;
          break;
        }
      }
    } catch {
      /* 没有 projects 目录就算了 */
    }
  }
  if (!file) return '';
  try {
    const lines = fs.readFileSync(file, 'utf8').split('\n');
    for (let i = lines.length - 1; i >= 0; i--) {
      const line = lines[i].trim();
      if (!line) continue;
      let obj;
      try {
        obj = JSON.parse(line);
      } catch {
        continue;
      }
      if (!obj || obj.type !== 'assistant') continue;
      const content = obj.message && obj.message.content;
      if (Array.isArray(content)) {
        const text = content
          .filter((c) => c && c.type === 'text')
          .map((c) => c.text || '')
          .join('')
          .trim();
        if (text) return text;
      } else if (typeof content === 'string' && content.trim()) {
        return content.trim();
      }
    }
  } catch {
    /* 读不到就当没有 */
  }
  return '';
}

/** transcript 允许读取的固定根目录：~/.claude/projects（客户端传来的路径必须落在它下面） */
function transcriptRoot() {
  return path.join(os.homedir(), '.claude', 'projects');
}

/** 拒绝任意路径：resolve 后必须仍在 transcript 根目录内，防持令牌者把服务器当任意文件读取器 */
function insideTranscriptRoot(candidate) {
  if (typeof candidate !== 'string' || candidate.length === 0) return false;
  const root = transcriptRoot() + path.sep;
  const resolved = path.resolve(candidate);
  if (process.platform === 'win32') {
    return resolved.toLowerCase().startsWith(root.toLowerCase());
  }
  return resolved.startsWith(root);
}

/** 超过这个时间没更新的 agent 状态会被丢弃，避免旧任务一直挂在外屏上 */
const STATE_TTL_MS = Number(process.env.STATE_TTL_MS || 10 * 60 * 1000);
/** 长时间停在「思考中/执行中」但没有任何新事件的，自动降级为待机（防止 hook 漏事件卡住） */
const ACTIVE_STALE_MS = Number(process.env.ACTIVE_STALE_MS || 3 * 60 * 1000);

/** 最近事件，方便排查「为什么外屏显示这个状态」 */
const recentEvents = [];

function idleState() {
  return {
    type: 'state',
    agent: '',
    session_id: '',
    state: 'idle',
    label: '等待 Agent 接入',
    ts: Date.now(),
  };
}

/** 把「当前所有 agent 状态」整体同步给客户端（客户端用它替换本地缓存） */
function broadcastStates() {
  broadcast({ type: 'states', items: [...agentStates.values()] });
}

/** 清掉过期状态；主状态失效时回退到剩下最新的一个 */
function pruneAgents() {
  const now = Date.now();
  let changed = false;

  // 会话列表也要跟着状态一起过期，否则 /health 会一直显示几天前的会话
  const before = sessions.length;
  sessions = sessions.filter((s) => now - (s.ts || 0) <= STATE_TTL_MS);
  if (sessions.length !== before) {
    broadcast({ type: 'sessions', items: sessions });
    changed = true;
  }

  for (const [key, value] of agentStates) {
    const age = now - (value.ts || 0);
    if (age > STATE_TTL_MS) {
      agentStates.delete(key);
      changed = true;
      continue;
    }
    const active = value.state === 'thinking' || value.state === 'tool';
    if (active && age > ACTIVE_STALE_MS) {
      // hook 漏了 Stop 之类的收尾事件时，靠超时兜底回待机
      const demoted = { ...value, state: 'idle', label: '待机', ts: now };
      agentStates.set(key, demoted);
      const session = sessions.find((s) => s.id === demoted.session_id);
      if (session) session.state = 'idle';
      if (lastState.agent === key) {
        lastState = demoted;
        broadcast(lastState);
      }
      changed = true;
    }
  }
  if (lastState.agent && !agentStates.has(lastState.agent)) {
    const alive = [...agentStates.values()].sort((a, b) => (b.ts || 0) - (a.ts || 0));
    lastState = alive[0] || idleState();
    broadcast(lastState);
    broadcast({ type: 'sessions', items: sessions });
    changed = true;
  }
  if (changed) broadcastStates();
}

setInterval(pruneAgents, 30_000).unref();

function resetState() {
  agentStates.clear();
  sessions = [];
  lastState = idleState();
  broadcast(lastState);
  broadcast({ type: 'sessions', items: sessions });
  broadcastStates();
  console.log('[bridge] state reset');
}

// ---------------------------------------------------------------- 手机下发指令给 Claude

let claudeBusy = false;

/** claude 的 session id 只允许 UUID 安全字符，避免拼进命令行后被解释成别的参数 */
const SESSION_RE = /^[0-9a-fA-F-]{8,64}$/;

/** 找 claude 可执行文件：Windows 上 demo 的 'claude' 是 claude.cmd，必须给全路径才能不走 shell */
const CLAUDE_BIN = (() => {
  if (process.env.CLAUDE_BIN) return process.env.CLAUDE_BIN;
  const candidates = [
    path.join(process.env.APPDATA || '', 'npm', 'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'claude', 'claude.exe'),
  ];
  for (const c of candidates) {
    try {
      if (c && fs.existsSync(c)) return c;
    } catch {
      /* ignore */
    }
  }
  return 'claude';
})();

/** 手机跑过的 Claude 会话：跑一次就记住，后续指令接着同一个对话 */
let claudePromptSession = '';

/**
 * 最近活跃的 Claude Code 会话 id。
 * Trae 不发 hook，Bridge 平时收不到 resume_id，所以按「~/.claude/projects 里最新的会话文件」兜底，
 * 这样手机语音发过去的指令才会落进用户正在用的那个对话，而不是另开一个新会话。
 */
let claudeLatestCache = { at: 0, id: '' };

function latestClaudeSession() {
  if (Date.now() - claudeLatestCache.at < 10_000) return claudeLatestCache.id;
  let newest = { id: '', mtime: 0 };
  const base = path.join(os.homedir(), '.claude', 'projects');
  try {
    for (const dir of fs.readdirSync(base)) {
      let entries;
      try {
        entries = fs.readdirSync(path.join(base, dir));
      } catch {
        continue;
      }
      for (const f of entries) {
        if (!f.endsWith('.jsonl')) continue;
        try {
          const st = fs.statSync(path.join(base, dir, f));
          if (st.mtimeMs > newest.mtime) {
            newest = { id: f.replace(/\.jsonl$/, ''), mtime: st.mtimeMs };
          }
        } catch {
          /* 单个文件读不到就算了 */
        }
      }
    }
  } catch {
    /* 没装 Claude Code / 还没跑过，返回空 */
  }
  const id = SESSION_RE.test(newest.id) ? newest.id : '';
  claudeLatestCache = { at: Date.now(), id };
  return id;
}

const PROMPT_TIMEOUT_MS = Number(process.env.PROMPT_TIMEOUT_MS || 5 * 60 * 1000);

/** 手机发来一句话 → 用 claude --resume <会话> -p 在后台执行，结果通过 hooks 回流 */
function runClaude(text, sessionId) {
  const prompt = String(text || '').trim();
  if (!prompt) return { ok: false, error: 'empty prompt' };
  if (claudeBusy) {
    return { ok: false, error: 'claude busy' };
  }

  // 手机只拿到 8 位展示 id，恢复会话必须用完整 id：
  // hook 报的 resume_id → 上次手机跑过的会话 → 最近活跃的会话文件
  const stored = agentStates.get('claude') || {};
  const provided = String(sessionId || '');
  const raw = provided.length >= 32
    ? provided
    : String(stored.resume_id || '') || claudePromptSession || latestClaudeSession();
  const sid = SESSION_RE.test(raw) ? raw : '';
  const args = sid
    ? ['--resume', sid, '-p', '--output-format', 'json']
    : ['-p', '--output-format', 'json'];

  claudeBusy = true;
  pushState({
    agent: 'claude',
    session_id: sid.slice(0, 8) || 'cli',
    resume_id: sid,
    state: 'thinking',
    label: '收到手机指令：' + prompt.slice(0, 28),
  });

  let settled = false;
  let timer = null;
  const settle = (failure) => {
    if (settled) return;
    settled = true;
    if (timer) clearTimeout(timer);
    claudeBusy = false;
    if (failure) failClaude(failure);
  };

  let child;
  try {
    child = spawn(CLAUDE_BIN, args, {
      cwd: ROOT,
      env: process.env,
      shell: false,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (e) {
    settle('启动 claude 失败：' + e.message);
    return { ok: false, error: e.message };
  }

  timer = setTimeout(() => {
    try { child.kill(); } catch { /* ignore */ }
    settle('claude 执行超时');
  }, PROMPT_TIMEOUT_MS);

  let out = '';
  let err = '';
  child.stderr.on('data', (d) => { err += d; });
  child.stdout.on('data', (d) => { out += d; });
  child.on('error', (e) => settle('claude 不可用：' + e.message));
  child.on('close', (code) => {
    // 把 claude 的回答回传到外屏：以前这里直接丢掉 stdout，手机上就只剩「任务完成」
    let result = '';
    try {
      const obj = JSON.parse(out.trim());
      if (obj && typeof obj.result === 'string') result = obj.result.trim();
      if (obj && obj.session_id && SESSION_RE.test(obj.session_id)) {
        claudePromptSession = obj.session_id;
      }
    } catch {
      result = out.trim();
    }
    if (code === 0) {
      settle('');
      if (result) {
        broadcast({
          type: 'prompt_reply',
          ok: true,
          text: result.slice(0, 2000),
          session_id: claudePromptSession,
        });
        console.log('[bridge] prompt reply:', result.slice(0, 60));
      }
    } else {
      settle(err || ('claude 退出码 ' + code));
    }
  });

  try {
    child.stdin.write(prompt);
    child.stdin.end();
  } catch {
    /* 进程可能已退出，交给 close 处理 */
  }
  return { ok: true, session: sid };
}

function failClaude(reason) {
  const hint = /Not logged in|login/i.test(reason) ? 'Claude 未登录（在终端跑一次 claude /login）' : reason;
  const prev = agentStates.get('claude') || {};
  pushState({
    agent: 'claude',
    session_id: prev.session_id || 'cli',
    resume_id: prev.resume_id || '',
    state: 'error',
    label: hint.slice(0, 48),
  });
  console.warn('[bridge] claude prompt failed:', reason.slice(0, 200));
}

// ---------------------------------------------------------------- 实时对话（语音）
// 手机：系统 ASR 识别成文字 → WS {"type":"chat"} → 这里生成回复 → 豆包 TTS → 手机播放。
// 配置放 bridge/config.json（不入库）；没配 Key 时：对话退回本机 claude，TTS 由手机系统发音。

const CONFIG_PATH = path.join(HERE, '..', 'config.json');
let CONFIG = { chat: {}, tts: {} };
try {
  const parsed = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  CONFIG = { ...parsed, chat: parsed.chat || {}, tts: parsed.tts || {} };
} catch {
  /* 没有配置就用默认值 */
}

const CHAT_PERSONA = String(
  CONFIG.chat.persona
  || '你叫哈基米，是一只住在折叠屏外屏里的西高地小狗。说话口语化、有点俏皮。回答一般 35~50 字，通常两句；不要解释过程、不要列点、不要客套。不要使用 Markdown，不要提自己是 AI。',
);

const POWERSHELL = path.join(
  process.env.SystemRoot || 'C:\\Windows',
  'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe',
);

/** 读不到进程环境时再问 Windows 用户环境变量（Bridge 常从旧窗口启动，拿不到后设的变量） */
function readEnv(name) {
  if (process.env[name]) return process.env[name];
  try {
    const out = execFileSync(POWERSHELL, [
      '-NoProfile', '-NonInteractive', '-Command',
      `[Environment]::GetEnvironmentVariable('${name}','User')`,
    ], { encoding: 'utf8', windowsHide: true });
    return String(out || '').trim();
  } catch {
    return '';
  }
}

/**
 * 对话用的凭据：优先 config.json，其次系统里已经配好的环境变量。
 * 这样不用把密钥写进仓库文件——用户终端里 claude 能登录，这里就能用。
 */
let chatCredsCache = null;
function chatCredentials() {
  if (chatCredsCache) return chatCredsCache;
  const key = String(CONFIG.chat.apiKey || '').trim()
    || readEnv('DEEPSEEK_API_KEY')
    || readEnv('ANTHROPIC_AUTH_TOKEN')
    || readEnv('OPENAI_API_KEY');
  let base = String(CONFIG.chat.baseUrl || '').trim();
  if (!base) {
    const anthropic = readEnv('ANTHROPIC_BASE_URL');
    // https://api.deepseek.com/anthropic → https://api.deepseek.com
    base = anthropic ? anthropic.replace(/\/anthropic\/?$/, '') : 'https://api.deepseek.com';
  }
  chatCredsCache = {
    key,
    base: base.replace(/\/+$/, ''),
    model: String(CONFIG.chat.model || 'deepseek-chat'),
  };
  return chatCredsCache;
}

/**
 * 提前把对话接口的 TLS 连接建好：手机一开口（发来录音）就先握个手，
 * 等识别完真正发 /chat 时直接复用连接，省掉首次 100~300ms 的握手。
 */
let lastChatWarm = 0;
function warmChatHost() {
  const creds = chatCredentials();
  if (!creds.key) return;
  const now = Date.now();
  if (now - lastChatWarm < 15000) return;   // 连接池还热着，别反复握
  lastChatWarm = now;
  try {
    const u = new URL(creds.base + '/');
    const mod = u.protocol === 'http:' ? http : https;
    const req = mod.request(u, {
      method: 'HEAD',
      headers: { authorization: `Bearer ${creds.key}` },
    }, (res) => res.resume());
    req.on('error', () => { /* 预热失败不影响正式请求 */ });
    req.setTimeout(5000, () => req.destroy());
    req.end();
  } catch {
    /* ignore */
  }
}

/** 对话进程单独开在仓库外，避免读到项目 CLAUDE.md；BRIDGE_PORT=1 让 hooks 立即失败，不污染外屏 Agent 状态 */
const CHAT_DIR = process.env.CHAT_DIR || path.join(os.tmpdir(), 'hachimophone-chat');
const CHAT_TIMEOUT_MS = Number(CONFIG.chat.timeoutMs || 60000);
let chatSessionId = '';
let chatBusy = false;
let chatBusySince = 0;

/**
 * 上一轮回复还在跑就别插话；但卡住超过 45 秒一定是 promise 没落地（网络半死、连接被掐），
 * 这时必须强制解锁，否则后面每一轮都会收到「正在回复上一条」，听起来就是问它没反应。
 */
function chatLocked() {
  if (!chatBusy) return false;
  if (Date.now() - chatBusySince < 45_000) return true;
  console.warn('[bridge] chatBusy 超过 45s，强制解锁');
  chatBusy = false;
  return false;
}

/** 极简 JSON POST（兼容 http/https，只依赖内置模块） */
function postJson(urlStr, headers, bodyObj, timeoutMs) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const mod = u.protocol === 'http:' ? http : https;
    const data = Buffer.from(JSON.stringify(bodyObj));
    let settled = false;
    const done = (err, val) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve(val);
    };
    const req = mod.request({
      hostname: u.hostname,
      port: u.port || (u.protocol === 'http:' ? 80 : 443),
      path: u.pathname + u.search,
      method: 'POST',
      timeout: timeoutMs,
      headers: { 'content-type': 'application/json', 'content-length': data.length, ...headers },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => done(null, { status: res.statusCode || 0, body: Buffer.concat(chunks).toString('utf8') }));
      // 响应中途被重置时不会触发 end，没有这行这个 Promise 就永远挂着，
      // 后面的 ASR（串行队列）会全部跟着卡死
      res.on('error', (e) => done(e));
      res.on('close', () => done(new Error('connection closed')));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (e) => done(e));
    req.end(data);
  });
}

/** OpenAI 兼容接口（DeepSeek / 豆包方舟 / MiMo 都能用） */
async function chatOpenAi(text) {
  const creds = chatCredentials();
  const { status, body } = await postJson(
    `${creds.base}/chat/completions`,
    { authorization: `Bearer ${creds.key}` },
    {
      model: creds.model,
      messages: [
        { role: 'system', content: CHAT_PERSONA + (sceneNote ? '\n' + sceneNote : '') },
        ...chatHistory,
        { role: 'user', content: text },
      ],
      temperature: 0.7,
      max_tokens: 80,
    },
    CHAT_TIMEOUT_MS,
  );
  if (status < 200 || status >= 300) throw new Error('模型请求失败（HTTP ' + status + '）');
  let data;
  try {
    data = JSON.parse(body);
  } catch {
    throw new Error('模型返回格式异常');
  }
  const reply = data && data.choices && data.choices[0] && data.choices[0].message
    ? data.choices[0].message.content
    : '';
  if (!reply) throw new Error('模型没有返回内容');
  return String(reply).trim();
}

/** 流式版：边生成边回调增量文字，首句一出来就能开始合成语音 */
function streamChatOpenAi(text, onDelta, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const creds = chatCredentials();
    if (!creds.key) {
      reject(new Error('未配置模型 Key'));
      return;
    }
    const url = new URL(`${creds.base}/chat/completions`);
    const mod = url.protocol === 'http:' ? http : https;
    const payload = JSON.stringify({
      model: creds.model,
      stream: true,
      temperature: 0.7,
      max_tokens: 80,
      messages: [
        { role: 'system', content: CHAT_PERSONA + (sceneNote ? '\n' + sceneNote : '') },
        ...chatHistory,
        { role: 'user', content: text },
      ],
    });
    let settled = false;
    const finish = (err) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve();
    };
    const req = mod.request(
      url,
      {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${creds.key}`,
          'content-length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        if (res.statusCode !== 200) {
          let err = '';
          res.on('data', (c) => { err += c; });
          res.on('end', () => finish(new Error('模型请求失败（HTTP ' + res.statusCode + '）')));
          res.on('error', (e) => finish(e));
          res.on('close', () => finish(new Error('模型响应中断')));
          return;
        }
        let buf = '';
        const consumeLine = (rawLine) => {
          const line = rawLine.trim();
          if (!line.startsWith('data:')) return;
          const raw = line.slice(5).trim();
          if (!raw || raw === '[DONE]') return;
          try {
            const evt = JSON.parse(raw);
            const delta = evt && evt.choices && evt.choices[0] && evt.choices[0].delta;
            if (delta && delta.content) onDelta(delta.content);
          } catch {
            /* 忽略不完整行 */
          }
        };
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          buf += chunk;
          let i;
          while ((i = buf.indexOf('\n')) >= 0) {
            consumeLine(buf.slice(0, i));
            buf = buf.slice(i + 1);
          }
        });
        res.on('end', () => {
          if (buf.trim()) consumeLine(buf);
          finish();
        });
        res.on('error', (e) => finish(e));
        res.on('close', () => finish(new Error('模型连接中断')));
      },
    );
    req.on('error', (e) => finish(e));
    req.setTimeout(timeoutMs, () => req.destroy(new Error('模型响应超时')));
    req.end(payload);
  });
}

/** 本机 claude CLI：复用已登录的会话，按人设回答（不需要额外 Key） */
function chatClaude(text) {
  return new Promise((resolve, reject) => {
    try {
      fs.mkdirSync(CHAT_DIR, { recursive: true });
    } catch (e) {
      reject(new Error('创建对话工作目录失败：' + e.message));
      return;
    }
    const args = ['-p', text, '--output-format', 'json', '--append-system-prompt', CHAT_PERSONA, '--strict-mcp-config'];
    if (chatSessionId) args.push('--resume', chatSessionId);

    // Bridge 进程可能没继承用户环境里的登录令牌，这里补上
    const creds = chatCredentials();
    const env = { ...process.env, BRIDGE_PORT: '1' };
    if (creds.key && !env.ANTHROPIC_AUTH_TOKEN) env.ANTHROPIC_AUTH_TOKEN = creds.key;

    let child;
    try {
      child = spawn(CLAUDE_BIN, args, {
        cwd: CHAT_DIR,
        env,
        shell: false,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (e) {
      reject(new Error('启动 claude 失败：' + e.message));
      return;
    }

    let out = '';
    let err = '';
    let done = false;
    let timer;
    const finish = (failure, value) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (failure) reject(failure);
      else resolve(value);
    };
    timer = setTimeout(() => {
      try { child.kill(); } catch { /* ignore */ }
      finish(new Error('对话超时'));
    }, CHAT_TIMEOUT_MS);

    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', (e) => finish(e));
    child.on('close', (code) => {
      let obj = null;
      try {
        obj = JSON.parse(out.trim());
      } catch {
        /* 不是 JSON 就按纯文本处理 */
      }
      if (obj && obj.session_id) chatSessionId = obj.session_id;
      if (obj && typeof obj.result === 'string') {
        if (/Not logged in|login/i.test(obj.result)) {
          finish(new Error('Claude 未登录（终端跑一次 claude /login，或在 config.json 配 chat.apiKey）'));
          return;
        }
        if (obj.is_error) {
          finish(new Error(String(obj.result).slice(0, 140)));
          return;
        }
        finish(null, obj.result.trim());
        return;
      }
      if (code !== 0) {
        finish(new Error(String(err || out || 'claude 退出码 ' + code).slice(0, 160)));
        return;
      }
      finish(null, out.trim());
    });
  });
}

/** 下载二进制（千问 TTS 先返回音频 URL，再取文件） */
function fetchBuffer(urlStr, timeoutMs = 15000, redirects = 5) {
  return new Promise((resolve, reject) => {
    const mod = urlStr.startsWith('http:') ? http : https;
    let settled = false;
    const done = (err, val) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve(val);
    };
    const req = mod.get(urlStr, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        if (redirects <= 0) {
          done(new Error('下载音频重定向过多'));
          return;
        }
        const nextUrl = new URL(res.headers.location, urlStr).toString();
        fetchBuffer(nextUrl, timeoutMs, redirects - 1).then((v) => done(null, v), (e) => done(e));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        done(new Error(`下载音频失败：HTTP ${res.statusCode}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => done(null, Buffer.concat(chunks)));
      res.on('error', (e) => done(e));
      res.on('close', () => done(new Error('下载音频中断')));
    });
    req.on('error', (e) => done(e));
    req.setTimeout(timeoutMs, () => req.destroy(new Error('下载音频超时')));
  });
}

/** 千问 TTS：先拿音频 URL，再把整段 WAV 抓回来（手机端直接播） */
async function synthesizeQwen(text, voiceOverride) {
  const creds = asrCredentials();
  if (!creds.key) throw new Error('未配置 asr.apiKey');
  // CosyVoice 的 long* 音色在千问接口上不认，整段兜底时用通用女声
  const configured = (CONFIG.tts && CONFIG.tts.voice) || '';
  const voice = voiceOverride || (/^long/i.test(configured) ? 'Cherry' : configured || 'Cherry');
  const t0 = Date.now();
  const { status, body } = await postJson(
    'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation',
    { authorization: `Bearer ${creds.key}` },
    {
      model: 'qwen3-tts-flash',
      input: { text, voice, language_type: 'Chinese' },
    },
    20000,
  );
  let data = null;
  try {
    data = JSON.parse(body);
  } catch {
    data = null;
  }
  const audio = data && data.output && data.output.audio;
  if (status < 200 || status >= 300 || !audio || !audio.url) {
    const msg = (data && (data.message || (data.output && data.output.message))) || String(body).slice(0, 120);
    throw new Error(`千问 TTS ${status}：${msg}`);
  }
  const bytes = await fetchBuffer(audio.url);
  console.log(`[bridge] tts ${Date.now() - t0}ms（${voice}，${Math.round(bytes.length / 1024)}KB）`);
  return { wav: bytes.toString('base64') };
}

/**
 * CosyVoice v3 流式 TTS（WebSocket）：音频以二进制帧下发，控制信息是 JSON 文本帧。
 * 语音更自然，适合「龙泡泡」这类童声。
 */
function streamCosyVoiceTts(text, hooks, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const creds = asrCredentials();
    if (!creds.key) {
      reject(new Error('未配置 asr.apiKey'));
      return;
    }
    if (typeof WebSocket !== 'function') {
      reject(new Error('当前 Node 不支持 WebSocket（需要 22+）'));
      return;
    }
    const model = (CONFIG.tts && CONFIG.tts.model) || 'cosyvoice-v3-flash';
    const voice = (CONFIG.tts && CONFIG.tts.voice) || 'longjielidou_v3';
    const rate = Number((CONFIG.tts && CONFIG.tts.sampleRate) || 24000);
    const taskId = crypto.randomUUID();
    let started = false;
    let done = false;
    let timer = null;

    const finish = (err) => {
      if (done) return;
      done = true;
      if (timer) clearTimeout(timer);
      try {
        ws.close();
      } catch {
        /* ignore */
      }
      if (err) reject(err);
      else resolve();
    };

    let ws;
    try {
      ws = new WebSocket('wss://dashscope.aliyuncs.com/api-ws/v1/inference', {
        headers: { Authorization: `Bearer ${creds.key}` },
      });
    } catch (e) {
      reject(e);
      return;
    }
    timer = setTimeout(() => finish(new Error('流式 TTS 超时')), timeoutMs);

    const send = (obj) => {
      try {
        ws.send(JSON.stringify(obj));
      } catch {
        /* 连接已断，交给 close 处理 */
      }
    };

    ws.onopen = () => send({
      header: { action: 'run-task', task_id: taskId, streaming: 'duplex' },
      payload: {
        task_group: 'audio',
        task: 'tts',
        function: 'SpeechSynthesizer',
        model,
        parameters: {
          text_type: 'PlainText',
          voice,
          format: 'pcm',
          sample_rate: rate,
          volume: 60,
          rate: 1,
          pitch: 1,
        },
        input: {},
      },
    });

    // WebSocket 的 data 是 Blob，取内容要 await；多条消息并发时顺序会被打乱，
    // 这里串成一条队列，保证 PCM 按到达顺序推给手机，也能保证「合成结束」在最后一片之后
    let queue = Promise.resolve();

    const handle = async (e) => {
      let buf;
      try {
        buf = typeof e.data === 'string'
          ? Buffer.from(e.data, 'utf8')
          : Buffer.from(await e.data.arrayBuffer());
      } catch {
        return;
      }
      if (!buf.length) return;
      // JSON 文本帧以 '{' 开头，其余都当 PCM
      if (buf[0] !== 0x7b) {
        if (!started) {
          started = true;
          hooks.onStart(rate);
        }
        try {
          hooks.onPcm(buf);
        } catch {
          /* 手机断开时忽略 */
        }
        return;
      }
      let m;
      try {
        m = JSON.parse(buf.toString('utf8'));
      } catch {
        return;
      }
      const ev = m.header && m.header.event;
      if (ev === 'task-started') {
        send({ header: { action: 'continue-task', task_id: taskId }, payload: { input: { text } } });
        send({ header: { action: 'finish-task', task_id: taskId }, payload: { input: {} } });
      } else if (ev === 'task-finished') {
        // 排在队列末尾：等前面所有音频帧都推完再收尾
        queue = queue.then(() => finish(started ? null : new Error('CosyVoice 没有返回音频')));
      } else if (ev === 'task-failed') {
        queue = queue.then(() => finish(new Error('CosyVoice 失败：' + JSON.stringify(m.header || {}).slice(0, 140))));
      }
    };

    ws.onmessage = (e) => {
      queue = queue.then(() => handle(e)).catch(() => {});
    };
    ws.onerror = () => finish(new Error('CosyVoice 连接失败'));
    ws.onclose = () => finish(started ? null : new Error('CosyVoice 连接被关闭'));
  });
}

/** 按配置选流式 TTS 通道：cosyvoice* 走 WebSocket，其余走千问 SSE */
function streamTts(text, hooks) {
  const model = String((CONFIG.tts && CONFIG.tts.model) || '');
  if (/^cosyvoice/i.test(model)) return streamCosyVoiceTts(text, hooks, 15000);
  return streamQwenTts(text, hooks, 15000);
}

/**
 * 千问流式 TTS：SSE 边合成边回调 PCM（24k 单声道），首包 ~30ms 就能开始播。
 * 第一段数据自带 WAV 头，按 44 字节切掉并把采样率回调出去。
 */
function streamQwenTts(text, hooks, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const creds = asrCredentials();
    if (!creds.key) {
      reject(new Error('未配置 asr.apiKey'));
      return;
    }
    const voice = (CONFIG.tts && CONFIG.tts.voice) || 'Ethan';
    /** 已经吐过音频：后续网络抖动就当播完，别再回落到整段合成（否则会重播） */
    let started = false;
    const payload = JSON.stringify({
      model: (CONFIG.tts && CONFIG.tts.model) || 'qwen3-tts-flash',
      input: { text, voice, language_type: 'Chinese' },
    });
    let settled = false;
    const finish = (err) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve();
    };
    const req = https.request(
      'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation',
      {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${creds.key}`,
          'X-DashScope-SSE': 'enable',
          'content-length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        if (res.statusCode !== 200) {
          let err = '';
          res.on('data', (c) => { err += c; });
          res.on('end', () => finish(new Error(`千问 TTS ${res.statusCode}：${err.slice(0, 120)}`)));
          res.on('error', (e) => finish(e));
          res.on('close', () => finish(new Error(`千问 TTS ${res.statusCode}`)));
          return;
        }
        let buf = '';
        let head = Buffer.alloc(0);

        const push = (bytes) => {
          if (!started) {
            head = Buffer.concat([head, bytes]);
            if (head.length < 44) return;
            started = true;
            hooks.onStart(head.readUInt32LE(24) || 24000);
            if (head.length > 44) hooks.onPcm(head.subarray(44));
            return;
          }
          hooks.onPcm(bytes);
        };

        const consumeLine = (rawLine) => {
          const line = rawLine.trim();
          if (!line.startsWith('data:')) return;
          const raw = line.slice(5).trim();
          if (raw === '[DONE]') return;
          try {
            const evt = JSON.parse(raw);
            const data = evt && evt.output && evt.output.audio && evt.output.audio.data;
            if (data) push(Buffer.from(data, 'base64'));
          } catch {
            /* 半个 JSON 不会出现：按行切分已经保证完整 */
          }
        };

        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          buf += chunk;
          let i;
          while ((i = buf.indexOf('\n')) >= 0) {
            consumeLine(buf.slice(0, i));
            buf = buf.slice(i + 1);
          }
        });
        res.on('end', () => {
          if (buf.trim()) consumeLine(buf);
          if (!started) finish(new Error('千问 TTS 没有返回音频'));
          else finish();
        });
        res.on('error', (e) => finish(started ? null : e));
        res.on('close', () => finish(started ? null : new Error('千问 TTS 连接中断')));
      },
    );
    req.on('error', (e) => finish(started ? null : e));
    req.setTimeout(timeoutMs, () => req.destroy(new Error('流式 TTS 超时')));
    req.end(payload);
  });
}

/** 豆包 TTS 2.0 单向流式 HTTP（和上游 HachimoDock 同一接口） */
function synthesizeDoubao(text) {
  const tts = CONFIG.tts || {};
  if (!tts.apiKey) return Promise.resolve(null);
  const sampleRate = Number(tts.sampleRate || 16000);
  const body = JSON.stringify({
    user: { uid: 'hachimophone' },
    req_params: {
      text,
      speaker: tts.speaker || 'zh_female_vv_uranus_bigtts',
      audio_params: { format: 'pcm', sample_rate: sampleRate },
    },
  });

  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 'openspeech.bytedance.com',
      port: 443,
      path: '/api/v3/tts/unidirectional',
      method: 'POST',
      timeout: 20000,
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        'x-api-key': tts.apiKey,
        'x-api-resource-id': tts.resourceId || 'seed-tts-2.0',
        'x-api-request-id': crypto.randomUUID(),
      },
    }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error('豆包 TTS HTTP ' + res.statusCode));
        return;
      }
      let pending = '';
      let finished = false;
      const chunks = [];
      let total = 0;

      /** 响应是一串首尾相接的 JSON 对象，按大括号配对切 */
      const drain = () => {
        for (;;) {
          if (finished) return;
          const start = pending.indexOf('{');
          if (start < 0) {
            pending = '';
            return;
          }
          if (start > 0) pending = pending.slice(start);
          let depth = 0;
          let inStr = false;
          let esc = false;
          let end = -1;
          for (let i = 0; i < pending.length; i++) {
            const ch = pending[i];
            if (inStr) {
              if (esc) esc = false;
              else if (ch === '\\') esc = true;
              else if (ch === '"') inStr = false;
              continue;
            }
            if (ch === '"') inStr = true;
            else if (ch === '{') depth++;
            else if (ch === '}') {
              depth--;
              if (depth === 0) { end = i; break; }
            }
          }
          if (end < 0) return;
          const raw = pending.slice(0, end + 1);
          pending = pending.slice(end + 1);
          let obj;
          try {
            obj = JSON.parse(raw);
          } catch {
            continue;
          }
          if (obj.code === 20000000) {
            finished = true;
            return;
          }
          if (obj.code !== 0) {
            finished = true;
            reject(new Error('豆包 TTS 失败（' + obj.code + '），请检查音色和语音服务权限'));
            return;
          }
          if (obj.data) {
            const pcm = Buffer.from(obj.data, 'base64');
            total += pcm.length;
            if (total > 8 * 1024 * 1024) {
              finished = true;
              reject(new Error('豆包 TTS 音频过大'));
              return;
            }
            chunks.push(pcm);
          }
        }
      };

      res.on('data', (c) => {
        if (finished) return;
        pending += c.toString('utf8');
        drain();
      });
      res.on('end', () => {
        if (!finished) {
          reject(new Error('豆包 TTS 未正常结束'));
          return;
        }
        if (!chunks.length) {
          reject(new Error('豆包 TTS 没有返回音频'));
          return;
        }
        resolve({ audio: Buffer.concat(chunks).toString('base64'), sampleRate });
      });
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('豆包 TTS 超时')));
    req.on('error', reject);
    req.end(body);
  });
}

/** 最近 5 轮对话（只给 OpenAI 兼容分支用，claude 分支自己有 --resume） */
let chatHistory = [];
const CHAT_TURNS = 5;
const SENTENCE_END = /[。！？!?；;…\n]/;
/** 这一轮对话的现场提示（来自手机本地视觉），拼在 system prompt 后面 */
let sceneNote = '';
const CAMERA_HINT = '如果用户的提问必须通过手机摄像头看眼前画面、手势、物体或穿着才能回答，请只回复 [CAM] 这5个字符，不要输出其他任何字；否则直接简短回答。';

function isCameraMarker(reply) {
  const t = String(reply || '').trim().toUpperCase();
  return t === '[CAM]' || t === '【CAM】' || t.startsWith('[CAM]');
}

function rememberTurn(prompt, reply) {
  chatHistory.push({ role: 'user', content: prompt });
  chatHistory.push({ role: 'assistant', content: reply });
  if (chatHistory.length > CHAT_TURNS * 2) chatHistory = chatHistory.slice(-CHAT_TURNS * 2);
}

/**
 * 全流式一轮：模型出字 → 凑够一句立刻送 TTS（边合成边推 PCM）→ 手机边收边播。
 * 首句不需要等全文，整体比整段模式早 600~900ms 出声。
 */
async function chatStreamed(prompt, hooks, t0) {
  let full = '';
  let pending = '';
  let carry = '';
  let startedAudio = false;
  let audioEnded = false;
  let headerChecked = false;
  let cameraAborted = false;
  let firstTextAt = 0;
  let firstAudioAt = 0;
  let ttsCount = 0;
  let ttsChain = Promise.resolve();
  // 防止 streamChatOpenAi 抛错时 ttsChain 先前已 reject 导致 UnhandledPromiseRejection
  const safeWaitTts = () => ttsChain.catch(() => {});

  const speak = (segment) => {
    ttsCount++;
    ttsChain = ttsChain
      .then(() => streamTts(segment, {
        onStart: (rate) => {
          if (startedAudio) return;
          startedAudio = true;
          firstAudioAt = Date.now() - t0;
          hooks.onAudioStart(rate);
        },
        onPcm: hooks.onPcm,
      }))
      .catch((e) => {
        // 第一段就失败 → 整个流式放弃，交给上层退回整段模式；后续失败只记日志
        if (!startedAudio) throw e;
        console.warn('[bridge] 分句合成失败:', e.message);
      });
    // 确保任何中间态都有人吞住 unhandledRejection（真正错误会在 await ttsChain 抛出）
    ttsChain.catch(() => {});
  };

  const flushPending = () => {
    let m;
    while ((m = pending.match(SENTENCE_END))) {
      const cut = m.index + 1;
      const part = pending.slice(0, cut).replace(/\s+/g, ' ').trim();
      pending = pending.slice(cut);
      const combined = ((carry ? carry + ' ' : '') + part).trim();
      // 太短的句子（「汪！」「好。」）不要丢，暂存到 carry 等下一句一起合成，
      // 也不能塞回 pending 开头（否则下一轮还会反复匹配到同一标点导致卡死）
      if (combined.length >= 4) {
        carry = '';
        speak(combined);
      } else if (combined) {
        carry = combined;
      }
    }
    // 第一段允许在逗号处提前切：先把头几个字合成出来，别等整句
    if (ttsCount === 0) {
      const comma = pending.match(/[，,、]/);
      if (comma && comma.index >= 2) {
        const part = pending.slice(0, comma.index + 1).trim();
        pending = pending.slice(comma.index + 1);
        const combined = ((carry ? carry + ' ' : '') + part).trim();
        carry = '';
        if (combined) speak(combined);
      }
    }
    if (!firstTextAt && full.length >= 6) {
      firstTextAt = Date.now() - t0;
      if (hooks.onText) hooks.onText(full.trim());
    }
  };

  try {
    await streamChatOpenAi(prompt, (delta) => {
      if (cameraAborted) return;
      full += delta;
      pending += delta;
      // 先攒够 6 个字符确认不是 "[CAM]" 再推给 TTS / 文字回显，避免把控制标记念出来
      if (!headerChecked) {
        const trimmed = full.trim();
        if (trimmed.length < 6 && !/[\n。！？!?]/.test(trimmed)) return;
        headerChecked = true;
        if (isCameraMarker(trimmed)) {
          cameraAborted = true;
          return;
        }
      }
      flushPending();
    });

    if (cameraAborted || isCameraMarker(full)) {
      return { ok: true, need_camera: true, text: prompt };
    }
    if (!headerChecked) flushPending();
    const rest = ((carry ? carry + ' ' : '') + pending).replace(/\s+/g, ' ').trim();
    carry = '';
    if (/[\u4e00-\u9fa5a-zA-Z0-9]/.test(rest)) speak(rest);
    await ttsChain;
  } finally {
    await safeWaitTts();
    // 无论成功还是中途异常，只要发过 audio_start 就必须补发 audio_end，避免手机一直占着 AudioTrack
    if (hooks.onAudioEnd && startedAudio && !audioEnded) {
      audioEnded = true;
      hooks.onAudioEnd();
    }
  }

  const reply = full.replace(/\s+/g, ' ').trim().slice(0, 300);
  if (!reply) throw new Error('模型没有返回内容');
  rememberTurn(prompt, reply);
  if (hooks.onText) hooks.onText(reply);
  console.log(`[bridge] chat ${Date.now() - t0}ms（首字 ${firstTextAt}ms / 首音 ${firstAudioAt || '-'}ms）：${reply.slice(0, 30)}`);
  return { ok: true, text: reply, streamed: startedAudio };
}

/** 一轮对话：模型回复 + 可选 TTS 音频（TTS 失败就让手机用系统 TTS） */
async function handleChat(text, hooks = {}, opts = {}) {
  const onText = hooks.onText;
  const prompt = String(text || '').trim().slice(0, 500);
  if (!prompt) return { ok: false, error: '没有听到内容' };

  // 把手机端本地视觉（姿态/图像标签）作为一句现场提示塞进 system prompt：
  // 不额外花图片费用，模型也能知道「面前有人/在客厅」这类上下文
  const v = lastVision && Date.now() - lastVision.ts < 10000 ? lastVision : null;
  const parts = [];
  if (v) {
    parts.push(`（现场：${v.found ? '画面里有一个人' : '画面里暂时没人'}${
      v.face ? '，你认出来是' + v.face : ''
    }${
      v.labels ? '；看到的物体/环境：' + v.labels.slice(0, 40) : ''
    }。只在相关时自然带一句，不要每次都复述这句提示。）`);
  }
  if (opts.vlmReady) parts.push(CAMERA_HINT);
  sceneNote = parts.join('\n');

  // provider=auto（默认）：有 Key（config.json 或系统环境变量）就走 OpenAI 兼容接口，否则用本机 claude
  const provider = String(CONFIG.chat.provider || 'auto');
  const useOpenAi = !!chatCredentials().key && provider !== 'claude';
  const t0 = Date.now();

  // 手机在线 + 千问可用时走全流式：模型边出字、TTS 边合成边播，首句不等全文
  if (useOpenAi && hooks.onAudioStart && asrCredentials().key) {
    let audioStarted = false;
    const streamHooks = {
      ...hooks,
      onAudioStart: (rate) => {
        audioStarted = true;
        hooks.onAudioStart(rate);
      },
    };
    try {
      return await chatStreamed(prompt, streamHooks, t0);
    } catch (e) {
      console.warn('[bridge] 流式对话失败，退回整段模式:', e.message);
      // 已经出过声就别整段重播一遍，否则手机上会从头再说一次
      if (audioStarted) return { ok: false, error: '回复中断，再说一次' };
    }
  }

  let reply;
  if (useOpenAi) {
    reply = await chatOpenAi(prompt);
  } else {
    reply = await chatClaude(prompt);
  }
  reply = String(reply || '').replace(/\s+/g, ' ').trim().slice(0, 300);
  if (!reply) return { ok: false, error: '模型没有返回内容' };
  if (opts.vlmReady && isCameraMarker(reply)) {
    return { ok: true, need_camera: true, text: prompt };
  }
  console.log(`[bridge] chat ${Date.now() - t0}ms：${reply.slice(0, 30)}`);

  if (useOpenAi) rememberTurn(prompt, reply);
  // 先把文字回给手机显示，别等 TTS 合成 + 下载
  if (onText) {
    try {
      onText(reply);
    } catch {
      /* 手机此时断开也不影响后面合成 */
    }
  }

  // 手机在线时走流式：边合成边播，首个字比整段合成早 1 秒左右
  if (hooks.onAudioStart && asrCredentials().key) {
    try {
      await streamTts(reply, {
        onStart: hooks.onAudioStart,
        onPcm: hooks.onPcm,
      });
      if (hooks.onAudioEnd) hooks.onAudioEnd();
      return { ok: true, text: reply };
    } catch (e) {
      console.warn('[bridge] 流式 TTS 失败，改用整段合成:', e.message);
    }
  }

  let tts = null;
  try {
    // 千问优先（和 ASR 共用一个 Key），没有再退到豆包，都没有就让手机用系统发音
    tts = asrCredentials().key
      ? await synthesizeQwen(reply)
      : await synthesizeDoubao(reply);
  } catch (e) {
    console.warn('[bridge] TTS 失败，手机将改用系统发音:', e.message);
  }
  if (!tts) return { ok: true, text: reply };
  if (tts.wav) return { ok: true, text: reply, wav: tts.wav };
  return { ok: true, text: reply, audio: tts.audio, sample_rate: tts.sampleRate };
}


// ---------------------------------------------------------------- 语音识别
// 手机自带识别在国内连不上 Google，所以退化成「手机只录音、PC 识别」：
// 手机发来 16k 单声道 PCM，这里落成 WAV，交给 Windows 自带的语音引擎（zh-CN）转文字。
// 不需要任何 API Key，缺点是准确率一般，适合「一句话指令」这种短句。

const ASR_SCRIPT = path.join(HERE, '..', 'asr', 'recognize.ps1');
const ASR_DIR = path.join(os.tmpdir(), 'hachimophone-asr');
/** ASR 临时目录只初始化一次：私有目录模式（Windows 上 ACL 由系统管，名字带强随机段不可猜测） */
let asrDirReady = false;
function ensureAsrDir() {
  if (asrDirReady) return ASR_DIR;
  fs.mkdirSync(ASR_DIR, { recursive: true });
  asrDirReady = true;
  return ASR_DIR;
}
/** 优先用 PowerShell 7（Windows PowerShell 5.1 上 System.Speech 识别不出中文） */
function findShell() {
  if (process.env.ASR_PWSH) return process.env.ASR_PWSH;
  try {
    const first = execFileSync('where.exe', ['pwsh.exe'], { encoding: 'utf8', windowsHide: true })
      .split(/\r?\n/).map((s) => s.trim()).find(Boolean);
    if (first && fs.existsSync(first)) return first;
  } catch {
    /* 没装 pwsh 就用系统自带的 */
  }
  return path.join(
    process.env.SystemRoot || 'C:\\Windows',
    'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe',
  );
}

const ASR_SHELL = findShell();
/** 同一时间只跑一个识别进程，避免一次连说几句时开一堆 PowerShell */
let asrChain = Promise.resolve();

function wavBuffer(pcm, sampleRate) {
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);           // fmt 块长度
  header.writeUInt16LE(1, 20);            // PCM
  header.writeUInt16LE(1, 22);            // 单声道
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

// 千问 ASR（阿里云百炼 / DashScope）：中文准确率远好于 Windows 自带听写引擎。
// Key 只从配置或系统环境变量读取，不写进仓库文件。
let asrCredsCache = null;
function asrCredentials() {
  if (asrCredsCache) return asrCredsCache;
  asrCredsCache = {
    key: String((CONFIG.asr && CONFIG.asr.apiKey) || '').trim()
      || readEnv('DASHSCOPE_API_KEY')
      || readEnv('DASHSCOPE_KEY'),
    base: String((CONFIG.asr && CONFIG.asr.baseUrl)
      || 'https://dashscope.aliyuncs.com/compatible-mode/v1').replace(/\/+$/, ''),
    model: String((CONFIG.asr && CONFIG.asr.model) || 'qwen3-asr-flash'),
  };
  return asrCredsCache;
}

/** 千问 ASR：OpenAI 兼容 chat/completions + input_audio（data URL） */
async function recognizeCloud(wav) {
  const creds = asrCredentials();
  if (!creds.key) return { ok: false, error: '未配置 asr.apiKey', skipped: true };
  const { status, body } = await postJson(
    `${creds.base}/chat/completions`,
    { authorization: `Bearer ${creds.key}` },
    {
      model: creds.model,
      stream: false,
      asr_options: { language: 'zh', enable_itn: true },
      messages: [{
        role: 'user',
        content: [{
          type: 'input_audio',
          input_audio: { data: 'data:audio/wav;base64,' + wav.toString('base64') },
        }],
      }],
    },
    30000,
  );
  let data = null;
  try {
    data = JSON.parse(body);
  } catch {
    data = null;
  }
  if (status < 200 || status >= 300) {
    const msg = data && data.error ? (data.error.message || data.error.code) : String(body).slice(0, 120);
    return { ok: false, error: `千问 ASR ${status}：${msg}` };
  }
  const content = data && data.choices && data.choices[0] && data.choices[0].message
    ? data.choices[0].message.content
    : '';
  const text = Array.isArray(content)
    ? content.map((c) => c.text || '').join('')
    : String(content || '');
  const clean = text.replace(/\s+/g, ' ').trim();
  return clean ? { ok: true, text: clean } : { ok: false, error: '没听清', empty: true };
}


function recognizeWav(file) {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    let proc;
    try {
      proc = spawn(ASR_SHELL, [
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', ASR_SCRIPT, '-Path', file,
      ], { shell: false, windowsHide: true });
    } catch (e) {
      resolve({ ok: false, error: '无法启动 PowerShell：' + e.message });
      return;
    }
    proc.stdout.setEncoding('utf8');
    proc.stderr.setEncoding('utf8');
    proc.stdout.on('data', (c) => { out += c; });
    proc.stderr.on('data', (c) => { err += c; });
    const timer = setTimeout(() => { try { proc.kill(); } catch { /* 已退出 */ } }, 20000);
    proc.on('error', (e) => {
      clearTimeout(timer);
      resolve({ ok: false, error: '无法启动 PowerShell：' + e.message });
    });
    proc.on('close', () => {
      clearTimeout(timer);
      const line = out.trim().split('\n').pop() || '';
      try {
        resolve(JSON.parse(line));
      } catch {
        resolve({ ok: false, error: (err || out).trim().slice(0, 120) || '识别失败' });
      }
    });
  });
}

/** PCM 进来，文字出去；串行执行，失败也继续排队 */
function recognizePcm(pcm, sampleRate) {
  const run = async () => {
    const wav = wavBuffer(pcm, sampleRate);
    // 配了千问 Key 就优先用云端；只有未配置或网络/服务端异常时才退回本机 Windows 引擎，
    // 云端正常返回「没听清」（empty）时不拉起 PowerShell，避免无谓卡顿 1~2 秒阻塞后续轮次
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const cloud = await recognizeCloud(wav);
        if (cloud.ok || cloud.empty) return cloud;
        if (cloud.skipped) break;
        console.warn('[bridge] 千问 ASR 失败，改用本机引擎:', cloud.error);
        break;
      } catch (e) {
        if (attempt === 0) {
          console.warn('[bridge] 千问 ASR 异常，重试一次:', e.message);
          continue;
        }
        console.warn('[bridge] 千问 ASR 异常，改用本机引擎:', e.message);
      }
    }

    if (!fs.existsSync(ASR_SCRIPT)) return { ok: false, error: '缺少 bridge/asr/recognize.ps1' };
    const dir = ensureAsrDir();
    // 文件名带 16 hex 随机位：共享 tmpdir 下其他本地用户不可猜测、不可抢先占位
    const file = path.join(dir,
      `v-${Date.now()}-${process.pid}-${crypto.randomBytes(8).toString('hex')}.wav`);
    fs.writeFileSync(file, wav);
    try {
      return await recognizeWav(file);
    } finally {
      fs.unlink(file, () => { /* 文件删不掉也无所谓，系统会清临时目录 */ });
    }
  };
  const next = asrChain.then(run, run);
  asrChain = next.then(() => {}, () => {});
  // 兜底时限：识别本身已经有超时，万一哪条链路没落地，也不能把后面的识别全堵死
  return Promise.race([
    next,
    new Promise((resolve) => setTimeout(
      () => resolve({ ok: false, error: '识别超时' }), 45_000)),
  ]);
}

/**
 * 预识别结果缓存：手机说完停顿 0.3 秒就先送一份 PCM 过来，
 * 停顿满 0.65 秒结束时若没再说话，直接复用这次的识别结果，少等一次往返。
 */
const voiceProbes = new Map();
const PROBE_TTL_MS = 60_000;

function rememberProbe(id, task) {
  if (!id) return task;
  voiceProbes.set(id, task);
  const drop = () => {
    const timer = setTimeout(() => {
      if (voiceProbes.get(id) === task) voiceProbes.delete(id);
    }, PROBE_TTL_MS);
    if (timer.unref) timer.unref();
  };
  task.then(drop, drop);
  return task;
}

function takeProbe(id) {
  const task = id ? voiceProbes.get(id) : null;
  if (task) voiceProbes.delete(id);
  return task || null;
}

function pushState(patch) {
  lastState = {
    type: 'state',
    agent: String(patch.agent || '').toLowerCase(),
    session_id: String(patch.session_id || ''),
    resume_id: String(patch.resume_id || ''),
    state: String(patch.state || 'idle'),
    label: String(patch.label ?? ''),
    ts: Date.now(),
  };
  const key = lastState.agent || 'unknown';
  agentStates.set(key, lastState);
  const idx = sessions.findIndex((s) => s.id === lastState.session_id);
  if (idx >= 0) {
    sessions[idx].state = lastState.state;
    sessions[idx].ts = lastState.ts;
  } else if (lastState.session_id) {
    // 第一次见到的会话：按 agent 建个条目，最多保留 8 条
    sessions.unshift({ id: lastState.session_id, title: lastState.agent || 'agent', state: lastState.state, ts: lastState.ts });
    sessions = sessions.slice(0, 8);
  }
  broadcast(lastState);
  broadcast({ type: 'sessions', items: sessions });
  recentEvents.unshift({ ...lastState });
  if (recentEvents.length > 50) recentEvents.pop();
  console.log(`[bridge] state ${lastState.agent}/${lastState.state} ${lastState.label ?? ''}`);
}

// ---------------------------------------------------------------- HTTP + WS

/** 读 JSON body，超过 MAX_BODY 直接 413，避免局域网被撑爆内存 */
function readJsonBody(req, res, cb) {
  const chunks = [];
  let size = 0;
  let aborted = false;
  req.on('data', (c) => {
    if (aborted) return;
    size += c.length;
    if (size > MAX_BODY) {
      aborted = true;
      res.writeHead(413, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'body too large' }));
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on('error', () => {
    aborted = true;
  });
  req.on('end', () => {
    if (aborted) return;
    let packet = {};
    try { packet = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { /* 忽略坏包 */ }
    cb(packet);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://localhost:${PORT}`);
  const reply = (code, obj) => {
    res.writeHead(code, { 'content-type': 'application/json' });
    res.end(JSON.stringify(obj));
  };

  if (req.method === 'POST') {
    // 写操作一律要令牌：否则同 Wi-Fi 的任何设备都能让这台 PC 跑 claude
    if (!authorized(req, url)) return reply(401, { ok: false, error: 'unauthorized' });

    switch (url.pathname) {
      case '/hook':
      case '/agent':
        readJsonBody(req, res, (packet) => {
          const key = String(packet.agent || 'claude').toLowerCase();
          const mapped = url.pathname === '/hook' ? mapAgentHook(packet, agentStates.get(key)) : packet;
          if (mapped && mapped.state) pushState(mapped);
          // 任务结束：把这一轮 agent 的回复内容也推到手机（不管是手机下发还是电脑上直接跑的）
          if (mapped && mapped.state === 'done' && url.pathname === '/hook') {
            const text = lastAssistantText(packet.session_id, packet.transcript_path);
            if (text) {
              broadcast({ type: 'prompt_reply', ok: true, text: text.slice(0, 2000), session_id: packet.session_id });
              console.log('[bridge] hook reply:', text.slice(0, 60));
            }
          }
          reply(200, { ok: true, state: lastState });
        });
        return;
      case '/prompt':
        readJsonBody(req, res, (packet) => {
          const result = runClaude(packet.text, packet.session_id);
          reply(200, result);
        });
        return;
      case '/chat':
        readJsonBody(req, res, (packet) => {
          if (chatLocked()) return reply(200, { ok: false, error: '正在回复上一条' });
          chatBusy = true;
          chatBusySince = Date.now();
          handleChat(packet.text)
            .then((out) => {
              chatBusy = false;
              reply(200, out);
            })
            .catch((e) => {
              chatBusy = false;
              reply(200, { ok: false, error: e.message });
            });
        });
        return;
      // 单独测 TTS（千问优先，其次豆包），不用真的对话
      case '/tts':
        readJsonBody(req, res, (packet) => {
          const text = String(packet.text || '你好，我是哈基米').slice(0, 300);
          const job = asrCredentials().key
            ? synthesizeQwen(text, String(packet.voice || '')).then((out) => ({ ok: true, wav: out.wav }))
            : synthesizeDoubao(text).then((out) => (out
              ? { ok: true, audio: out.audio, sample_rate: out.sampleRate }
              : { ok: false, error: '未配置 tts.apiKey' }));
          job
            .then((out) => reply(200, out))
            .catch((e) => reply(200, { ok: false, error: e.message }));
        });
        return;
      case '/reset':
        resetState();
        return reply(200, { ok: true, state: lastState });
      default:
        return reply(404, { ok: false, error: 'not found' });
    }
  }

  if (!authorized(req, url)) {
    return reply(401, { ok: false, error: 'unauthorized: append ?token=<bridge/token.txt>' });
  }

  if (url.pathname === '/health') {
    pruneAgents();
    return reply(200, {
      ok: true,
      clients: clients.size,
      state: lastState,
      states: [...agentStates.values()],
      sessions,
    });
  }

  if (url.pathname === '/vision') {
    // 给 ESP32-C3 / 调试用：拿到最近一次人体目标（超过 3 秒没更新就当没人）
    const fresh = lastVision && Date.now() - lastVision.ts < 3000;
    return reply(200, {
      ok: true,
      found: !!(fresh && lastVision.found),
      x: fresh ? lastVision.x : 0.5,
      y: fresh ? lastVision.y : 0.5,
      labels: fresh ? lastVision.labels : '',
      face: fresh ? lastVision.face : '',
      age: lastVision ? Date.now() - lastVision.ts : -1,
    });
  }

  if (url.pathname === '/events') {
    return reply(200, { ok: true, events: recentEvents });
  }

  if (url.pathname === '/inject') {
    pushState({
      agent: url.searchParams.get('agent') || 'mock',
      session_id: url.searchParams.get('session') || 's1',
      state: url.searchParams.get('state') || 'thinking',
      label: url.searchParams.get('label') || '',
    });
    return reply(200, { ok: true, state: lastState });
  }

  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(DEBUG_PAGE);
});

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }

  const accept = crypto
    .createHash('sha1')
    .update(key + WS_MAGIC)
    .digest('base64');

  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  // 地址里可以带 ?token=；没带的话必须在 hello 包里补，否则不接收任何指令
  let preAuthorized = false;
  try {
    preAuthorized = tokenEquals(
      new URL(req.url || '/', 'http://localhost').searchParams.get('token'), TOKEN);
  } catch {
    /* ignore */
  }

  const conn = { socket, device: null, authorized: preAuthorized };
  if (preAuthorized) {
    clients.add(conn);
    console.log(`[bridge] client connected, total=${clients.size}`);
  }

  let closed = false;
  let authTimer = null;
  const cleanup = () => {
    if (closed) return;
    closed = true;
    if (authTimer) { clearTimeout(authTimer); authTimer = null; }
    if (clients.delete(conn)) {
      console.log(`[bridge] client gone, total=${clients.size}`);
    }
    try { socket.destroy(); } catch { /* ignore */ }
  };

  const reject = (reason) => {
    if (authTimer) { clearTimeout(authTimer); authTimer = null; }
    conn.authorized = false;
    clients.delete(conn);
    // 先把错误帧冲刷出去再关连接，否则同步 destroy 会让客户端只看到连接重置而拿不到 unauthorized 提示
    try {
      socket.end(encodeFrame(JSON.stringify({ type: 'error', error: reason })));
    } catch {
      cleanup();
      return;
    }
    const t = setTimeout(cleanup, 400);
    if (t.unref) t.unref();
  };

  if (!preAuthorized) {
    authTimer = setTimeout(() => {
      if (!conn.authorized && !closed) reject('hello timeout');
    }, 5000);
    if (authTimer.unref) authTimer.unref();
  }

  const onMessage = (msg) => {
    if (msg.opcode === OP_PONG) {
      try { socket.write(encodeFrame(msg.body, OP_PONG)); } catch { /* ignore */ }
      return;
    }
    if (msg.opcode !== OP_TEXT) return;

    let packet;
    try { packet = JSON.parse(msg.body); } catch { return; }

    if (packet.type === 'hello') {
      if (!conn.authorized && !tokenEquals(packet.token, TOKEN)) {
        reject('unauthorized');
        return;
      }
      if (authTimer) { clearTimeout(authTimer); authTimer = null; }
      conn.authorized = true;
      if (!clients.has(conn)) {
        clients.add(conn);
        console.log(`[bridge] client connected, total=${clients.size}`);
      }
      conn.device = packet.device || null;
      send(conn, {
        type: 'hello',
        v: PROTOCOL_VERSION,
        server: 'hachimodock-phone-bridge',
        ts: Date.now(),
      });
      // 补发每个 agent 的最新状态（客户端自己决定怎么展示多 agent）
      pruneAgents();
      for (const s of agentStates.values()) send(conn, s);
      if (agentStates.size === 0) send(conn, lastState);
      send(conn, { type: 'states', items: [...agentStates.values()] });
      send(conn, { type: 'sessions', items: sessions });
      console.log('[bridge] hello from', JSON.stringify(conn.device));
      return;
    }

    if (!conn.authorized) {
      reject('hello required');
      return;
    }

    switch (packet.type) {
      case 'ping':
        send(conn, { type: 'pong', ts: packet.ts ?? Date.now() });
        break;
      case 'confirm': {
        // 收到手机「我知道了」之后必须把对应的 waiting 状态清掉（多 agent 时不能误改最后一个上报的 agent）
        const sid = packet.session_id || '';
        const list = [...agentStates.values()];
        const waitingMatch = (sid && list.find((s) => s.session_id === sid && s.state === 'waiting'))
          || list.find((s) => s.state === 'waiting')
          || (sid && list.find((s) => s.session_id === sid));
        const targetAgent = (waitingMatch && waitingMatch.agent) || lastState.agent || 'claude';
        const targetSid = sid || (waitingMatch && waitingMatch.session_id) || lastState.session_id;
        const targetResume = (waitingMatch && waitingMatch.resume_id)
          || (agentStates.get(targetAgent) && agentStates.get(targetAgent).resume_id)
          || '';
        console.log('[bridge] confirm', targetAgent, targetSid);
        pushState({
          agent: targetAgent,
          session_id: targetSid,
          resume_id: targetResume,
          state: 'idle',
          label: '已确认，继续待命',
        });
        break;
      }
      case 'prompt': {
        const result = runClaude(packet.text, packet.session_id);
        send(conn, { type: 'prompt_ack', ok: result.ok, error: result.error || '' });
        console.log('[bridge] prompt from phone:', String(packet.text || '').slice(0, 40), result);
        break;
      }
      case 'vision': {
        // 对话期间手机本地姿态跟踪的结果：x/y 是画面归一化坐标，给 ESP32-C3 云台用
        lastVision = {
          found: !!packet.found,
          x: Number(packet.x) || 0,
          y: Number(packet.y) || 0,
          labels: String(packet.labels || ''),
          face: String(packet.face || ''),
          ts: Date.now(),
        };
        break;
      }
      case 'chat': {
        const id = Number(packet.id) || 0;
        const text = String(packet.text || '').trim();
        if (!text) {
          send(conn, { type: 'chat_reply', id, ok: false, error: '没有听到内容' });
          break;
        }
        if (chatLocked()) {
          send(conn, { type: 'chat_reply', id, ok: false, error: '正在回复上一条' });
          break;
        }
        chatBusy = true;
        chatBusySince = Date.now();
        console.log('[bridge] chat from phone:', text.slice(0, 40));
        handleChat(text, {
          onText: (replyText) => send(conn, { type: 'chat_text', id, ok: true, text: replyText }),
          onAudioStart: (rate) => send(conn, { type: 'chat_audio_start', id, rate }),
          onPcm: (bytes) => send(conn, { type: 'chat_audio_chunk', id, pcm: bytes.toString('base64') }),
          onAudioEnd: () => send(conn, { type: 'chat_audio_end', id }),
        }, { vlmReady: !!packet.vlm_ready })
          .then((out) => {
            chatBusy = false;
            send(conn, { type: 'chat_reply', id, ...out });
          })
          .catch((e) => {
            chatBusy = false;
            send(conn, { type: 'chat_reply', id, ok: false, error: e.message });
          });
        break;
      }
      case 'say': {
        const id = Number(packet.id) || 0;
        const text = String(packet.text || '').trim().slice(0, 300);
        if (!text) {
          send(conn, { type: 'chat_reply', id, ok: false, error: '空文本' });
          break;
        }
        if (asrCredentials().key) {
          let audioStarted = false;
          streamTts(text, {
            onStart: (rate) => {
              audioStarted = true;
              send(conn, { type: 'chat_audio_start', id, rate });
            },
            onPcm: (bytes) => send(conn, { type: 'chat_audio_chunk', id, pcm: bytes.toString('base64') }),
          })
            .then(() => {
              if (audioStarted) send(conn, { type: 'chat_audio_end', id });
              send(conn, { type: 'chat_reply', id, ok: true, text, streamed: audioStarted });
            })
            .catch(() => {
              if (audioStarted) send(conn, { type: 'chat_audio_end', id });
              send(conn, { type: 'chat_reply', id, ok: true, text, streamed: audioStarted });
            });
        } else {
          synthesizeDoubao(text)
            .then((tts) => send(conn, tts
              ? { type: 'chat_reply', id, ok: true, text, audio: tts.audio, sample_rate: tts.sampleRate }
              : { type: 'chat_reply', id, ok: true, text }))
            .catch(() => send(conn, { type: 'chat_reply', id, ok: true, text }));
        }
        break;
      }
      case 'voice': {
        const id = Number(packet.id) || 0;
        const rate = Number(packet.rate) || 16000;
        const probe = !!packet.probe;
        const probeId = Number(packet.probeId) || 0;
        let pcm = null;
        try {
          pcm = Buffer.from(String(packet.pcm || ''), 'base64');
        } catch {
          pcm = null;
        }
        if (!pcm || pcm.length < 3200) {
          send(conn, { type: 'voice_text', id, ok: false, error: '录音太短' });
          break;
        }
        console.log('[bridge] voice', Math.round(pcm.length / 2 / rate * 10) / 10, 's' + (probe ? '（预识别）' : ''));
        warmChatHost();
        let task;
        if (probe) {
          task = rememberProbe(id, recognizePcm(pcm, rate));
        } else {
          const cached = takeProbe(probeId);
          if (cached) {
            console.log('[bridge] asr 复用预识别结果');
            // 预识别没听清（空文本）时再老老实实识别一遍完整录音
            task = cached.then((r) => (r && r.ok ? r : recognizePcm(pcm, rate)));
          } else {
            task = recognizePcm(pcm, rate);
          }
        }
        task
          .then((r) => {
            console.log('[bridge] asr', r.ok ? r.text : r.error);
            send(conn, { type: 'voice_text', id, ok: !!r.ok, text: r.text || '', error: r.error || '' });
          })
          .catch((e) => send(conn, { type: 'voice_text', id, ok: false, error: e.message }));
        break;
      }
      case 'chat_reset':
        chatSessionId = '';
        chatHistory = [];
        console.log('[bridge] chat session reset');
        break;
      default:
        console.log('[bridge] unknown packet', packet.type);
    }
  };

  const parser = createFrameParser(onMessage, cleanup, MAX_WS_FRAME);
  socket.on('data', (chunk) => {
    try { parser(chunk); } catch (e) {
      console.error('[bridge] parse error', e);
      cleanup();
    }
  });
  socket.on('close', cleanup);
  socket.on('error', cleanup);
});

// ---------------------------------------------------------------- mock Agent

const MOCK_STEPS = [
  { state: 'thinking', label: '思考：怎么改 HachimoDock 的协议' },
  { state: 'tool', label: '执行：读取 firmware/protocol.md' },
  { state: 'tool', label: '执行：写入 bridge/src/server.mjs' },
  { state: 'waiting', label: '需要你确认：是否继续安装依赖' },
  { state: 'done', label: '完成：Bridge 骨架已就绪' },
  { state: 'idle', label: '待机中' },
];

if (MOCK) {
  let i = 0;
  setInterval(() => {
    const step = MOCK_STEPS[i++ % MOCK_STEPS.length];
    pushState({ agent: 'mock', session_id: 's1', ...step });
  }, 4000);
}

server.on('error', (e) => {
  if (e && e.code === 'EADDRINUSE') {
    console.error(`[bridge] 端口 ${PORT} 已被占用：可能已有一个 Bridge 在运行（换端口用 PORT=xxxx）`);
  } else {
    console.error('[bridge] server error', e);
  }
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[bridge] http://0.0.0.0:${PORT}  ws://<本机IP>:${PORT}/ws  mock=${MOCK}`);
  console.log(`[bridge] 配对令牌（手机端填这个）：${maskToken(TOKEN)}（完整令牌见 bridge/token.txt）`);
  console.log(`[bridge] 调试页：http://127.0.0.1:${PORT}/?token=<见token.txt>`);
  const creds = chatCredentials();
  const chatMode = creds.key && CONFIG.chat.provider !== 'claude'
    ? `OpenAI 兼容接口（${creds.model}）`
    : '本机 claude';
  console.log(`[bridge] 实时对话：${chatMode}${asrCredentials().key
    ? ` + 千问 TTS（${(CONFIG.tts && CONFIG.tts.voice) || 'Ethan'}）`
    : (CONFIG.tts.apiKey ? ' + 豆包 TTS' : '（无 TTS Key，手机用系统发音）')}`);
  console.log(`[bridge] 语音识别：${asrCredentials().key
    ? `千问 ASR（${asrCredentials().model}，失败退回本机 Windows 引擎）`
    : '本机 Windows 引擎（配 DASHSCOPE_API_KEY 可换千问 ASR）'}`);
  startBeacon();
});

/** 每 2s 往局域网广播一次身份，手机 App 地址为空时会自动发现这台 PC */
function startBeacon() {
  let beacon;
  let timer;
  const stop = () => {
    if (timer) clearInterval(timer);
    timer = null;
    try { beacon.close(); } catch { /* ignore */ }
  };
  try {
    beacon = dgram.createSocket('udp4');
  } catch (e) {
    console.warn('[bridge] beacon disabled:', e.message);
    return;
  }
  beacon.on('error', (e) => {
    console.warn('[bridge] beacon error, disabled:', e.message);
    stop();
  });
  beacon.bind(() => {
    try {
      beacon.setBroadcast(true);
    } catch {
      /* 某些系统不允许广播，忽略 */
    }
    const payload = Buffer.from(JSON.stringify({
      app: 'hachimodock-bridge',
      v: PROTOCOL_VERSION,
      port: PORT,
      path: '/ws',
      name: 'hachimodock-phone-bridge',
      // 桥接指纹：手机首连绑定后据此校验后续 Beacon，防伪造广播劫持（见手机端 discoverBridge）
      fp: BRIDGE_FINGERPRINT,
    }));
    // 多网卡（有线 + 无线）时 255.255.255.255 只会走默认路由那一块网卡，
    // 手机连的是 Wi-Fi 就永远收不到，所以同时往每块网卡的定向广播地址发一份，并定期刷新网卡列表
    let targets = subnetBroadcasts();
    let tickCount = 0;
    timer = setInterval(() => {
      if (!timer) return;
      if (++tickCount % 15 === 0) targets = subnetBroadcasts();
      for (const addr of targets) {
        try {
          beacon.send(payload, 0, payload.length, BEACON_PORT, addr, () => {});
        } catch (e) {
          console.warn('[bridge] beacon send failed on ' + addr + ':', e.message);
        }
      }
    }, 2000);
  });
}

/** 255.255.255.255 + 每块网卡的子网广播地址 */
function subnetBroadcasts() {
  const out = new Set(['255.255.255.255']);
  try {
    for (const list of Object.values(os.networkInterfaces())) {
      for (const ni of list || []) {
        if (ni.family !== 'IPv4' || ni.internal || !ni.netmask) continue;
        const ip = ni.address.split('.').map(Number);
        const mask = ni.netmask.split('.').map(Number);
        if (ip.length !== 4 || mask.length !== 4) continue;
        out.add(ip.map((b, i) => (b & mask[i]) | (~mask[i] & 255)).join('.'));
      }
    }
  } catch {
    /* 拿不到网卡信息就只发全网广播 */
  }
  return [...out];
}

const DEBUG_PAGE = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HachimoDock Phone · Bridge</title>
<style>
 body{margin:0;background:#0f172a;color:#e2e8f0;font:14px/1.6 ui-sans-serif,system-ui,"Microsoft YaHei"}
 main{max-width:720px;margin:40px auto;padding:0 20px}
 h1{font-size:18px;letter-spacing:.06em;color:#22d3ee}
 code{background:#1e293b;padding:2px 6px;border-radius:6px}
 #state{font-size:15px;margin:12px 0}
 #state b{color:#22d3ee}
 button{background:#164e63;color:#e0f2fe;border:1px solid #22d3ee55;border-radius:8px;padding:8px 12px;margin:4px 6px 4px 0;cursor:pointer}
 button:hover{background:#0e7490}
 pre{background:#111827;padding:12px;border-radius:10px;overflow:auto;font-size:12px}
</style></head>
<body><main>
<h1>HachimoDock Phone · Bridge</h1>
<p>WebSocket: <code id="url"></code>　连接数：<b id="n">0</b></p>
<p id="state">等待状态…</p>
<p>
  <button data-s="thinking">thinking</button>
  <button data-s="tool">tool</button>
  <button data-s="waiting">waiting</button>
  <button data-s="done">done</button>
  <button data-s="error">error</button>
  <button data-s="idle">idle</button>
</p>
<pre id="log"></pre>
<script>
const token = ${JSON.stringify(TOKEN)};
const url = 'ws://' + location.hostname + ':${PORT}/ws?token=' + encodeURIComponent(token);
document.getElementById('url').textContent = url;
const ws = new WebSocket(url);
const log = (s) => { const el = document.getElementById('log'); el.textContent = s + '\\n' + el.textContent.slice(0, 4000); };
ws.onopen = () => { ws.send(JSON.stringify({ type: 'hello', v: 1, token, device: { name: 'browser-debug' } })); };
ws.onmessage = (e) => {
  const p = JSON.parse(e.data);
  if (p.type === 'hello') { document.getElementById('n').textContent = '1'; }
  if (p.type === 'state') {
    // 用 textContent 而不是 innerHTML：hook 上报的 label 里可能有尖括号
    document.getElementById('state').textContent =
      p.state + '　' + (p.label || '') + '　' + new Date(p.ts).toLocaleTimeString();
  }
  log(e.data);
};
document.querySelectorAll('button[data-s]').forEach(b => b.onclick = () => {
  fetch('/inject?token=' + encodeURIComponent(token)
    + '&state=' + b.dataset.s + '&label=' + encodeURIComponent('手动注入 ' + b.dataset.s));
});
</script></main></body></html>`;
