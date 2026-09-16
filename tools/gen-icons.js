'use strict';
/* 生成三端应用图标（纯 Node，无依赖）：PNG 编码 + 圆角方块 + 双向箭头图形 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      table[n] = c;
    }
  }
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function encodePNG(w, h, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

/* 像素级绘制：渐变圆角方块 + 白色"⇄"双箭头 */
function drawIcon(size) {
  const px = Buffer.alloc(size * size * 4);
  const r = size * 0.22; // 圆角半径
  const inRound = (x, y) => {
    const cx = Math.min(Math.max(x, r), size - r), cy = Math.min(Math.max(y, r), size - r);
    const dx = x - cx, dy = y - cy;
    return dx * dx + dy * dy <= r * r || (x >= r && x <= size - r) || (y >= r && y <= size - r);
  };
  // 箭头：右向箭头 y∈[0.30,0.47]，左向箭头 y∈[0.53,0.70]，杆 + 三角头
  const inArrow = (x, y) => {
    const nx = x / size, ny = y / size;
    const bar = (y0, y1, x0, x1) => nx >= x0 && nx <= x1 && ny >= y0 && ny <= y1;
    const tri = (y0, y1, tx, dir) => { // dir=1 右尖, -1 左尖
      if (dir > 0 && nx >= tx && nx <= tx + 0.16) {
        const w = (nx - tx) / 0.16 * ((y1 - y0) / 2);
        return ny >= (y0 + y1) / 2 - w && ny <= (y0 + y1) / 2 + w;
      }
      if (dir < 0 && nx <= tx && nx >= tx - 0.16) {
        const w = (tx - nx) / 0.16 * ((y1 - y0) / 2);
        return ny >= (y0 + y1) / 2 - w && ny <= (y0 + y1) / 2 + w;
      }
      return false;
    };
    return (bar(0.32, 0.44, 0.16, 0.56) || tri(0.26, 0.50, 0.56, 1)) ||
           (bar(0.56, 0.68, 0.44, 0.84) || tri(0.50, 0.74, 0.44, -1));
  };
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4;
      if (!inRound(x, y)) { px[i + 3] = 0; continue; }
      const t = (x + y) / (2 * size);
      px[i] = Math.round(43 + t * 40);
      px[i + 1] = Math.round(108 - t * 30);
      px[i + 2] = Math.round(255 - t * 30);
      px[i + 3] = 255;
      if (inArrow(x, y)) { px[i] = 255; px[i + 1] = 255; px[i + 2] = 255; }
    }
  }
  return encodePNG(size, size, px);
}

const outDir = path.join(__dirname, '..', 'assets');
fs.mkdirSync(outDir, { recursive: true });
const targets = [
  ['icon.png', 256],
  ['tray.png', 32],
  ['icon-512.png', 512],
  ['icon-192.png', 192],
  ['icon-144.png', 144],
  ['icon-96.png', 96],
  ['icon-72.png', 72],
  ['icon-48.png', 48],
  ['icon-1024.png', 1024],
];
for (const [name, size] of targets) {
  fs.writeFileSync(path.join(outDir, name), drawIcon(size));
  console.log('生成', name, size + 'x' + size);
}
console.log('图标已输出到', outDir);
