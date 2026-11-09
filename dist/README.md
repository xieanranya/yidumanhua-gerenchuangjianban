# 画质漫画 · 安装包

版本：**双端同号 `1.9.71 (82)`**。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.71.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.71](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.8 MB |
| Android | [Release 页 → v1.9.71](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases)（47 MB，超过 GitHub 文件 API 上限只走 Release） | 47 MB |

## v1.9.71（双端）：排行榜接源真实排行通道

首页排行榜彻底重做——**不再用探索分区凑数，直接调源网站的官方排行接口**：

| 源 | 排行选项 | 数据来源 |
|---|---|---|
| 禁漫 | 總排行 / 月排行 / 周排行 / 日排行 | categories/filter 接口（月榜·总榜实测 80 本；日/周榜源站暂无数据时显示空态）|
| 拷贝漫画 | 日榜 / 周榜 / 月榜 / 总榜 | /rank 接口网页解析（实测四榜各 50 本）|
| 哔咔 | 24小时 / 7天 / 30天 / 骑士榜 | comics/leaderboard（需登录）|
| Komiic | 月 / 綜合 | hotComics（实测 20 本）|
| 绅士漫画 | 今日 / 本週 / 本月 | 需代理 |
| E-Hentai | 昨天 / 本月 / 本年 / 全部 | 需代理 |

- 引擎新增 `rankOptions`（探测排行选项）与 `rank`（拉榜）通用通道——**源网站没有排行接口的源（拷贝外的 noyacg 数据为空、漫画18）不出现在源 chips 里**，不硬凑；
- 周期 chips 的名字直接来自源站（總排行/月排行…），每个源每榜独立缓存；
- 空榜显示「源站还没更新」诚实提示。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.71` / `82` |
| Android versionName / versionCode | `1.9.71` / `82` |
| 包内 `bundle.js` md5（iOS） | `73c30118…` |
| CI（iOS）| success · gradle（安卓）| BUILD SUCCESSFUL |

## 之前版本

### v1.9.70：排行兜底 · v1.9.69：v4 品牌头+简洁首页（双端同号起点）· v1.9.68：头图氛围+横滑 · v1.9.66：发现页单页直显 · v1.9.65：全源线路+测速 · v1.9.63-64：禁漫官方线路+图片源 · v1.9.62：哔咔登录态 · v1.9.61：哔咔签到
