# 画质漫画 · 安装包

版本：**双端统一 `1.9.53 (64)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| Android | **[Release 页 → EnhanceComic-Android-v1.9.53.apk](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.53/EnhanceComic-Android-v1.9.53.apk)** | 47 MB |
| iOS | 本目录 `EnhanceComic-iOS-v1.9.53.ipa`（9.7 MB，未签名，需 Sideloadly / AltStore 签名） | 9.7 MB |

## v1.9.53（双端）：黑白漫画 AI 上色（安卓多轮打磨后的最终形态）

iOS 从 1.9.43 一步追齐到 1.9.53，上色功能与安卓完全对齐：

| 项 | 说明 |
|---|---|
| 模型 | **fp32 完整版（117MB，Tinigon 导出）**——之前 iOS 用的 fp16 在部分手机上会推理崩溃，且 iOS 的输出一直有暗部截断（模型输出是 [-1,1]，没做反归一化） |
| 合成 | **线稿混合**：黑色线稿用原图（锐利），其余用模型原生输出（颜色协调）；色度推理精度 768 |
| 进度条 | 阅读页加载进度条（下载百分比 / 「AI 上色中…」） |
| 缓存 | 带算法版本，旧结果自动失效 |

**iOS 用户注意**：装上后原来的 fp16 模型作废，需要在 设置 → 黑白上色 里**重新下载 fp32 模型（117MB）**，下载后旧文件自动清理。

## 之前版本

### v1.9.50-52（安卓）：上色多轮修复（fp32 模型 / 线稿混合 / 缓存失效）· v1.9.49：防崩墙 · v1.9.48：断点续传+进度条 · v1.9.47：双风格 · v1.9.46：修全黑 · v1.9.45：三真根因 · v1.9.43-44：上色首版 · v1.9.42：「12 本 ›」 · v1.9.36-41：登录闪退根治 + EH 登录修复

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.53` / `64` |
| Android versionCode / versionName | `64` / `1.9.53`（已发布） |
| 包内 `bundle.js` md5（两端） | `ed3b1586…`（引擎未动） |


