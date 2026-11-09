# 画质漫画 · 安装包

版本：**双端同号 `1.9.70 (81)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.70.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.70](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.70](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases)（47 MB，超过 GitHub 文件 API 上限只走 Release） | 47 MB |

## v1.9.70（双端）：排行榜兜底修复

首页排行榜「加载不出来」的根因：默认周期是「今日」，但多数源（如禁漫）的 explore 分区里没有日榜分区 → 匹配不到 → 只显示空态文案。

- **匹配不到任何周期榜时兜底显示第一个分区**（任何源任何周期都有内容），并标注「该源没有独立的「X」榜，以下来自分区「Y」」——不造假，说明来源；
- iOS 修复首次进入时源列表未就绪导致的匹配空转（兜底拉取后回写选中态）。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.70` / `81` |
| Android versionName / versionCode | `1.9.70` / `81` |
| CI（iOS）| success · gradle（安卓）| BUILD SUCCESSFUL |

## 之前版本

### v1.9.69：v4 品牌头+简洁首页+各源排行（双端同号起点）· v1.9.68：头图氛围+横滑 · v1.9.66：发现页单页直显 · v1.9.65：全源线路+测速 · v1.9.63-64：禁漫官方线路+图片源 · v1.9.62：哔咔登录态 · v1.9.61：哔咔签到
