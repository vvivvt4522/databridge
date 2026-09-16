'use strict';
/* PC 端预加载脚本：把原生能力暴露为 window.NativeBridge（与 Android/iOS 端同名接口） */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('NativeBridge', {
  kind: 'pc',
  getClipboard: () => ipcRenderer.invoke('clip:read'),
  setClipboard: (t) => ipcRenderer.invoke('clip:write', t),
  revealFile: (id) => ipcRenderer.invoke('reveal-file', id),
  notify: (title, body) => ipcRenderer.invoke('notify', title, body),
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (s) => ipcRenderer.invoke('settings:set', s),
});
