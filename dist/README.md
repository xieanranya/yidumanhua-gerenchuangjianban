# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.61 (72)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.61.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.61](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.61（iOS）：全源线路 + 哔咔签到 + 直连优先

- **线路选项泛化**：有多线路的源在源管理里都能选线路（选中优先，连不上仍自动换备用）：
  - 禁漫：分流 1~4
  - 拷贝漫画：**CDN 线路**（大陆=可直连 / 海外=需代理，默认海外、建议选大陆）+ **节点**（2026copy / 2025copy / copy20 / mangacopy / copy-manga）
- **直连优先**：线路面板里可直连的排前面并标「可直连」绿字；没设置时显示「默认 · 建议选××」
- **哔咔签到**：哔咔登录后出现「自动签到」开关 + 「立即签到」（官方打卡接口，显示连签天数）；
  哔咔登录态写入修正（之前登录后源管理里一直显示「未登录」）
- **自动签到泛化**：所有有签到能力的源统一调度（打开 App 时逐个检查：开关开 + 已登录 + 今天没签才发）
- 无多线路的源（Komiic / noyacg / 绅士 / E-Hentai 均单域名）不出线路行；无签到能力的源不出签到行

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.61` / `72` |
| 包内 `bundle.js` md5 | `233b27ce…`（哔咔 checkin + 登录态修复 + sources 能力标记） |
| CI | success |

## 之前版本

### v1.9.60：源管理优化 + 禁漫线路 + 禁漫签到 · v1.9.59：v3 沉浸式 UI · v1.9.56-58：UI 打磨 · v1.9.55：主页方案 B · v1.9.43-54：上色打磨与定稿 · v1.9.36-41：登录闪退根治 + EH 登录修复
