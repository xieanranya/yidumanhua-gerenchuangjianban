# 画质漫画 · 安装包

版本：Android 当前 **`1.9.47 (58)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.47.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.47/EnhanceComic-Android-v1.9.47.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.47（Android）：上色双风格——动漫 / 写实可切换

设置 → 阅读器 → 黑白上色 → 上色风格：

- **动漫 · 明快平涂**（manga-colorization-v2，与 iOS 同款）
- **写实 · 真实质感**（DDColor）

两个模型各约 59MB，**选哪种才下载哪种**，点风格行即切换并自动下载。
上色的管线两端各自验证过：manga 模型输出 RGB、DDColor 输出 ab+Lab 合成，
风格不同但都稳定。

## 黑白漫画 AI 上色

只对在线阅读生效；模型没就绪时自动回退原图；每页首次上色几秒，
看过的页有缓存秒开；彩色源和本地书不受影响。

## 之前版本

### v1.9.46：修上色全黑（alpha） · v1.9.45：三个真根因修复 · v1.9.44：开关即下载 · v1.9.43：黑白上色首版

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `58` / `1.9.47` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |
| 模型桌面验证 | DDColor ✓ / manga ✓（均出彩色） |

