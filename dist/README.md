# 画质漫画 · 安装包

版本：**双端同号 `1.9.69 (80)`**（安卓从 1.9.54 直接追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.69.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.69](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | 本目录 `EnhanceComic-Android-v1.9.69.apk`；也可到 [Release 页 → v1.9.69](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 47 MB |

## v1.9.69（双端）：v4 品牌头 + 简洁首页 + 各源排行（UI 大版本）

**头图定稿**：所有页面顶部统一 **118px 品牌区**（主题色底 + 粉/紫光晕 + 页面标题 + 右上工具钮）——头图里永远不出现封面，三种翻车模式（字打架/一片空/白雾）彻底终结。

**首页（简洁版）**：
- 今日热门横排（保留）
- **排行榜**：源 chips（默认禁漫）+ 周期 chips（今日/本周/月/总）+ 3 列网格带 No.1/2/3 排名角标；
  数据 = 该源 explore 分区按周期关键字匹配（无假数据，匹配不到显示空态）
- 删掉：本周上升最快、为你挑的、Hero 大卡

**发现页（单页直显，模式固定）**：默认显示禁漫内容（分区 chips + 3 列网格），源 chips 一键切换，「聚合」看全部源合并；搜索在当前源内。

**其他**：书架/我的/源管理/详情页头图统一品牌区；底部 Tab 选中色改粉；**安卓版本号从 1.9.54 追平到 1.9.69（build 80），双端同号**。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.69` / `80` |
| Android versionName / versionCode | `1.9.69` / `80` |
| CI（iOS）| success |
| gradle（安卓）| BUILD SUCCESSFUL |

## 之前版本

### v1.9.68：头图氛围背景+横滑修复 · v1.9.66：发现页单页直显 · v1.9.65：全源线路+测速 · v1.9.63-64：禁漫官方线路+图片源 · v1.9.62：哔咔登录态 · v1.9.61：哔咔签到 · v1.9.60：源管理优化 · v1.9.59：v3 沉浸式 UI
