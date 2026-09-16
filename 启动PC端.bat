@echo off
title DataBridge 三端互通
set "TOOLS=C:\Yesnoi\databridge\tools"
set "NODE=%TOOLS%\node\node-v20.19.0-win-x64"
set "PATH=%NODE%;%PATH%"

if not exist "%NODE%\node.exe" (
  echo [错误] 未找到 Node.js，请先运行 tools\安装环境.bat
  pause
  exit /b 1
)

cd /d "%~dp0"

if not exist "node_modules\express" (
  echo 首次运行，安装依赖中（约 1-2 分钟）...
  call npm install --no-audit --no-fund
)

if exist "node_modules\electron\dist\electron.exe" (
  echo 启动 PC 端应用...
  start "" "node_modules\electron\dist\electron.exe" .
) else (
  echo 未找到 Electron，启动纯服务器模式（浏览器访问下方地址）
  node server\index.js
  pause
)
