# DataBridge for iPad

原生 iPad 应用（WKWebView 壳 + 原生剪贴板/相册桥）。

## 云端构建

推送到 GitHub 后，`.github/workflows/build-ios.yml` 会在云端 Mac 上自动构建未签名 IPA：

1. 仓库 Actions 页 → Build iOS IPA → 下载 Artifact `DataBridge-unsigned-ipa`
2. 解压得到 `DataBridge-unsigned.ipa`
3. 用 AltStore 自签安装（见项目根目录《iPad安装指南.md》）

## 本地生成工程

```bash
brew install xcodegen
xcodegen generate
open DataBridge.xcodeproj
```
