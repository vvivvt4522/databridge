@echo off
setlocal
rem ===== DataBridge Android 一键构建（aapt2 + javac + d8 + zipalign + apksigner）=====
set "TOOLS=C:\Yesnoi\databridge\tools"
set "JAVA_HOME=%TOOLS%\jdk\jdk-17.0.20.1+1"
set "SDK=%TOOLS%\android-sdk"
set "BT=%SDK%\build-tools\34.0.0"
set "AJAR=%SDK%\platforms\android-34\android.jar"
set "BUILD=%TOOLS%\android-build"
set "APP=%BUILD%\app"
set "SRC=%~dp0"
set "PATH=%JAVA_HOME%\bin;%BT%;%PATH%"

if not exist "%BT%\aapt2.exe" (
  echo [错误] 未找到 Android 构建工具，请先运行 tools\安装环境.bat
  pause
  exit /b 1
)
if not exist "%AJAR%" (
  echo [错误] 未找到 android.jar，请先运行 tools\安装环境.bat
  pause
  exit /b 1
)

echo [1/8] 准备构建目录...
rd /s /q "%BUILD%" 2>nul
mkdir "%APP%"

echo [2/8] 复制源码...
xcopy "%SRC%java" "%APP%\java\" /e /i /q /y >nul
xcopy "%SRC%res" "%APP%\res\" /e /i /q /y >nul
copy /y "%SRC%AndroidManifest.xml" "%APP%\" >nul

echo [3/8] aapt2 编译并链接资源...
call "%BT%\aapt2.exe" compile --dir "%APP%\res" -o "%BUILD%\res.zip" || goto :err
call "%BT%\aapt2.exe" link -o "%BUILD%\base.apk" -I "%AJAR%" --manifest "%APP%\AndroidManifest.xml" --java "%APP%\gen" --min-sdk-version 24 --target-sdk-version 34 "%BUILD%\res.zip" || goto :err

echo [4/8] javac 编译 Java...
dir /s /b "%APP%\gen\*.java" "%APP%\java\*.java" > "%BUILD%\sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "%AJAR%" -d "%BUILD%\classes" "@%BUILD%\sources.txt" || goto :err

echo [5/8] d8 转 dex...
jar cf "%BUILD%\classes.jar" -C "%BUILD%\classes" .
mkdir "%BUILD%\dex" 2>nul
call "%BT%\d8.bat" --release --lib "%AJAR%" --min-api 24 --output "%BUILD%\dex" "%BUILD%\classes.jar" || goto :err

echo [6/8] 把 classes.dex 打进 APK...
pushd "%BUILD%\dex"
call "%BT%\aapt.exe" add "%BUILD%\base.apk" classes.dex || goto :err
popd

echo [7/8] 对齐并签名...
if not exist "%BUILD%\databridge.keystore" (
  keytool -genkeypair -keystore "%BUILD%\databridge.keystore" -alias databridge -keyalg RSA -keysize 2048 -validity 10950 -storepass databridge2026 -keypass databridge2026 -dname "CN=DataBridge" || goto :err
)
call "%BT%\zipalign.exe" -f 4 "%BUILD%\base.apk" "%BUILD%\aligned.apk" || goto :err
call "%BT%\apksigner.bat" sign --ks "%BUILD%\databridge.keystore" --ks-pass pass:databridge2026 --key-pass pass:databridge2026 --out "%BUILD%\DataBridge.apk" "%BUILD%\aligned.apk" || goto :err

echo [8/8] 复制 APK 到项目目录...
copy /y "%BUILD%\DataBridge.apk" "%SRC%..\DataBridge.apk" >nul
echo.
echo ============================================
echo  构建成功！APK 位置: %SRC%..\DataBridge.apk
echo  把它传到手机上安装即可（需允许"未知来源"）
echo ============================================
goto :eof

:err
echo.
echo [错误] 构建失败，请把上面的报错信息发给开发者
pause
exit /b 1
