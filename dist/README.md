# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.63 (74)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.63.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.63](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.63（iOS）：禁漫 API 线路 + 图片源全部可手选

对齐禁漫官方 App 的「线路与图源」设置页（清单来自官方截图）：

- **API 线路**：cdngwc.club / cdngwc.net / cdnutc.cc / **cdnbtd.cc（新）** / cdnhjk.net / **cdngwc.cc（新）** / cdnutc.me——共 7 条，直连实测全部可达
- **图片源**：跟随服务配置（默认，官方推荐）/ cdn-msp3.jmapiproxy1.cc / cdn-msp.jmapiproxy1.cc / cdn-msp.jmapiproxy2.cc / cdn-msp2.jmapiproxy2.cc / cdn-msp3.jmapiproxy2.cc——直连实测全部 200
- 图片源手选立即生效（封面 + 正文 + 头像图全部走选定的源）；引擎侧两个源文件与安卓共用一份 bundle
- 其余源维持：拷贝=CDN线路（大陆/海外）；单域名源（Komiic/noyacg/绅士/EH）无线路可选

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.63` / `74` |
| 包内 `bundle.js` md5 | `cd94be0b…`（禁漫官方线路表 + imgDomain 设置） |
| CI | success |

## 之前版本

### v1.9.62：线路实测+哔咔登录态修复 · v1.9.61：全源线路+哔咔签到 · v1.9.60：源管理优化+禁漫线路 · v1.9.59：v3 沉浸式 UI · v1.9.55-58：UI 打磨 · v1.9.43-54：上色定稿
