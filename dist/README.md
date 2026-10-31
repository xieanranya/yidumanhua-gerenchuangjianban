# 画质漫画 · 安装包

版本：Android 当前 **`1.9.44 (55)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.44.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.44/EnhanceComic-Android-v1.9.44.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.44（Android）：修「开了黑白上色没反应」

最常见原因：**模型没下载**（开关和下载按钮之前是分开的两步）。这版：

- **打开开关即自动下载模型**（带进度），不用再手动点下载；
- 设置入口行直接显示模型状态：`开 · 待下载模型` / `开 · 下载模型 45%` / `开`（就绪）/ `下载失败`；
- 推理失败写日志 `colorize_error.log`（Android/data/com.enhance.comic/files/），
  如果自动下载后还不行，把这个文件发我。

## 黑白漫画 AI 上色

阅读在线黑白漫画时打开「黑白上色」，AI 自动给页面涂色。设置 → 阅读器 → 黑白上色。

| | Android | iOS |
|---|---|---|
| 模型 | **DDColor int8**（写实风）59.1MB | **manga-colorization-v2**（动漫风）58.8MB |
| 推理引擎 | ONNX Runtime（已打包） | 同左 |

- 只对在线阅读生效；模型没就绪时自动回退原图，绝不挡阅读；
- 每页首次上色几秒，看过的页有缓存秒开。

## 之前版本

### v1.9.43：黑白上色首版（双端）· v1.9.42：「12 本 ›」分区列表 · v1.9.36–41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `55` / `1.9.44` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |

