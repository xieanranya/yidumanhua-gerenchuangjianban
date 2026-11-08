# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.62 (73)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.62.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.62](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.62（iOS）：线路实测修正 + 哔咔登录态/签到显示修复

- **哔咔登录态修复**：哔咔的登录真相是 token（不写 __logged），源管理里一直显示「未登录」、签到面板不出现——已改为按 token 判定；**装上后如果哔咔还是显示未登录，重新登录一次即可**，之后永久正确
- **线路实测结论**（直连探测）：禁漫 5 条分流直连全通、拷贝/Komiic 经域名别名改写后直连全通（copy5000.com / komiic.cc）、noyacg 直连通；绅士 / E-Hentai / 哔咔必须代理
- **拷贝「节点」行移除**：源里所有节点域名都会被别名层改写到 copy5000.com，选节点没有实际意义；只保留「CDN 线路」（大陆=直连优先 / 海外）

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.62` / `73` |
| 包内 `bundle.js` md5 | `1338bd84…`（哔咔 isLogged + sources 登录态判定 + 线路面板修正） |
| CI | success |

## 之前版本

### v1.9.61：全源线路+哔咔签到+直连优先 · v1.9.60：源管理优化+禁漫线路+签到 · v1.9.59：v3 沉浸式 UI · v1.9.55-58：UI 打磨 · v1.9.43-54：上色定稿 · v1.9.36-41：登录闪退根治
