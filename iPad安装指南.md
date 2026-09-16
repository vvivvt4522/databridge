# iPad 安装三端互通 · 完整操作指南

> 原理：GitHub 免费提供云端 Mac（Actions），帮我们把 iPad App 编译出来；AltStore 用你的免费 Apple ID 把 App 签名装进 iPad。

---

## 第 1 步：把项目推送到 GitHub（触发云端构建）

### 1.1 网页上创建空仓库
1. 浏览器打开 https://github.com/new
2. Repository name 填 `databridge`（随便起，建议全小写）
3. 选 **Public**（推荐：公有仓库 Actions 完全免费；私有仓库也能用但 macOS 构建消耗免费额度更快）
4. 下面三个初始化选项（README/.gitignore/license）**全部不要勾**
5. 点 **Create repository**

### 1.2 推送代码（二选一）

**方式 A（最省事）：把创建好的仓库地址发我**，格式类似 `https://github.com/你的用户名/databridge.git`，我帮你推送。推送时你电脑上会弹出一个浏览器登录窗口，登录你的 GitHub 账号点授权即可。

**方式 B（自己动手）**：在项目文件夹 `C:\Yesnoi\Y-zcode\三端数据互通工具` 的地址栏输入 `cmd` 回车，逐条执行：

```bat
set PATH=C:\Yesnoi\databridge\tools\git\cmd;%PATH%
git branch -M main
git remote add origin https://github.com/你的用户名/databridge.git
git push -u origin main
```

> 推送时弹出的登录窗口：选"Browser / 浏览器登录"→ 登录 GitHub → 点 Authorize。
> 已提前配好的：git 仓库已初始化、首个提交已完成、.gitignore 已排除 node_modules 和含配对码的 server/data。

### 1.3 等云端构建（约 10~20 分钟）
1. 刷新仓库页面 → 顶部 **Actions** 标签
2. 左侧 "Build iOS IPA" → 点最新一次运行 → 等绿勾 ✅
3. 页面下方 **Artifacts** 区域 → 下载 **DataBridge-unsigned-ipa**
4. 解压得到 `DataBridge-unsigned.ipa`，先放电脑桌面

---

## 第 2 步：AltStore 自签安装

### 2.1 电脑端准备（一次性）
1. **安装 iTunes + iCloud（苹果官网版！不要微软商店版）**
   - iTunes：https://www.apple.com.cn/itunes/download/
   - iCloud：https://support.apple.com/zh-cn/102600 （页面里点"下载 iCloud for Windows"）
   - 装完重启一次电脑
2. **安装 AltServer**：https://altstore.io → Download for Windows → 解压后运行 `AltInstaller` → 装完任务栏出现菱形图标 ◆
3. **生成"App 专用密码"**（你的 Apple ID 开了两步验证才需要，现在基本都开了）：
   - 打开 https://appleid.apple.com → 登录 → 登录与安全 → App 专用密码 → 生成一个（名字随便，如 altstore）→ 复制保存

### 2.2 把 AltStore 装进 iPad
1. 数据线连接 iPad 和电脑 → iPad 弹"信任此电脑"→ 输密码信任
2. 任务栏 ◆ AltServer 图标 → **Install AltStore** → 选你的 iPad → 输入 Apple ID 和刚才的 App 专用密码
3. iPad 上：设置 → 通用 → **VPN与设备管理** → 找到你的 Apple ID → 点**信任**
4. iPad 主屏出现 **AltStore** App

### 2.3 把三端互通装进 iPad
1. 把桌面的 `DataBridge-unsigned.ipa` 传到 iPad 的"文件"App 里
   - 电脑打开 https://apps.apple.com.cn/app/itunes-cloud （网页版 iCloud 传文件）或者用数据线 + iTunes 文件共享，或任意网盘
2. iPad 打开 **AltStore** → **My Apps** → 点左上角 **+**
3. 选中 `DataBridge-unsigned.ipa` → 输入 Apple ID → 等进度条走完
4. 主屏出现 **三端互通** App！

### 2.4 首次使用
1. 打开 PC 端（启动PC端.bat）→ "设备"页 → 复制显示的完整地址
2. iPad 打开三端互通 → 把地址粘贴进输入框 → 点"连接"
3. 开始互传图片和剪贴板 🎉

---

## 第 3 步：7 天续期（免费签名的唯一代价）

Apple 免费账号签名的 App **7 天过期**。续期方法：

- 保持电脑上的 AltServer 运行（开机自启即可）
- iPad 和电脑在**同一 WiFi** 下打开 AltStore，它会自动静默续签（也可以在 My Apps 里下拉手动刷新）

限制：免费 Apple ID 最多 3 个自签 App、每周最多注册 10 个 App ID。嫌麻烦的终极方案：花 99 美元/年买 Apple Developer，签名一次管一年。

---

## 常见问题

| 问题 | 解决 |
|---|---|
| Install AltStore 里看不到 iPad | 检查数据线；确认 iTunes 能识别到 iPad；换 USB 口 |
| 输入 Apple ID 报错 | 两步验证账号必须用"App 专用密码"，不能用登录密码 |
| AltStore 打开闪退 | 设置里重新信任证书；或重新用 AltServer 装一遍 AltStore |
| Actions 构建失败 | 把失败日志截图发我，我来修 |
| App 打开连不上 | 检查 PC 端是否启动、iPad 与电脑是否同一 WiFi、地址是否完整（含 ?t= 配对码） |

> 明天测试如果这套流程还没走完，iPad 先用 **Safari 打开 PC"设备"页的地址**，功能一模一样，先测互通，App 随后再装。
