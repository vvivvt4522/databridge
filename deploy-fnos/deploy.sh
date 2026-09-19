#!/bin/bash
# ===== DataBridge fnOS 一键部署脚本（裸机 Node 方案）=====
# 用法：把本文件和 server/ web/ package.json 放在同一目录，SSH 进 fnOS 执行 bash deploy.sh
set -e

NODE_VER=20.19.0
APP_DIR=/opt/databridge
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== 1/5 安装 Node.js（国内镜像，已装则跳过）==="
ARCH=$(uname -m)
case "$ARCH" in
  x86_64) NARCH=x64 ;;
  aarch64|arm64) NARCH=arm64 ;;
  *) echo "不支持的架构: $ARCH"; exit 1 ;;
esac
NODE_DIR="/usr/local/lib/nodejs/node-v${NODE_VER}-linux-${NARCH}"
if ! command -v node >/dev/null 2>&1; then
  curl -fL "https://npmmirror.com/mirrors/node/v${NODE_VER}/node-v${NODE_VER}-linux-${NARCH}.tar.xz" -o /tmp/node.tar.xz
  mkdir -p /usr/local/lib/nodejs
  tar -xJf /tmp/node.tar.xz -C /usr/local/lib/nodejs
  ln -sf "${NODE_DIR}/bin/node" /usr/local/bin/node
  ln -sf "${NODE_DIR}/bin/npm" /usr/local/bin/npm
fi
node -v

echo "=== 2/5 停止旧服务（如有）==="
systemctl stop databridge 2>/dev/null || true

echo "=== 3/5 复制程序文件到 ${APP_DIR} ==="
mkdir -p "$APP_DIR"
cp -r "$SRC_DIR/server" "$SRC_DIR/web" "$SRC_DIR/package.json" "$APP_DIR/"
mkdir -p "$APP_DIR/server/data"
cd "$APP_DIR"
npm config set registry https://registry.npmmirror.com
npm install --omit=dev --no-audit --no-fund

echo "=== 4/5 写入 systemd 服务（开机自启+崩溃自动重启）==="
cat > /etc/systemd/system/databridge.service <<EOF
[Unit]
Description=DataBridge Server
After=network.target

[Service]
WorkingDirectory=${APP_DIR}
ExecStart=/usr/local/bin/node ${APP_DIR}/server/index.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now databridge
sleep 2

echo "=== 5/5 部署结果 ==="
systemctl --no-pager -l status databridge | head -n 5
TOKEN=$(node -e "console.log(require('${APP_DIR}/server/data/config.json').token)")
IP=$(hostname -I | awk '{print $1}')
echo ""
echo "============================================"
echo " 部署成功！三端连接地址："
echo " http://${IP}:8322/?t=${TOKEN}"
echo " 接收文件目录：${APP_DIR}/server/data/received"
echo "============================================"
