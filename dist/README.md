# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.68 (79)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.68.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.68](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.68（iOS）：头图氛围背景 + 发现页横滑修复

- **头图不空了**：纯渐变确实单调。改成 Apple Music 式「氛围背景」——今日最热封面（书架=在读第一本）放大 + 高斯模糊 + 压暗当头图底衬：有色彩光影但封面文字不可读，标题保持清晰，也不与 Hero 卡重复（模糊的是同一本的氛围，不是清晰重复）
- **发现页横滑修复**：源 chips / 分区 chips 嵌在纵向滚动里时手势会被外层吞掉（横向滚动没有显式高度），补上固定高度后横向滑动正常

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.68` / `79` |
| CI | success |

## 之前版本

### v1.9.67：头图纯渐变 · v1.9.66：发现页单页直显 · v1.9.65：全源线路+测速 · v1.9.63-64：禁漫官方线路+图片源 · v1.9.62：哔咔登录态 · v1.9.61：哔咔签到 · v1.9.60：源管理优化 · v1.9.59：v3 沉浸式 UI
