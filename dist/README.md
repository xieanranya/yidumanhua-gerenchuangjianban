# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.80 (91)`**（安卓源码同步至同号，包按需出）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.80.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.80](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |

## v1.9.80（iOS）：排行榜「显示不出来」根因修复

首页排行榜从 v1.9.71 起一直显示空态的根因：引擎任务返回是 `{ok, data:{...}}` 包装结构，漫画列表在 `data.comics`，iOS 解析却读了顶层 `comics`（永远是 nil）→ 空数组还被当作「已拉过」缓存，切周期/切源也全空。

- 修复解析层级；node 实测引擎数据正常（禁漫總排行 80 本、拷贝日榜 50 本）
- 上一版（1.9.79）已修的竖长页解码（韩漫模糊）一并包含

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.80` / `91` |
| CI | success |

## 之前版本

### v1.9.79：竖长页解码修复 · v1.9.78：v7 阅读器重构 · v1.9.77：海报修复 · v1.9.76：v6 杂志封面 · v1.9.75：排行进首屏 · v1.9.74：v5 极简网格首页 · v1.9.71：排行接源真实通道
