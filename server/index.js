'use strict';
/*
 * DataBridge 服务器核心
 * HTTP API + WebSocket 实时通道 + mDNS 广播（供 Android NSD 自动发现）
 * 安全模型：128 位随机配对码 + 失败限速 + 401 延迟应答（防暴力枚举）
 * 既可独立运行（node server/index.js），也被 Electron 主进程 require
 */
const express = require('express');
const http = require('http');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const { WebSocketServer } = require('ws');
const multer = require('multer');
const QRCode = require('qrcode');
const { Bonjour } = require('bonjour-service');

const DEFAULT_PORT = 8322;

function loadConfig(dataDir) {
  const cfgPath = path.join(dataDir, 'config.json');
  let cfg = null;
  try { cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8')); } catch (e) { /* first run */ }
  if (!cfg || !cfg.token) {
    cfg = { token: crypto.randomBytes(16).toString('hex'), port: DEFAULT_PORT };
    fs.mkdirSync(dataDir, { recursive: true });
    fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2));
  }
  return cfg;
}

function lanIPs() {
  const out = [];
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    for (const it of ifs[name] || []) {
      if (it.family === 'IPv4' && !it.internal) out.push(it.address);
    }
  }
  const isPrivate = (ip) => /^(192\.168\.|10\.|172\.(1\d|2\d|3[01])\.)/.test(ip);
  out.sort((a, b) => (isPrivate(b) ? 1 : 0) - (isPrivate(a) ? 1 : 0));
  return out;
}

function sanitizeName(name, max) {
  let n = String(name || '').replace(/[\\/:*?"<>|\r\n\0]/g, '_').trim();
  if (!n) n = '未命名';
  return n.slice(0, max || 180);
}

function contentDisposition(name) {
  const fallback = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
  return 'attachment; filename="' + fallback + '"; filename*=UTF-8\'\'' + encodeURIComponent(name);
}

/* 失败限速：同一 IP 10 分钟内配对失败超过 8 次 → 拉黑到窗口结束 */
const authFails = new Map(); // ip -> [timestamps]
const FAIL_WINDOW = 10 * 60 * 1000, FAIL_MAX = 8;
function isBlocked(ip) {
  const arr = authFails.get(ip) || [];
  const recent = arr.filter(t => Date.now() - t < FAIL_WINDOW);
  authFails.set(ip, recent);
  return recent.length >= FAIL_MAX;
}
function recordFail(ip) {
  const arr = authFails.get(ip) || [];
  arr.push(Date.now());
  authFails.set(ip, arr);
}

function startServer(opts) {
  opts = opts || {};
  const dataDir = opts.dataDir || path.join(__dirname, 'data');
  const webDir = opts.webDir || path.join(__dirname, '..', 'web');
  const recvDir = path.join(dataDir, 'received');
  const metaPath = path.join(dataDir, 'meta.json');
  fs.mkdirSync(recvDir, { recursive: true });

  const cfg = loadConfig(dataDir);
  const port = opts.port || cfg.port || DEFAULT_PORT;

  let meta = { files: [], texts: [] };
  try { meta = JSON.parse(fs.readFileSync(metaPath, 'utf8')); } catch (e) { /* first run */ }
  if (!Array.isArray(meta.files)) meta.files = [];
  if (!Array.isArray(meta.texts)) meta.texts = [];
  const saveMeta = () => { try { fs.writeFileSync(metaPath, JSON.stringify(meta)); } catch (e) {} };

  const emitter = new EventEmitter();
  const app = express();
  app.use(express.json({ limit: '2mb' }));

  // token 校验（仅 API；静态页面放行，页面自身不含数据）
  app.use((req, res, next) => {
    if (req.path.startsWith('/api/')) {
      const ip = req.socket.remoteAddress || '?';
      const t = req.query.t || req.get('x-token') || '';
      if (t !== cfg.token) {
        if (isBlocked(ip)) return res.status(429).json({ error: '尝试过于频繁，请稍后再试' });
        recordFail(ip);
        // 延迟应答，拖慢暴力枚举
        return setTimeout(() => res.status(401).json({ error: '配对码无效' }), 600);
      }
    }
    next();
  });
  app.use('/api', (req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
      console.log('[api]', new Date().toLocaleTimeString(), req.method, req.originalUrl.split('?')[0], res.statusCode, (Date.now() - start) + 'ms');
    });
    next();
  });
  app.use(express.static(webDir));

  const upload = multer({
    storage: multer.diskStorage({
      destination: (req, file, cb) => cb(null, recvDir),
      filename: (req, file, cb) => {
        const ext = path.extname(sanitizeName(file.originalname)).toLowerCase().slice(0, 12);
        cb(null, crypto.randomBytes(8).toString('hex') + ext);
      },
    }),
    limits: { fileSize: 2 * 1024 * 1024 * 1024 },
  });

  // ---------- API ----------
  app.get('/api/info', (req, res) => {
    res.json({
      app: 'DataBridge', version: '0.2.0',
      hostname: os.hostname(), port,
      ips: lanIPs(), devices: deviceList(),
    });
  });

  app.get('/api/qr', async (req, res) => {
    const ips = lanIPs();
    const url = 'http://' + (ips[0] || '127.0.0.1') + ':' + port + '/?t=' + cfg.token;
    const urls = ips.map(ip => 'http://' + ip + ':' + port + '/?t=' + cfg.token);
    try {
      const dataUrl = await QRCode.toDataURL(url, { width: 420, margin: 1 });
      res.json({ url, urls, dataUrl });
    } catch (e) { res.status(500).json({ error: String(e.message || e) }); }
  });

  app.get('/api/texts', (req, res) => res.json(meta.texts));

  app.post('/api/text', (req, res) => {
    const b = req.body || {};
    const content = String(b.content || '');
    if (!content.trim()) return res.status(400).json({ error: '内容为空' });
    if (content.length > 200000) return res.status(413).json({ error: '内容过长' });
    const item = {
      id: crypto.randomBytes(6).toString('hex'),
      content, time: Date.now(),
      from: sanitizeName(b.from, 40), kind: /^(pc|android|ios|web)$/.test(b.kind) ? b.kind : 'web',
    };
    meta.texts.unshift(item);
    if (meta.texts.length > 100) meta.texts.length = 100;
    saveMeta();
    broadcast({ type: 'text', item });
    emitter.emit('text', item);
    res.json(item);
  });

  app.get('/api/files', (req, res) => res.json(meta.files));

  app.post('/api/file', upload.single('file'), (req, res) => {
    if (!req.file) return res.status(400).json({ error: '没有文件' });
    // multer 默认按 latin1 解码文件名，中文需转回 utf8
    const original = Buffer.from(req.file.originalname, 'latin1').toString('utf8');
    const b = req.body || {};
    const item = {
      id: path.basename(req.file.filename, path.extname(req.file.filename)),
      name: sanitizeName(original), stored: req.file.filename,
      size: req.file.size, mime: req.file.mimetype || 'application/octet-stream',
      from: sanitizeName(b.from, 40), kind: /^(pc|android|ios|web)$/.test(b.kind) ? b.kind : 'web',
      time: Date.now(),
    };
    meta.files.unshift(item);
    if (meta.files.length > 500) {
      for (const r of meta.files.splice(500)) { try { fs.unlinkSync(path.join(recvDir, r.stored)); } catch (e) {} }
    }
    saveMeta();
    broadcast({ type: 'file', item });
    emitter.emit('file', item);
    res.json(item);
  });

  app.get('/api/file/:id', (req, res) => {
    const item = meta.files.find(f => f.id === req.params.id);
    if (!item) return res.status(404).json({ error: '不存在' });
    const p = path.join(recvDir, item.stored);
    if (!fs.existsSync(p)) return res.status(404).json({ error: '文件已丢失' });
    // 浏览器直接打开（预览）时不强制下载：预览用 ?preview=1，否则带下载头
    if (!req.query.preview) res.setHeader('Content-Disposition', contentDisposition(item.name));
    res.setHeader('Content-Type', item.mime);
    res.setHeader('Cache-Control', 'private, max-age=86400');
    fs.createReadStream(p).pipe(res);
  });

  app.delete('/api/file/:id', (req, res) => {
    const i = meta.files.findIndex(f => f.id === req.params.id);
    if (i < 0) return res.status(404).json({ error: '不存在' });
    try { fs.unlinkSync(path.join(recvDir, meta.files[i].stored)); } catch (e) {}
    meta.files.splice(i, 1); saveMeta();
    broadcast({ type: 'files-changed' });
    res.json({ ok: true });
  });

  app.delete('/api/text/:id', (req, res) => {
    const i = meta.texts.findIndex(t => t.id === req.params.id);
    if (i >= 0) { meta.texts.splice(i, 1); saveMeta(); broadcast({ type: 'texts-changed' }); }
    res.json({ ok: true });
  });

  // 统一错误处理：上传解析失败等
  app.use((err, req, res, next) => {
    console.error('[server-error]', new Date().toLocaleTimeString(), req.method, req.path, err.message || err);
    if (res.headersSent) return next(err);
    const code = (err && err.code === 'LIMIT_FILE_SIZE') ? 413 : (err.status || 500);
    res.status(code).json({ error: err.message || '服务器错误' });
  });
  // ---------- WebSocket ----------
  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });
  const devices = new Map();

  wss.on('connection', (ws, req) => {
    let u;
    try { u = new URL(req.url, 'http://x'); } catch (e) { ws.close(); return; }
    if (u.searchParams.get('t') !== cfg.token) { ws.close(4001, 'bad token'); return; }
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('error', () => {});
    ws.on('message', (buf) => {
      let msg;
      try { msg = JSON.parse(buf.toString('utf8')); } catch (e) { return; }
      if (msg.type === 'hello') {
        devices.set(ws, {
          id: crypto.randomBytes(4).toString('hex'),
          name: sanitizeName(msg.name, 40),
          kind: /^(pc|android|ios|web)$/.test(msg.kind) ? msg.kind : 'web',
        });
        ws.send(JSON.stringify({ type: 'welcome', me: devices.get(ws) }));
        broadcastDevices();
      }
    });
    ws.on('close', () => { devices.delete(ws); broadcastDevices(); });
  });

  const hb = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) { ws.terminate(); continue; }
      ws.isAlive = false;
      try { ws.ping(); } catch (e) {}
    }
  }, 10000);

  function deviceList() { return Array.from(devices.values()); }
  function broadcastDevices() { broadcast({ type: 'devices', list: deviceList() }); }
  function broadcast(obj) {
    const s = JSON.stringify(obj);
    for (const ws of wss.clients) { if (ws.readyState === 1) { try { ws.send(s); } catch (e) {} } }
  }

  // ---------- mDNS（Android NSD 自动发现用）----------
  let bonjour = null, svc = null;
  try {
    bonjour = new Bonjour();
    svc = bonjour.publish({ name: 'DataBridge-' + os.hostname(), type: 'http', port, txt: { app: 'databridge', token: cfg.token, v: '2' } });
  } catch (e) { console.warn('[mDNS] 发布失败(不影响手动连接):', e.message); }

  return new Promise((resolve) => {
    server.listen(port, '0.0.0.0', () => {
      const ips = lanIPs();
      resolve({
        port, token: cfg.token,
        urls: ips.map(ip => 'http://' + ip + ':' + port + '/?t=' + cfg.token),
        primaryUrl: 'http://' + (ips[0] || '127.0.0.1') + ':' + port + '/?t=' + cfg.token,
        emitter,
        close() {
          try { if (svc) svc.stop(); } catch (e) {}
          try { if (bonjour) bonjour.destroy(); } catch (e) {}
          clearInterval(hb);
          try { wss.close(); } catch (e) {}
          try { server.close(); } catch (e) {}
        },
      });
    });
    server.on('error', (e) => { console.error('[服务器] 端口被占用?', e.message); process.exit(1); });
  });
}

module.exports = { startServer, lanIPs, DEFAULT_PORT };

// 独立运行：node server/index.js
if (require.main === module) {
  (async () => {
    const s = await startServer();
    console.log('========================================');
    console.log('  DataBridge v0.2 server ready');
    console.log('  local: http://localhost:' + s.port + '/?t=' + s.token);
    for (const u of s.urls) console.log('  lan:   ' + u);
    console.log('========================================');
  })();
}
