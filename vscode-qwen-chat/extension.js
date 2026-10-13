const vscode = require('vscode');
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

let proxyProc = null;

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

// 调用本地代理，解析 SSE 流式回传
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
          const d = j.choices && j.choices[0] && j.choices[0].delta;
          if (!d) continue;
          if (d.reasoning) webview.postMessage({ type: 'delta', kind: 'reasoning', text: d.reasoning });
          if (d.content) webview.postMessage({ type: 'delta', kind: 'content', text: d.content });
        } catch (e) { /* 忽略不完整分片 */ }
      }
    });
    res.on('end', () => webview.postMessage({ type: 'done' }));
  });
  req.on('error', e => webview.postMessage({ type: 'error', text: String(e) }));
  req.write(payload);
  req.end();
}

function activate(context) {
  const proxyScript = path.join(context.extensionPath, '..', 'chat-server.py');
  const provider = {
    resolveWebviewView(webviewView) {
      const webview = webviewView.webview;
      webview.options = {
        enableScripts: true,
        localResourceRoots: [vscode.Uri.file(context.extensionPath)]
      };
      const scriptUri = webview.asWebviewUri(vscode.Uri.file(path.join(context.extensionPath, 'webview', 'chat.js')));
      const cssUri = webview.asWebviewUri(vscode.Uri.file(path.join(context.extensionPath, 'webview', 'chat.css')));
      webview.html = getHtml(webview, scriptUri, cssUri);
      webview.onDidReceiveMessage(msg => {
        if (msg.type === 'send') streamChat(webview, msg);
        else if (msg.type === 'startProxy')
          ensureProxy(proxyScript).then(ok => webview.postMessage({ type: 'status', ok, msg: ok ? '已连接' : '启动失败，请检查 python 是否在 PATH' }));
      });
      ensureProxy(proxyScript).then(ok => webview.postMessage({
        type: 'status', ok,
        msg: ok ? '已连接' : '未连接（点「启动服务」或先按 F5 启动代理）'
      }));
    }
  };
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('qwenChatView', provider));

  // 命令面板也可打开侧边栏（对应校园版千问“命令启动”的思路，但无需 Key）
  context.subscriptions.push(vscode.commands.registerCommand('qwenChat.open', () => {
    vscode.commands.executeCommand('workbench.view.extension.qwenChat');
  }));
}

function deactivate() {
  if (proxyProc) { try { proxyProc.kill(); } catch (e) {} proxyProc = null; }
}

module.exports = { activate, deactivate };
