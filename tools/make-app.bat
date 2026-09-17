@echo off
setlocal
title 生成桌面版 DataBridge
rem ===== 把 PC 端打包成独立 exe（Electron 便携目录 + 桌面快捷方式） =====
set "NODE=C:\Yesnoi\databridge\tools\node\node-v20.19.0-win-x64"
set "PATH=%NODE%;%PATH%"
set "SRC=%~dp0..\
set "APP=C:\Yesnoi\databridge\app"

if not exist "%NODE%\node.exe" (
  echo [错误] 未找到 Node.js，请先运行 tools\安装环境.bat
  pause
  exit /b 1
)

echo [1/6] 清理旧目录...
rd /s /q "%APP%" 2>nul
mkdir "%APP%"

echo [2/6] 复制 Electron 运行时...
xcopy "%SRC%node_modules\electron\dist" "%APP%\" /e /i /q /y >nul

echo [3/6] 重命名主程序...
ren "%APP%\electron.exe" "DataBridge.exe"

echo [4/6] 复制应用代码与依赖...
mkdir "%APP%\resources\app"
copy /y "%SRC%package.json" "%APP%\resources\app\" >nul
xcopy "%SRC%pc" "%APP%\resources\app\pc\" /e /i /q /y >nul
xcopy "%SRC%server" "%APP%\resources\app\server\" /e /i /q /y >nul
xcopy "%SRC%web" "%APP%\resources\app\web\" /e /i /q /y >nul
xcopy "%SRC%assets" "%APP%\resources\app\assets\" /e /i /q /y >nul
robocopy "%SRC%node_modules" "%APP%\resources\app\node_modules" /e /xd electron .bin /nfl /ndl /njh /njs /np >nul
if errorlevel 8 goto :err

echo [5/6] 共享数据目录（与开发目录同一份收件箱）...
rmdir /s /q "%APP%\resources\app\server\data" 2>nul
mklink /J "%APP%\resources\app\server\data" "%SRC%server\data" >nul

echo [6/6] 生成图标与桌面快捷方式...
node "%SRC%tools\make-ico.js" || goto :err
powershell -ExecutionPolicy Bypass -Command "$ws = New-Object -ComObject WScript.Shell; $desktop = [Environment]::GetFolderPath('Desktop'); $sc = $ws.CreateShortcut((Join-Path $desktop '三端互通.lnk')); $sc.TargetPath = '%APP%\DataBridge.exe'; $sc.WorkingDirectory = '%APP%'; $sc.IconLocation = '%APP%\assets\icon.ico,0'; $sc.Save()"
if errorlevel 1 goto :err

echo.
echo ============================================
echo  完成！桌面已创建"三端互通"快捷方式
echo  双击即可启动，无需再运行 bat
echo ============================================
pause
exit /b 0

:err
echo [错误] 生成失败
pause
exit /b 1
