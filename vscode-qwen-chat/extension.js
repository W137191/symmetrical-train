const vscode = require('vscode');
const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

let proxyProc = null;
let outChannel = null;

function log(...a) {
  if (outChannel) outChannel.appendLine(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '));
}

// 从 VS Code 设置读取（不写死地址/端口/模型，便于切换）
function cfg() {
  const c = vscode.workspace.getConfiguration('qwenCampus');
  return {
    host: c.get('host', '127.0.0.1'),
    port: c.get('port', 8000),
    model: c.get('model', 'qwen3.5-122b')
  };
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// 定位 chat-server.py：开发模式在扩展目录上级；全局安装则在 D:/1/122
function resolveProxyScript(context) {
  const candidates = [
    path.join(context.extensionPath, '..', 'chat-server.py'),
    'D:\\1\\122\\chat-server.py',
    path.join(context.extensionPath, 'chat-server.py')
  ];
  for (const c of candidates) {
    try { if (fs.existsSync(c)) return c; } catch (e) {}
  }
  return candidates[1];
}

// 探测本地代理是否已启动
function ping() {
  const { host, port } = cfg();
  return new Promise(resolve => {
    const req = http.get({ host, port, path: '/', timeout: 1000 }, r => { r.resume(); resolve(true); });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

// 未启动时自动拉起 chat-server.py（仅我们自己拉起的才在退出时回收）
async function ensureProxy(proxyScript) {
  if (await ping()) return true;
  try {
    proxyProc = spawn('python', [proxyScript], { detached: true, stdio: 'ignore' });
    proxyProc.unref();
  } catch (e) {
    return false;
  }
  for (let i = 0; i < 40; i++) {
    if (await ping()) return true;
    await sleep(300);
  }
  return false;
}

// ===== 编码智能体：工具定义 =====
const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'read_file',
      description: '读取工作区内某个文件的文本内容。',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: '文件相对或绝对路径，必须位于已打开的工作区内' } },
        required: ['path']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'write_file',
      description: '创建或覆盖写入一个文件（限工作区内）。可用于写代码、改配置等。',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: '文件路径，相对工作区根或绝对路径' },
          content: { type: 'string', description: '要写入的完整文件内容' }
        },
        required: ['path', 'content']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'list_directory',
      description: '列出工作区内某个目录的内容（默认列出工作区根）。',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: '目录路径，默认为工作区根' } },
        required: []
      }
    }
  }
];

// 把任意路径解析为工作区内的绝对路径；越界则抛错
function resolveWorkspacePath(p) {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || !folders.length) throw new Error('未打开工作区：请在 VS Code 先「打开文件夹」，再使用编码智能体');
  const root = folders[0].uri.fsPath;
  let full;
  if (p && /^[a-zA-Z]:[\\/]/.test(p)) {
    full = path.resolve(p);
    if (path.relative(root, full).startsWith('..')) throw new Error('拒绝在工作区之外操作：' + p);
  } else {
    full = path.resolve(root, p || '.');
  }
  return full;
}

// 执行模型请求的一个工具调用（读写文件，全部限定在工作区内）
async function executeTool(webview, call) {
  const name = call.function.name;
  let args = {};
  try { args = JSON.parse(call.function.arguments || '{}'); } catch (e) { args = {}; }
  let result;
  try {
    if (name === 'read_file') {
      const full = resolveWorkspacePath(args.path);
      let text = fs.readFileSync(full, 'utf8');
      if (text.length > 30000) text = text.slice(0, 30000) + '\n...[内容已截断，超过 30000 字符]';
      result = '文件内容（' + args.path + '）：\n' + text;
    } else if (name === 'write_file') {
      const full = resolveWorkspacePath(args.path);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      const content = args.content != null ? String(args.content) : '';
      fs.writeFileSync(full, content, 'utf8');
      result = '已写入 ' + args.path + '（' + Buffer.byteLength(content, 'utf8') + ' 字节）';
    } else if (name === 'list_directory') {
      const full = resolveWorkspacePath(args.path || '.');
      const entries = fs.readdirSync(full, { withFileTypes: true });
      result = '目录 ' + (args.path || '.') + '：\n' + entries.map(e => (e.isDirectory() ? '[目录] ' : '[文件] ') + e.name).join('\n');
    } else {
      result = '未知工具：' + name;
    }
    webview.postMessage({ type: 'tool', name, args, status: 'ok', result });
  } catch (e) {
    result = '工具执行失败：' + (e && e.message ? e.message : String(e));
    webview.postMessage({ type: 'tool', name, args, status: 'error', result });
  }
  return result;
}

// 单次非流式请求（agent 用）
function chatOnce(payload) {
  return new Promise((resolve, reject) => {
    const { host, port } = cfg();
    const body = JSON.stringify(payload);
    const req = http.request({
      host, port, path: '/v1/chat/completions', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, res => {
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', c => { buf += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(buf)); }
        catch (e) { reject(new Error('响应解析失败: ' + buf.slice(0, 300))); }
      });
    });
    req.on('error', e => reject(e));
    req.write(body);
    req.end();
  });
}

// 编码智能体主循环：发请求 → 有 tool_calls 就执行并把结果回填 → 直到给出最终文本
async function agentChat(webview, msg) {
  const { model } = cfg();
  if (!vscode.workspace.workspaceFolders || !vscode.workspace.workspaceFolders.length) {
    webview.postMessage({ type: 'error', text: '编码智能体需要工作区：请在 VS Code 打开一个文件夹后再试（或取消勾选「编码智能体」做纯聊天）。' });
    return;
  }
  const messages = (msg.history || []).concat([{ role: 'user', content: msg.text }]);
  const base = {
    model,
    temperature: typeof msg.temperature === 'number' ? msg.temperature : 0.7,
    max_tokens: msg.maxTokens || 4096,
    chat_template_kwargs: { enable_thinking: msg.thinking !== false },
    tools: TOOLS,
    tool_choice: 'auto'
  };
  let lastReasoning = '';
  let lastContent = '';
  for (let i = 0; i < 8; i++) {
    let resp;
    try {
      resp = await chatOnce(Object.assign({ messages: messages.slice() }, base));
    } catch (e) {
      webview.postMessage({ type: 'error', text: String(e) });
      return;
    }
    const choice = resp.choices && resp.choices[0];
    if (!choice || !choice.message) { webview.postMessage({ type: 'error', text: '模型返回为空' }); return; }
    const m = choice.message;
    // 把助手消息（可能含 tool_calls）回填，去掉非标准字段
    const sanitized = { role: m.role };
    if (m.content != null) sanitized.content = m.content;
    if (m.tool_calls) sanitized.tool_calls = m.tool_calls;
    messages.push(sanitized);
    if (m.tool_calls && m.tool_calls.length) {
      webview.postMessage({ type: 'status', ok: true, msg: '● 智能体执行中…' });
      for (const tc of m.tool_calls) {
        const r = await executeTool(webview, tc);
        messages.push({ role: 'tool', tool_call_id: tc.id, content: r });
      }
      continue;
    }
    if (m.reasoning) lastReasoning = m.reasoning;
    if (m.content) lastContent = m.content;
    break;
  }
  if (lastReasoning) webview.postMessage({ type: 'delta', kind: 'reasoning', text: lastReasoning });
  if (lastContent) webview.postMessage({ type: 'delta', kind: 'content', text: lastContent });
  webview.postMessage({ type: 'done' });
}

// ===== 普通流式聊天（无工具）=====
function getHtml(webview, scriptUri, cssUri) {
  const csp = webview.cspSource;
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src ${csp}; script-src ${csp};">
<link rel="stylesheet" href="${cssUri}">
</head>
<body>
<div class="app">
  <div class="top">
    <span id="status" class="bad">○ 连接中…</span>
    <button id="startProxy" class="mini">启动服务</button>
  </div>
  <div id="messages"></div>
  <div class="composer">
    <div class="opts">
      <label class="opt"><input type="checkbox" id="agent" checked> 编码智能体（读写文件）</label>
      <label class="opt"><input type="checkbox" id="thinking" checked> 深度思考</label>
      <label class="opt">温度 <input type="number" id="temp" value="0.7" min="0" max="2" step="0.1" class="num"></label>
    </div>
    <textarea id="input" rows="3" placeholder="输入消息，Enter 发送 / Shift+Enter 换行"></textarea>
    <button id="send">发送</button>
  </div>
</div>
<script src="${scriptUri}"></script>
</body>
</html>`;
}

// 调用本地代理，解析 SSE 流式回传（普通聊天模式）
function streamChat(webview, msg) {
  const { host, port, model } = cfg();
  const messages = (msg.history || []).concat([{ role: 'user', content: msg.text }]);
  const payload = JSON.stringify({
    model,
    messages,
    temperature: typeof msg.temperature === 'number' ? msg.temperature : 0.7,
    max_tokens: msg.maxTokens || 2048,
    stream: true,
    chat_template_kwargs: { enable_thinking: msg.thinking !== false }
  });
  const req = http.request({
    host, port, path: '/v1/chat/completions', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }
  }, res => {
    let buf = '';
    res.setEncoding('utf8');
    log('响应状态', res.statusCode);
    if (res.statusCode !== 200) {
      let errBody = '';
      res.on('data', c => { errBody += c; });
      res.on('end', () => {
        log('非 200 响应体', errBody.slice(0, 800));
        let detail = errBody;
        try { const o = JSON.parse(errBody); detail = o.error || o.detail || errBody; } catch (e) {}
        webview.postMessage({ type: 'error', text: '代理返回 ' + res.statusCode + '：' + detail });
      });
      return;
    }
    res.on('data', chunk => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        let line = buf.slice(0, idx).replace(/\r/g, '').trim();
        buf = buf.slice(idx + 1);
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (data === '[DONE]') { webview.postMessage({ type: 'done' }); return; }
        try {
          const j = JSON.parse(data);
          if (j.error) { webview.postMessage({ type: 'error', text: '模型返回错误：' + (j.error.detail || JSON.stringify(j.error)) }); return; }
          const d = j.choices && j.choices[0] && j.choices[0].delta;
          if (!d) continue;
          if (d.reasoning) webview.postMessage({ type: 'delta', kind: 'reasoning', text: d.reasoning });
          if (d.content) webview.postMessage({ type: 'delta', kind: 'content', text: d.content });
        } catch (e) {
          log('SSE 分片解析失败:', data.slice(0, 300), e && e.message);
        }
      }
    });
    res.on('end', () => webview.postMessage({ type: 'done' }));
  });
  req.on('error', e => { log('请求异常', String(e)); webview.postMessage({ type: 'error', text: String(e) }); });
  req.write(payload);
  req.end();
}

function activate(context) {
 try {
  const proxyScript = resolveProxyScript(context);
  outChannel = vscode.window.createOutputChannel('千问侧边栏');
  const provider = {
    resolveWebviewView(webviewView) {
      try {
      const webview = webviewView.webview;
      webview.options = {
        enableScripts: true,
        localResourceRoots: [vscode.Uri.file(context.extensionPath)]
      };
      const scriptUri = webview.asWebviewUri(vscode.Uri.file(path.join(context.extensionPath, 'webview', 'chat.js')));
      const cssUri = webview.asWebviewUri(vscode.Uri.file(path.join(context.extensionPath, 'webview', 'chat.css')));
      webview.html = getHtml(webview, scriptUri, cssUri);
      webview.onDidReceiveMessage(msg => {
        try {
          if (msg.type === 'send') {
            if (msg.agent) agentChat(webview, msg);
            else streamChat(webview, msg);
          } else if (msg.type === 'startProxy')
            ensureProxy(proxyScript).then(ok => webview.postMessage({ type: 'status', ok, msg: ok ? '已连接' : '启动失败，请检查 python 是否在 PATH' }));
        } catch (e) {
          webview.postMessage({ type: 'error', text: '处理消息异常：' + (e && e.message ? e.message : e) });
        }
      });
      ensureProxy(proxyScript).then(ok => {
        log('代理探测', ok ? '已连接' : '未连接');
        webview.postMessage({
          type: 'status', ok,
          msg: ok ? '已连接' : '未连接（点「启动服务」或先按 F5 启动代理）'
        });
      });
      } catch (e) {
        vscode.window.showErrorMessage('千问视图加载失败: ' + (e && e.message ? e.message : e));
      }
    }
  };
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('qwenChatView', provider));

  // 命令面板也可打开侧边栏
  context.subscriptions.push(vscode.commands.registerCommand('qwenChat.open', () => {
    vscode.commands.executeCommand('workbench.view.extension.qwenChat');
  }));
  } catch (e) {
    const msg = '千问扩展激活失败: ' + (e && e.message ? e.message : e);
    vscode.window.showErrorMessage(msg);
    if (outChannel) outChannel.appendLine('激活异常: ' + (e && e.stack ? e.stack : e));
    throw e;
  }
}

function deactivate() {
  if (proxyProc) { try { proxyProc.kill(); } catch (e) {} proxyProc = null; }
}

module.exports = { activate, deactivate };
