const vscode = acquireVsCodeApi();
const history = [];
let streaming = false;

const $ = id => document.getElementById(id);
const messagesEl = $('messages');
const inputEl = $('input');
const statusEl = $('status');
const sendBtn = $('send');

function addMsg(role, text, kind) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  const who = document.createElement('div'); who.className = 'who'; who.textContent = role === 'user' ? '我' : 'Qwen3.5';
  const body = document.createElement('div'); body.className = 'body';
  if (kind === 'reasoning') {
    const d = document.createElement('details'); d.className = 'think';
    const s = document.createElement('summary'); s.textContent = '思考过程';
    const b = document.createElement('div'); b.className = 'think-body'; b.textContent = text;
    d.appendChild(s); d.appendChild(b); body.appendChild(d);
  } else {
    const p = document.createElement('div'); p.className = 'text'; p.textContent = text; body.appendChild(p);
  }
  div.appendChild(who); div.appendChild(body); messagesEl.appendChild(div);
  messagesEl.scrollTop = messagesEl.scrollHeight;
  return body;
}

function appendDelta(body, text) {
  let p = body.querySelector('.text');
  if (!p) { p = document.createElement('div'); p.className = 'text'; body.appendChild(p); }
  p.textContent += text;
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function addToolCard(m) {
  const div = document.createElement('div');
  div.className = 'tool' + (m.status === 'error' ? ' err' : '');
  const head = document.createElement('div'); head.className = 't-head';
  head.textContent = (m.status === 'error' ? '❌ 工具错误: ' : '🔧 工具: ') + m.name + (m.args && m.args.path ? '  ' + m.args.path : '');
  div.appendChild(head);
  if (m.result) {
    const body = document.createElement('div'); body.className = 't-body'; body.textContent = m.result;
    div.appendChild(body);
  }
  messagesEl.appendChild(div);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

window.addEventListener('message', e => {
  try {
  const m = e.data;
  if (m.type === 'status') {
    statusEl.textContent = m.ok ? '● 已连接' : '○ ' + (m.msg || '未连接');
    statusEl.className = m.ok ? 'ok' : 'bad';
  } else if (m.type === 'tool') {
    addToolCard(m);
  } else if (m.type === 'delta') {
    if (m.kind === 'reasoning') {
      let t = document.querySelector('.msg.assistant:last-child .think-body');
      if (!t) { addMsg('assistant', '', 'reasoning'); t = document.querySelector('.msg.assistant:last-child .think-body'); }
      t.textContent += m.text;
    } else {
      let body = document.querySelector('.msg.assistant:last-child .body');
      if (!body) { body = addMsg('assistant', '', 'content'); }
      appendDelta(body, m.text);
    }
  } else if (m.type === 'done') {
    streaming = false; sendBtn.disabled = false; inputEl.disabled = false;
    const last = document.querySelector('.msg.assistant:last-child .text');
    if (last) history.push({ role: 'assistant', content: last.textContent });
  } else if (m.type === 'error') {
    statusEl.textContent = '○ 错误: ' + m.text; statusEl.className = 'bad';
    streaming = false; sendBtn.disabled = false; inputEl.disabled = false;
  }
  } catch (err) {
    const s = document.getElementById('status');
    if (s) { s.textContent = '○ JS 异常: ' + err; s.className = 'bad'; }
    console.error(err);
  }
});

function send() {
  if (streaming) return;
  const text = inputEl.value.trim();
  if (!text) return;
  addMsg('user', text, 'content');
  const hist = history.slice();
  history.push({ role: 'user', content: text });
  inputEl.value = '';
  streaming = true; sendBtn.disabled = true;
  vscode.postMessage({
    type: 'send',
    history: hist,
    text,
    temperature: parseFloat($('temp').value) || 0.7,
    thinking: $('thinking').checked,
    agent: $('agent') ? $('agent').checked : false
  });
}

sendBtn.onclick = send;
inputEl.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
$('startProxy').onclick = () => vscode.postMessage({ type: 'startProxy' });
