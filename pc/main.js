'use strict';
/* DataBridge PC 端（Electron 主进程）
 * 两种模式：
 *  - local  服务器内嵌在本机（旧行为）
 *  - remote 连接远程服务器（如 fnOS NAS），本机变成客户端：
 *    收到文件自动下载到本机、收到文本自动进剪贴板、托盘一键推送剪贴板
 */
const { app, BrowserWindow, Tray, Menu, ipcMain, clipboard, globalShortcut, nativeImage, shell, Notification } = require('electron');
const path = require('path');
const fs = require('fs');
const WebSocket = require('ws');
const { startServer } = require('../server');

let win = null, tray = null, server = null, quitting = false;
let clientWs = null, clientTimer = null;

const dataDir = path.join(__dirname, '..', 'server', 'data');
const settingsPath = path.join(dataDir, 'pc-settings.json');

function defaultReceiveDir() {
  return path.join(app.getPath('downloads'), 'DataBridge');
}

function loadSettings() {
  try { return JSON.parse(fs.readFileSync(settingsPath, 'utf8')); } catch (e) {}
  return { mode: 'local', remoteUrl: '', receiveDir: '', autoCopyText: true, notifyFile: true };
}
function saveSettings(s) {
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(settingsPath, JSON.stringify(s, null, 2));
}
let settings = loadSettings();

function isRemote() { return settings.mode === 'remote' && !!settings.remoteUrl; }

function trayIcon() {
  const p = path.join(__dirname, '..', 'assets', 'tray.png');
  if (fs.existsSync(p)) return nativeImage.createFromPath(p);
  return nativeImage.createEmpty();
}

function notify(title, body) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: trayIcon() });
  n.on('click', () => showWindow());
  n.show();
}

/* ---------- 远程文件下载 ---------- */
const downloadedFiles = new Map(); // fileId -> 本地路径

function receiveDir() {
  const dir = settings.receiveDir || defaultReceiveDir();
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function sanitizeName(name) {
  return String(name || 'file').replace(/[\\/:*?"<>|]/g, '_').slice(0, 180);
}

async function downloadIncomingFile(item) {
  const dir = receiveDir();
  let target = path.join(dir, sanitizeName(item.name));
  if (fs.existsSync(target)) target = path.join(dir, Date.now() + '_' + sanitizeName(item.name));
  const url = settings.remoteUrl.replace(/\/$/, '') + '/api/file/' + item.id + '?t=' + remoteToken();
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const buf = Buffer.from(await res.arrayBuffer());
  fs.writeFileSync(target, buf);
  downloadedFiles.set(item.id, target);
  return target;
}

/* ---------- 远程客户端（WebSocket） ---------- */
function remoteToken() {
  try { return new URL(settings.remoteUrl).searchParams.get('t') || ''; } catch (e) { return ''; }
}
function remoteApi(p) {
  return settings.remoteUrl.replace(/\/$/, '') + p + (p.includes('?') ? '&' : '?') + 't=' + remoteToken();
}

function startClient() {
  clearTimeout(clientTimer);
  if (!isRemote()) return;
  const wsUrl = settings.remoteUrl.replace(/^http/, 'ws') + '/ws?t=' + remoteToken();
  try { clientWs = new WebSocket(wsUrl); } catch (e) { clientTimer = setTimeout(startClient, 3000); return; }
  clientWs.on('open', () => {
    clientWs.send(JSON.stringify({ type: 'hello', name: '我的电脑', kind: 'pc' }));
    if (tray) tray.setToolTip('三端互通 · 已连接远程服务器');
  });
  clientWs.on('message', (buf) => {
    let m; try { m = JSON.parse(buf.toString('utf8')); } catch (e) { return; }
    if (m.type === 'text') {
      const item = m.item;
      if (settings.autoCopyText) clipboard.writeText(item.content);
      notify('收到文本（来自 ' + item.from + '）', settings.autoCopyText ? '已自动复制到剪贴板' : item.content.slice(0, 60));
    } else if (m.type === 'file') {
      const item = m.item;
      if (settings.notifyFile) notify('收到文件（来自 ' + item.from + '）', item.name + ' · 正在保存到本机…');
      downloadIncomingFile(item).then((p) => {
        if (settings.notifyFile) notify('文件已保存', p);
      }).catch((e) => {
        notify('文件保存失败', item.name + '：' + e.message);
      });
    }
  });
  clientWs.on('close', () => {
    if (tray) tray.setToolTip('三端互通 · 未连接，重试中…');
    clientTimer = setTimeout(startClient, 3000);
  });
  clientWs.on('error', () => { try { clientWs.close(); } catch (e) {} });
}

/* ---------- 剪贴板推送 ---------- */
function pushClipboard() {
  const text = clipboard.readText().trim();
  if (!text) { notify('三端互通', '剪贴板是空的'); return; }
  const api = isRemote() ? remoteApi('/api/text') : ('http://127.0.0.1:' + server.port + '/api/text?t=' + server.token);
  fetch(api, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: text, from: '我的电脑', kind: 'pc' }),
  }).then((r) => {
    if (!r.ok) throw new Error('HTTP ' + r.status);
    notify('三端互通', '剪贴板已推送到所有设备');
  }).catch((e) => notify('三端互通', '推送失败：' + e.message));
}

function showWindow() { if (win) { win.show(); win.focus(); } }

function receivedLocation() {
  return isRemote() ? receiveDir() : path.join(dataDir, 'received');
}

function findReceivedFile(id) {
  if (isRemote()) return downloadedFiles.get(id) || null;
  const dir = path.join(dataDir, 'received');
  if (!fs.existsSync(dir)) return null;
  const f = fs.readdirSync(dir).find((n) => path.basename(n, path.extname(n)) === id);
  return f ? path.join(dir, f) : null;
}

/* ---------- 远程地址设置小窗口 ---------- */
let promptWin = null;
function openRemotePrompt() {
  if (promptWin) { promptWin.focus(); return; }
  promptWin = new BrowserWindow({
    width: 480, height: 240, resizable: false, alwaysOnTop: true,
    title: '连接远程服务器', autoHideMenuBar: true,
    webPreferences: { nodeIntegration: true, contextIsolation: false },
  });
  const current = settings.remoteUrl || '';
  const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
    body{font-family:"Microsoft YaHei",sans-serif;background:#f4f6fb;margin:0;padding:20px}
    p{color:#7a839e;font-size:13px;margin:0 0 10px}
    input{width:100%;box-sizing:border-box;padding:10px;border:1.5px solid #d9e0f0;border-radius:10px;font-size:14px}
    button{margin-top:14px;width:100%;padding:11px;border:none;border-radius:10px;background:#2b6cff;color:#fff;font-size:15px;font-weight:700;cursor:pointer}
  </style></head><body>
    <p>粘贴 fnOS 服务器地址（含配对码），保存后自动切换到远程模式：</p>
    <input id="u" placeholder="http://192.168.x.x:8322/?t=xxxx" value="">
    <button onclick="save()">保存并重启应用</button>
    <script>
      const { ipcRenderer } = require('electron');
      function save(){ const v = document.getElementById('u').value.trim(); if(!v) return; ipcRenderer.send('set-remote', v); }
      document.getElementById('u').addEventListener('keydown', e => { if(e.key === 'Enter') save(); });
    </script>
  </body></html>`;
  promptWin.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  promptWin.on('closed', () => { promptWin = null; });
}

/* ---------- 窗口 / 托盘 ---------- */
function createWindow() {
  win = new BrowserWindow({
    width: 1020, height: 720, minWidth: 400, minHeight: 500,
    title: '三端互通',
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  Menu.setApplicationMenu(null);
  if (isRemote()) {
    win.loadURL(settings.remoteUrl);
  } else {
    win.loadURL('http://127.0.0.1:' + server.port + '/?t=' + server.token);
  }
  win.webContents.setWindowOpenHandler(({ url }) => {
    const localHosts = ['127.0.0.1', 'localhost'];
    let host = '';
    try { host = new URL(url).hostname; } catch (e) {}
    if (url.startsWith('http') && !localHosts.includes(host) && host !== serverHost()) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
}
function serverHost() {
  if (isRemote()) { try { return new URL(settings.remoteUrl).hostname; } catch (e) { return ''; } }
  return '127.0.0.1';
}

function buildTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip('三端互通');
  const menu = Menu.buildFromTemplate([
    { label: '显示主窗口', click: showWindow },
    { label: '推送剪贴板到其他设备 (Ctrl+Alt+V)', click: pushClipboard },
    { label: '打开接收文件夹', click: () => shell.openPath(receivedLocation()) },
    { type: 'separator' },
    { label: '服务器：本机', type: 'radio', checked: !isRemote(), click: () => switchMode('local') },
    { label: '服务器：远程（fnOS）', type: 'radio', checked: isRemote(), enabled: !!settings.remoteUrl, click: () => switchMode('remote') },
    { label: '设置远程服务器地址…', click: openRemotePrompt },
    { type: 'separator' },
    { label: '自动复制收到的文本', type: 'checkbox', checked: !!settings.autoCopyText, click: (i) => { settings.autoCopyText = i.checked; saveSettings(settings); } },
    { label: '文件到达时通知', type: 'checkbox', checked: !!settings.notifyFile, click: (i) => { settings.notifyFile = i.checked; saveSettings(settings); } },
    { label: '开机自启', type: 'checkbox', checked: app.getLoginItemSettings().openAtLogin, click: (i) => app.setLoginItemSettings({ openAtLogin: i.checked }) },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit(); } },
  ]);
  tray.setContextMenu(menu);
  tray.on('double-click', showWindow);
}

function switchMode(mode) {
  if (mode === 'remote' && !settings.remoteUrl) { openRemotePrompt(); return; }
  if ((settings.mode || 'local') === mode) return;
  settings.mode = mode;
  saveSettings(settings);
  app.relaunch();
  app.exit(0);
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', showWindow);

  app.whenReady().then(async () => {
    if (isRemote()) {
      createWindow();
      buildTray();
      startClient();
      try { globalShortcut.register('CommandOrControl+Alt+V', pushClipboard); } catch (e) {}
    } else {
      server = await startServer({ dataDir });
      createWindow();
      buildTray();
      try { globalShortcut.register('CommandOrControl+Alt+V', pushClipboard); } catch (e) {}

      server.emitter.on('text', (item) => {
        if (item.kind === 'pc') return;
        if (settings.autoCopyText) clipboard.writeText(item.content);
        notify('收到文本（来自 ' + item.from + '）', settings.autoCopyText ? '已自动复制到剪贴板' : item.content.slice(0, 60));
      });
      server.emitter.on('file', (item) => {
        if (item.kind === 'pc') return;
        if (settings.notifyFile) notify('收到文件（来自 ' + item.from + '）', item.name + ' · 已保存到 received 目录');
      });

      console.log('[DataBridge] 局域网地址:');
      for (const u of server.urls) console.log('  ' + u);
    }
  });

  app.on('before-quit', () => { quitting = true; });
  app.on('will-quit', () => {
    try { globalShortcut.unregisterAll(); } catch (e) {}
    try { if (server) server.close(); } catch (e) {}
    try { if (clientWs) clientWs.close(); } catch (e) {}
    clearTimeout(clientTimer);
  });
}

/* ---------- IPC ---------- */
ipcMain.handle('clip:read', () => clipboard.readText());
ipcMain.handle('clip:write', (e, t) => { clipboard.writeText(String(t || '')); return true; });
ipcMain.handle('reveal-file', (e, id) => {
  const p = findReceivedFile(id);
  if (p) shell.showItemInFolder(p);
  else notify('提示', isRemote() ? '该文件保存在服务器上，可在页面点"保存"下载到本机' : '文件不存在');
  return !!p;
});
ipcMain.handle('notify', (e, title, body) => { notify(title, body); return true; });
ipcMain.handle('settings:get', () => settings);
ipcMain.handle('settings:set', (e, s) => { settings = Object.assign(settings, s); saveSettings(settings); return settings; });
ipcMain.on('set-remote', (e, url) => {
  settings.remoteUrl = String(url || '').trim();
  settings.mode = 'remote';
  saveSettings(settings);
  if (promptWin) { promptWin.close(); promptWin = null; }
  app.relaunch();
  app.exit(0);
});
