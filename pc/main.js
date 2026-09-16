'use strict';
/* DataBridge PC 端（Electron 主进程）：窗口 + 托盘 + 通知 + 剪贴板 */
const { app, BrowserWindow, Tray, Menu, ipcMain, clipboard, globalShortcut, nativeImage, shell, Notification } = require('electron');
const path = require('path');
const fs = require('fs');
const { startServer } = require('../server');

let win = null, tray = null, server = null, quitting = false;
const dataDir = path.join(__dirname, '..', 'server', 'data');
const settingsPath = path.join(dataDir, 'pc-settings.json');

function loadSettings() {
  try { return JSON.parse(fs.readFileSync(settingsPath, 'utf8')); } catch (e) {}
  return { autoCopyText: true, notifyFile: true };
}
function saveSettings(s) {
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(settingsPath, JSON.stringify(s, null, 2));
}
let settings = loadSettings();

function receivedPath(id) {
  const dir = path.join(dataDir, 'received');
  if (!fs.existsSync(dir)) return null;
  const f = fs.readdirSync(dir).find(n => path.basename(n, path.extname(n)) === id);
  return f ? path.join(dir, f) : null;
}

function notify(title, body) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: trayIcon() });
  n.on('click', () => showWindow());
  n.show();
}

function trayIcon() {
  const p = path.join(__dirname, '..', 'assets', 'tray.png');
  if (fs.existsSync(p)) return nativeImage.createFromPath(p);
  return nativeImage.createEmpty();
}

function pushClipboard() {
  const text = clipboard.readText().trim();
  if (!text) { notify('三端互通', '剪贴板是空的'); return; }
  fetch('http://127.0.0.1:' + server.port + '/api/text?t=' + server.token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: text, from: '我的电脑', kind: 'pc' }),
  }).then(() => notify('三端互通', '剪贴板已推送到所有设备'))
    .catch(e => notify('三端互通', '推送失败：' + e.message));
}

function showWindow() {
  if (win) { win.show(); win.focus(); }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1020, height: 720, minWidth: 400, minHeight: 500,
    title: '三端互通',
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  Menu.setApplicationMenu(null);
  win.loadURL('http://127.0.0.1:' + server.port + '/?t=' + server.token);
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http') && !url.startsWith('http://127.0.0.1')) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.on('close', (e) => {
    if (!quitting) { e.preventDefault(); win.hide(); }
  });
}

function buildTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip('三端互通');
  const menu = Menu.buildFromTemplate([
    { label: '显示主窗口', click: showWindow },
    { label: '推送剪贴板到手机 (Ctrl+Alt+V)', click: pushClipboard },
    { label: '打开接收文件夹', click: () => shell.openPath(path.join(dataDir, 'received')) },
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

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', showWindow);

  app.whenReady().then(async () => {
    server = await startServer({ dataDir });
    createWindow();
    buildTray();
    try { globalShortcut.register('CommandOrControl+Alt+V', pushClipboard); } catch (e) {}

    // 服务器事件 → 通知 / 自动复制
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
  });

  app.on('before-quit', () => { quitting = true; });
  app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (e) {} try { if (server) server.close(); } catch (e) {} });
}

// ---------- IPC ----------
ipcMain.handle('clip:read', () => clipboard.readText());
ipcMain.handle('clip:write', (e, t) => { clipboard.writeText(String(t || '')); return true; });
ipcMain.handle('reveal-file', (e, id) => {
  const p = receivedPath(id);
  if (p) shell.showItemInFolder(p);
  return !!p;
});
ipcMain.handle('notify', (e, title, body) => { notify(title, body); return true; });
ipcMain.handle('settings:get', () => settings);
ipcMain.handle('settings:set', (e, s) => { settings = Object.assign(settings, s); saveSettings(settings); return settings; });
