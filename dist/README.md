# 画质漫画 · 安装包

版本：Android 当前 **`1.9.50 (61)`**，iOS 当前 **`1.9.43 (54)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.50.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.50/EnhanceComic-Android-v1.9.50.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.43.ipa`（9.7 MB） | 9.7 MB |

## v1.9.50（Android）：上色闪退真根因 —— 动漫模型换 fp32

**「开了上色翻页就闪退」的根因**：动漫风格之前用的是 **fp16 版模型**，
它在部分手机的 ONNX Runtime（ARM 版）上推理会**直接崩溃**——native 层崩溃，
Java 的 crash.log 都接不住。我在桌面 x64 上验证是好的，但手机 ARM 不行，
这是 fp16 移动端推理的已知坑。

**修复**：换成同一家模型的 **fp32 完整版（117MB，Tinigon 导出）**，
已用同一套引擎在桌面跑通完整管线（真实漫画页出彩色）。顺带修了
模型输出值域（[-1,1] → 反归一化），暗部不再被误截成黑。

- 动漫模型现在是 **117MB**（之前 59MB），断点续传有效，下载慢一点但一次就好；
- 写实风格（DDColor）不变；
- 旧 fp16 文件会在新模型下载成功后自动清理。

## 之前版本

### v1.9.49：阅读防崩墙+诊断 · v1.9.48：断点续传+阅读进度条 · v1.9.47：上色双风格 · v1.9.46：修上色全黑 · v1.9.45：上色三真根因修复 · v1.9.43-44：黑白上色首版 · v1.9.42：「12 本 ›」 · v1.9.36-41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| Android versionCode / versionName | `61` / `1.9.50` |
| Android 签名 SHA-256 | `d5084caf…`（未变） |
| 包内 `bundle.js` md5 | `ed3b1586…`（引擎未动） |
| 动漫模型（fp32）桌面验证 | 全管线出彩色 ✓ |
| 写实模型（DDColor int8）桌面验证 | 全管线出彩色 ✓ |

