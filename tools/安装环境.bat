@echo off
setlocal
title DataBridge 环境安装器
rem ===== 一键部署开发/运行环境：Node.js + JDK17 + Android SDK + npm 依赖 =====
set "TOOLS=C:\Yesnoi\databridge\tools"
set "NODE=%TOOLS%\node\node-v20.19.0-win-x64"
set "JDKDIR=%TOOLS%\jdk\jdk-17.0.20.1+1"
set "SDK=%TOOLS%\android-sdk"
set "PATH=%NODE%;%PATH%"
mkdir "%TOOLS%" 2>nul

echo ============================================
echo  DataBridge 环境安装器（全程免管理员权限）
echo ============================================

if not exist "%NODE%\node.exe" (
  echo [1/6] 下载 Node.js ...
  curl -L -o "%TOOLS%\node.zip" https://nodejs.org/dist/v20.19.0/node-v20.19.0-win-x64.zip
  powershell -Command "Expand-Archive -Path '%TOOLS%\node.zip' -DestinationPath '%TOOLS%\node' -Force"
) else (
  echo [1/6] Node.js 已就绪
)

if not exist "%JDKDIR%\bin\java.exe" (
  echo [2/6] 下载 JDK 17 ...
  curl -L -o "%TOOLS%\jdk17.zip" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
  powershell -Command "Expand-Archive -Path '%TOOLS%\jdk17.zip' -DestinationPath '%TOOLS%\jdk' -Force"
) else (
  echo [2/6] JDK 17 已就绪
)

if not exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" (
  echo [3/6] 下载 Android 命令行工具 ...
  curl -L -o "%TOOLS%\cmdline-tools.zip" https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
  powershell -Command "Expand-Archive -Path '%TOOLS%\cmdline-tools.zip' -DestinationPath '%SDK%\cmdline-tools' -Force"
  ren "%SDK%\cmdline-tools\cmdline-tools" latest
) else (
  echo [3/6] Android 命令行工具已就绪
)

if not exist "%SDK%\licenses\android-sdk-license" (
  echo [4/6] 写入 SDK 许可 ...
  mkdir "%SDK%\licenses" 2>nul
  echo 24333f8a63b6825ea9c5514f83c2829b004d1fee> "%SDK%\licenses\android-sdk-license"
  echo 8933bad161af4178b1185d1a37fbf41ea5269c55>> "%SDK%\licenses\android-sdk-license"
  echo d56f5187479451eabf01fb78af6dfcb131a6481e>> "%SDK%\licenses\android-sdk-license"
  echo 84831b9409646a918e30573bab4c9c91346d8abd> "%SDK%\licenses\android-sdk-preview-license"
) else (
  echo [4/6] SDK 许可已就绪
)

set "JAVA_HOME=%JDKDIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist "%SDK%\build-tools\34.0.0\aapt2.exe" (
  echo [5/6] 安装 Android 构建组件（约 300MB，耐心等待）...
  call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" "build-tools;34.0.0" "platforms;android-34" "platform-tools"
) else (
  echo [5/6] Android 构建组件已就绪
)

echo [6/6] 安装项目 npm 依赖（含 Electron，约 200MB）...
cd /d "%~dp0.."
if not exist "node_modules\express" call npm install --no-audit --no-fund
if not exist "assets\icon.png" call npm run icons

echo.
echo ============================================
echo  全部完成！双击"启动PC端.bat"即可使用
echo  需要打包安卓 APK 时运行 android-app\build.bat
echo ============================================
pause
