# 画质漫画 · 安装包

版本：Android 当前 **`1.9.49 (60)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.49.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.49/EnhanceComic-Android-v1.9.49.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.49（Android）：阅读防崩墙 + 诊断增强

**装完先到「我的」页确认版本号是 `1.9.49 (60)`**——1.9.47/48 的闪退有可能是
包没装对（重新下载时浏览器给了缓存的旧文件）。

### 这版做了什么

- 阅读页所有后台预取加防崩墙：任何异常只丢一次预取，不崩整个 App；
- 上色引擎状态初始化加兜底；
- **crash.log 每条带版本号**、每次启动写版本记录——再出问题，把
  `Android/data/com.enhance.comic/files/` 下的 **crash.log** 和
  **colorize_error.log** 两个文件发来，就能一次定位（装的哪版、崩在哪、
  上色/下载失败的具体原因）。

## 之前版本

### v1.9.48：修闪退加固+断点续传+阅读进度条 · v1.9.47：上色双风格 · v1.9.46：修上色全黑 · v1.9.45：上色三真根因修复 · v1.9.43-44：黑白上色首版 · v1.9.42：「12 本 ›」 · v1.9.36-41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `60` / `1.9.49` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |

