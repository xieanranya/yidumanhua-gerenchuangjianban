# 画质漫画 · 安装包

版本：Android 当前 **`1.9.45 (56)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.45.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.45/EnhanceComic-Android-v1.9.45.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.45（Android）：安卓上色真修复（桌面验证出彩色）

「开了没反应」的三个真根因（全修，并在桌面用同一引擎跑通了完整管线验证）：

1. `Result.get()` 在 onnxruntime 1.31 返回 `Optional<OnnxValue>`，Kotlin 的
   `as? OnnxTensor` 转型永远失败 → 现在 `orElse` 解包；
2. DDColor int8 的输入是 **256×256**（不是 512）→ 从模型信息动态读取；
3. 模型输出是 **Lab 的 ab 色度 2 通道**（不是 RGB 3 通道）→ ab 双线性上采样
   到原尺寸，与原灰度 L 合成后做 Lab→RGB。

上一版（1.9.44）的「开关打开即自动下载模型、入口行状态显示、失败日志」全部保留。

## 黑白漫画 AI 上色

设置 → 阅读器 → 黑白上色。只对在线阅读生效；失败自动回退原图；
每页首次上色几秒，看过的页有缓存秒开。

## 之前版本

### v1.9.44：上色开关即下载 + 状态显示 · v1.9.43：黑白上色首版 · v1.9.42：「12 本 ›」分区列表 · v1.9.36–41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `56` / `1.9.45` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |
| 桌面验证 | 同一 ONNX + 同一模型完整管线出彩色图 ✓ |

