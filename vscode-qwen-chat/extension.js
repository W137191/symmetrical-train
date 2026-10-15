const vscode = require('vscode');
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');

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
        if (msg.type === 'send') streamChat(webview, msg);
        else if (msg.type === 'startProxy')
          ensureProxy(proxyScript).then(ok => webview.postMessage({ type: 'status', ok, msg: ok ? '已连接' : '启动失败，请检查 python 是否在 PATH' }));
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

  // 命令面板也可打开侧边栏（对应校园版千问“命令启动”的思路，但无需 Key）
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
