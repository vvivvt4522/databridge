'use strict';
/* 三端互通 - 共享前端逻辑 v0.3（瀑布流文件墙 + 原生桥） */

const $ = (s) => document.querySelector(s);
const LS = {
  get token() { return localStorage.getItem('db_token') || ''; },
  set token(v) { localStorage.setItem('db_token', v); },
  get server() { return localStorage.getItem('db_server') || ''; },
  set server(v) { localStorage.setItem('db_server', v); },
  get name() { return localStorage.getItem('db_name') || ''; },
  set name(v) { localStorage.setItem('db_name', v); },
  get kind() { return localStorage.getItem('db_kind') || ''; },
  set kind(v) { localStorage.setItem('db_kind', v); },
  get autosave() { return localStorage.getItem('db_autosave') !== '0'; },
  set autosave(v) { localStorage.setItem('db_autosave', v ? '1' : '0'); },
};

/* ---------- 原生桥 ---------- */
const B = window.NativeBridge || null;
const CAP = {
  getClip: !!(B && B.getClipboard),
  setClip: !!(B && B.setClipboard),
  save: !!(B && (B.saveFile || B.saveFromUrl)),
  reveal: !!(B && B.revealFile),
  nativePick: !!(B && B.sendFiles),
  nativeSettings: !!(B && B.openSettings),
};
const MY_KIND = (B && B.kind) || detectKind();
function detectKind() {
  const ua = navigator.userAgent;
  if (/iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'ios';
  if (/Android/.test(ua)) return 'android';
  if (/Electron/.test(ua)) return 'pc';
  return 'web';
}
const KIND_ICON = { pc: '🖥️', android: '🤖', ios: '📱', web: '🌐' };

/* ---------- 基础工具 ---------- */
function toast(msg, ms) {
  const t = $('#toast');
  t.textContent = msg; t.style.display = 'block';
  clearTimeout(toast._h);
  toast._h = setTimeout(() => { t.style.display = 'none'; }, ms || 2200);
}
function fmtSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}
function fmtTime(ts) {
  const d = new Date(ts), now = new Date();
  const hm = d.toTimeString().slice(0, 5);
  if (d.toDateString() === now.toDateString()) return hm;
  return (d.getMonth() + 1) + '-' + d.getDate() + ' ' + hm;
}
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function isImage(f) { return /^image\//.test(f.mime || ''); }
function isVideo(f) { return /^video\//.test(f.mime || ''); }

function apiUrl(p) { return LS.server.replace(/\/$/, '') + p + (p.includes('?') ? '&' : '?') + 't=' + LS.token; }
function fileUrl(id, preview) {
  return apiUrl('/api/file/' + id + (preview ? '?preview=1' : ''));
}
async function api(p, opt) {
  opt = opt || {};
  opt.headers = Object.assign({ 'x-token': LS.token }, opt.headers || {});
  if (opt.body && !(opt.body instanceof FormData) && typeof opt.body !== 'string') {
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(opt.body);
  }
  const r = await fetch(apiUrl(p), opt);
  if (r.status === 401) { showSetup('配对已失效，请重新粘贴 PC 端地址'); throw new Error('401'); }
  if (!r.ok) { let e = {}; try { e = await r.json(); } catch (_) {} throw new Error(e.error || ('HTTP ' + r.status)); }
  return r.json();
}

/* ---------- 配对 ---------- */
function showSetup(msg) {
  if (msg) $('#setup p').textContent = msg;
  $('#setup').classList.remove('hidden');
}
$('#btnSetupGo').onclick = () => {
  let raw = $('#setupUrl').value.trim();
  if (!raw) return toast('请粘贴地址');
  if (!/^https?:\/\//.test(raw)) raw = 'http://' + raw;
  try {
    const u = new URL(raw);
    const t = u.searchParams.get('t');
    if (!t) return toast('地址里缺少配对码 t=');
    LS.server = u.origin; LS.token = t;
    location.reload();
  } catch (e) { toast('地址格式不对'); }
};

/* ---------- 页签 ---------- */
document.querySelectorAll('nav button').forEach(b => {
  b.onclick = () => {
    document.querySelectorAll('nav button').forEach(x => x.classList.remove('active'));
    document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
    b.classList.add('active');
    $('#' + b.dataset.tab).classList.add('active');
  };
});

/* ---------- WebSocket ---------- */
let ws = null, wsTimer = null;
function connectWS() {
  clearTimeout(wsTimer);
  if (!LS.server) return;
  const wsUrl = LS.server.replace(/^http/, 'ws') + '/ws?t=' + LS.token;
  try { ws = new WebSocket(wsUrl); } catch (e) { retryWS(); return; }
  ws.onopen = () => {
    setConn(true);
    ws.send(JSON.stringify({ type: 'hello', name: myName(), kind: MY_KIND }));
    loadFiles(); loadTexts();
  };
  ws.onmessage = (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m.type === 'devices') renderDevices(m.list);
    else if (m.type === 'text') onIncomingText(m.item);
    else if (m.type === 'file') onIncomingFile(m.item);
    else if (m.type === 'files-changed') loadFiles();
    else if (m.type === 'texts-changed') loadTexts();
  };
  ws.onclose = () => { setConn(false); retryWS(); };
  ws.onerror = () => { try { ws.close(); } catch (e) {} };
}
function retryWS() { wsTimer = setTimeout(connectWS, 2500); }
function setConn(on) {
  const el = $('#connState');
  el.textContent = on ? '● 已连接 ' + serverHost() : '○ 未连接，重试中…';
  el.className = on ? 'on' : '';
}
function serverHost() { try { return new URL(LS.server).hostname; } catch (e) { return ''; } }
function myName() {
  if (LS.name) return LS.name;
  const def = { pc: '我的电脑', android: '我的手机', ios: '我的iPad', web: '网页端' }[MY_KIND] || '我的设备';
  LS.name = def; return def;
}

function onIncomingText(item) {
  loadTexts();
  if (item.kind !== MY_KIND) toast('收到来自 ' + item.from + ' 的文本');
}
function onIncomingFile(item) {
  loadFiles();
  if (item.kind === MY_KIND) return;
  if (CAP.save && LS.autosave) autoSave(item);
  else toast('收到文件：' + item.name, 3200);
}
async function autoSave(item) {
  try {
    await saveItem(item);
    toast('已自动保存：' + item.name, 2600);
  } catch (e) {
    toast('自动保存失败：' + item.name, 3200);
  }
}

/* ---------- 瀑布流文件墙 ---------- */
function columnCount() { return window.innerWidth >= 1000 ? 3 : 2; }

async function loadFiles() {
  let list;
  try { list = await api('/api/files'); } catch (e) { return; }
  const box = $('#fileList');
  if (!list.length) { box.innerHTML = '<div class="empty">暂无文件，传一张试试</div>'; return; }
  box.innerHTML = '';
  const n = columnCount();
  const cols = [];
  for (let i = 0; i < n; i++) {
    const c = document.createElement('div');
    c.className = 'wf-col';
    box.appendChild(c);
    cols.push(c);
  }
  list.forEach((f, idx) => cols[idx % n].appendChild(fileCard(f)));
}

function fileCard(f) {
  const card = document.createElement('div');
  card.className = 'wcard';
  const canPreview = isImage(f) || isVideo(f);

  if (isImage(f)) {
    const media = document.createElement('div');
    media.className = 'wmedia';
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = fileUrl(f.id, true);
    img.onclick = () => openPreview(f);
    img.onerror = () => { media.innerHTML = ''; media.appendChild(iconBox('🖼️')); };
    media.appendChild(img);
    card.appendChild(media);
  } else if (isVideo(f)) {
    const media = document.createElement('div');
    media.className = 'wmedia';
    const v = document.createElement('video');
    v.preload = 'metadata';
    v.muted = true;
    v.playsInline = true;
    v.src = fileUrl(f.id, true) + '#t=0.1';
    v.onclick = () => openPreview(f);
    const play = document.createElement('div');
    play.className = 'wplay';
    play.textContent = '▶';
    media.appendChild(v);
    media.appendChild(play);
    v.onerror = () => { media.innerHTML = ''; media.appendChild(iconBox('🎬')); };
    card.appendChild(media);
  } else {
    card.appendChild(iconBox('📄'));
  }

  const info = document.createElement('div');
  info.className = 'winfo';
  const nameEl = document.createElement('div');
  nameEl.className = 'wname';
  nameEl.textContent = f.name;
  if (canPreview) { nameEl.style.cursor = 'pointer'; nameEl.onclick = () => openPreview(f); }
  const meta = document.createElement('div');
  meta.className = 'wmeta';
  meta.textContent = fmtSize(f.size) + ' · ' + f.from + ' · ' + fmtTime(f.time);
  info.appendChild(nameEl);
  info.appendChild(meta);
  card.appendChild(info);

  const act = document.createElement('div');
  act.className = 'wactions';
  if (CAP.save) {
    const b = document.createElement('button');
    b.className = 'wbtn'; b.textContent = '⤓ 保存';
    b.onclick = async (e) => {
      e.stopPropagation();
      b.textContent = '保存中…';
      try { await saveItem(f); toast('已保存'); }
      catch (e2) { toast('保存失败：' + e2.message); }
      b.textContent = '⤓ 保存';
    };
    act.appendChild(b);
  } else if (CAP.reveal) {
    const b = document.createElement('button');
    b.className = 'wbtn'; b.textContent = '⤓ 打开位置';
    b.onclick = (e) => { e.stopPropagation(); B.revealFile(f.id, f.name); };
    act.appendChild(b);
  } else {
    const a = document.createElement('a');
    a.className = 'wbtn'; a.textContent = '⤓ 下载'; a.href = fileUrl(f.id);
    a.setAttribute('download', f.name);
    a.onclick = (e) => e.stopPropagation();
    act.appendChild(a);
  }
  const del = document.createElement('button');
  del.className = 'wbtn del'; del.textContent = '🗑';
  del.onclick = async (e) => {
    e.stopPropagation();
    if (confirm('删除 ' + f.name + ' ?')) { await api('/api/file/' + f.id, { method: 'DELETE' }); loadFiles(); }
  };
  act.appendChild(del);
  card.appendChild(act);
  return card;
}
function iconBox(ch) {
  const d = document.createElement('div');
  d.className = 'wiconbox';
  d.textContent = ch;
  return d;
}

/* 保存到本机：优先原生流式下载，退回 base64 */
async function saveItem(f) {
  if (B && B.saveFromUrl) {
    const ok = await Promise.resolve(B.saveFromUrl(fileUrl(f.id), f.name, f.mime || 'application/octet-stream'));
    if (ok === false) throw new Error('原生保存失败');
    return;
  }
  if (f.size > 100 * 1048576) throw new Error('文件过大，请用浏览器下载');
  const r = await fetch(fileUrl(f.id));
  const blob = await r.blob();
  const b64 = await new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(String(fr.result).split(',')[1]);
    fr.onerror = rej;
    fr.readAsDataURL(blob);
  });
  const ok = await Promise.resolve(B.saveFile(f.name, b64, f.mime || 'application/octet-stream'));
  if (ok === false) throw new Error('原生保存失败');
}

/* ---------- 预览 ---------- */
function openPreview(f) {
  const c = $('#previewContent');
  $('#previewName').textContent = f.name;
  $('#previewMeta').textContent = fmtSize(f.size) + ' · ' + f.from;
  c.innerHTML = '';
  if (isImage(f)) {
    const img = document.createElement('img');
    img.src = fileUrl(f.id, true);
    img.onerror = () => {
      c.innerHTML = '';
      const d = document.createElement('div');
      d.className = 'noPreview';
      d.textContent = '图片加载失败，请检查与 PC 的连接';
      c.appendChild(d);
    };
    c.appendChild(img);
  } else if (isVideo(f)) {
    const v = document.createElement('video');
    v.src = fileUrl(f.id, true);
    v.controls = true; v.autoplay = true; v.playsInline = true;
    c.appendChild(v);
  } else {
    const d = document.createElement('div');
    d.className = 'noPreview';
    d.textContent = '该类型暂不支持预览，可点"保存"到本机查看';
    c.appendChild(d);
  }
  $('#previewModal').classList.remove('hidden');
}
function closePreview() {
  $('#previewContent').innerHTML = '';
  $('#previewModal').classList.add('hidden');
}
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePreview(); });

/* ---------- 上传 ---------- */
const dz = $('#dropzone');
dz.onclick = () => pickMedia();
$('#btnPickMedia').onclick = () => pickMedia();
$('#btnPickFile').onclick = () => pickAny();
function pickMedia() {
  if (CAP.nativePick) { B.sendFiles(true); return; }
  $('#fileInputMedia').click();
}
function pickAny() {
  if (CAP.nativePick) { B.sendFiles(false); return; }
  $('#fileInputFile').click();
}
$('#fileInputMedia').onchange = (e) => { uploadFiles(e.target.files); e.target.value = ''; };
$('#fileInputFile').onchange = (e) => { uploadFiles(e.target.files); e.target.value = ''; };
['dragover', 'dragenter'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('drag'); }));
['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('drag'); }));
dz.addEventListener('drop', e => { if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files); });

function uploadFiles(files) {
  Array.from(files).forEach(f => uploadOne(f));
}
function progressCard(file) {
  const card = document.createElement('div');
  card.className = 'wcard upcard';
  card.innerHTML = '<div class="wiconbox">⬆️</div>' +
    '<div class="winfo"><div class="wname">' + esc(file.name) + '</div>' +
    '<div class="wmeta"><span class="pct">0%</span><span class="progress" style="flex:1"><i></i></span></div></div>';
  const col = document.querySelector('#fileList .wf-col');
  if (col) col.prepend(card); else $('#fileList').prepend(card);
  return card;
}
function uploadOne(file) {
  const card = progressCard(file);
  const fd = new FormData();
  fd.append('file', file, file.name);
  fd.append('from', myName());
  fd.append('kind', MY_KIND);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', apiUrl('/api/file'));
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) {
      const p = Math.round(e.loaded / e.total * 100);
      card.querySelector('.pct').textContent = p + '%';
      card.querySelector('.progress i').style.width = p + '%';
    }
  };
  xhr.onload = () => { card.remove(); if (xhr.status === 200) { toast('已发送 ' + file.name); loadFiles(); } else toast('上传失败：' + (xhr.responseText || xhr.status)); };
  xhr.onerror = () => { card.remove(); toast('上传失败，请检查连接'); };
  xhr.send(fd);
}

/* ---------- 剪贴板 ---------- */
$('#btnSendClip').onclick = async () => {
  let text = '';
  if (CAP.getClip) {
    try { text = await Promise.resolve(B.getClipboard()); } catch (e) { text = ''; }
  }
  if (!text) {
    $('#clipInput').focus();
    toast(CAP.getClip ? '剪贴板是空的，可在下方输入' : '请长按下方输入框选择"粘贴"');
    return;
  }
  await sendText(text);
};
$('#btnSendText').onclick = async () => {
  const v = $('#clipInput').value;
  if (!v.trim()) return toast('先输入内容');
  await sendText(v);
  $('#clipInput').value = '';
};
async function sendText(text) {
  try {
    await api('/api/text', { method: 'POST', body: { content: text, from: myName(), kind: MY_KIND } });
    toast('已发送');
  } catch (e) { toast('发送失败：' + e.message); }
}
async function loadTexts() {
  let list;
  try { list = await api('/api/texts'); } catch (e) { return; }
  const box = $('#clipList');
  if (!list.length) { box.innerHTML = '<div class="empty">暂无内容</div>'; return; }
  box.innerHTML = '';
  for (const t of list) box.appendChild(textItem(t));
}
function textItem(t) {
  const div = document.createElement('div');
  div.className = 'item';
  div.innerHTML = '<div class="fileicon">📋</div>' +
    '<div class="info"><div class="content">' + esc(t.content) + '</div>' +
    '<div class="meta">' + esc(t.from) + ' · ' + fmtTime(t.time) + '</div></div>' +
    '<div class="actions"><button class="linkbtn">复制</button></div>';
  div.querySelector('.linkbtn').onclick = () => copyText(t.content);
  return div;
}
async function copyText(text) {
  if (CAP.setClip) {
    const ok = await Promise.resolve(B.setClipboard(text));
    if (ok !== false) return toast('已复制');
  }
  if (navigator.clipboard && window.isSecureContext) {
    try { await navigator.clipboard.writeText(text); return toast('已复制'); } catch (e) {}
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.cssText = 'position:fixed;opacity:0';
    document.body.appendChild(ta); ta.focus(); ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    if (ok) return toast('已复制');
  } catch (e) {}
  $('#copyModalText').value = text;
  $('#copyModal').classList.remove('hidden');
  $('#copyModalText').select();
}

/* ---------- 设备 ---------- */
function renderDevices(list) {
  const devicesCache = list || [];
  const box = $('#deviceList');
  const map = { pc: '🖥️ 电脑', android: '🤖 Android', ios: '📱 iPhone/iPad', web: '🌐 浏览器' };
  const meName = myName();
  box.innerHTML = '';
  if (!devicesCache.length) { box.innerHTML = '<div class="empty">还没有其他设备连接</div>'; }
  devicesCache.forEach(d => {
    const div = document.createElement('div');
    div.className = 'device';
    div.innerHTML = '<span class="kind">' + (KIND_ICON[d.kind] || '🌐') + '</span>' +
      '<div style="flex:1"><b>' + esc(d.name) + '</b>' + (d.name === meName ? ' <span style="font-size:12px;color:var(--brand)">(本机)</span>' : '') +
      '<div style="font-size:12px;color:var(--sub)">' + (map[d.kind] || d.kind) + '</div></div><span class="dot"></span>';
    box.appendChild(div);
  });
}
async function loadQR() {
  try {
    const r = await api('/api/qr');
    const img = $('#qrImg'); img.src = r.dataUrl; img.hidden = false;
    $('#connUrl').textContent = r.url;
    const sel = $('#ipSelect');
    if (r.urls.length > 1) {
      sel.hidden = false;
      sel.innerHTML = r.urls.map(u => '<option>' + esc(u) + '</option>').join('');
    }
  } catch (e) {}
}
$('#btnChangeServer').onclick = () => {
  if (CAP.nativeSettings) B.openSettings();
  else showSetup('粘贴 PC 端显示的新地址：');
};
$('#myName').textContent = myName();
$('#myName').title = '点击改名';
$('#myName').onclick = () => {
  const n = prompt('设备名称：', myName());
  if (n && n.trim()) { LS.name = n.trim(); $('#myName').textContent = LS.name; if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'hello', name: LS.name, kind: MY_KIND })); }
};

/* ---------- 自动保存开关 ---------- */
function initAutosave() {
  const row = $('#autosaveRow'), chk = $('#autosaveChk');
  if (!CAP.save) { row.hidden = true; return; }
  row.hidden = false;
  chk.checked = LS.autosave;
  chk.onchange = () => { LS.autosave = chk.checked; toast(chk.checked ? '已开启自动保存' : '已关闭自动保存'); };
}

/* 原生连接入口（安卓头部齿轮） */
function initNativeSettingsBtn() {
  if (!CAP.nativeSettings) return;
  const b = $('#btnNativeSettings');
  b.hidden = false;
  b.onclick = () => B.openSettings();
}

/* 窗口尺寸变化 → 重排瀑布流列数 */
let resizeTimer = null;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(loadFiles, 250);
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) { loadFiles(); loadTexts(); }
});

/* ---------- 启动 ---------- */
(async function boot() {
  const u = new URL(location.href);
  const t = u.searchParams.get('t');
  if (t) {
    LS.token = t; LS.server = u.origin;
    u.searchParams.delete('t');
    history.replaceState(null, '', u.pathname + (u.search || '') + u.hash);
  }
  if (!LS.token || !LS.server) { showSetup('首次使用：请粘贴 PC 端窗口显示的完整地址'); return; }
  try {
    const info = await api('/api/info');
    $('#connState').textContent = '● ' + info.hostname;
    $('#connState').className = 'on';
  } catch (e) {
    setConn(false);
  }
  initAutosave();
  initNativeSettingsBtn();
  connectWS();
  loadFiles(); loadTexts(); loadQR();
})();
