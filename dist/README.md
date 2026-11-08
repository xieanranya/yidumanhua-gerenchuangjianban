# 画质漫画 · 安装包

版本：iOS 当前 **`1.9.65 (76)`**，Android 当前 `1.9.54 (65)`（安卓对齐时追平）。

## 下载

| 平台 | 位置 | 大小 |
|---|---|---|
| iOS | 本目录 `EnhanceComic-iOS-v1.9.65.ipa`（未签名，需 Sideloadly / AltStore 签名）；也可到 [Release 页 → v1.9.65](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases) | 9.9 MB |
| Android | [Release 页 → v1.9.54](https://github.com/xieanranya/yidumanhua-gerenchuangjianban/releases/download/v1.9.54/EnhanceComic-Android-v1.9.54.apk) | 47 MB |

## v1.9.65（iOS）：全源线路展示 + 现场测速

**8 个源在源管理里全部有线路面板**，打开时自动逐条实测（✓ 含延迟 / ✗ 不通）：

| 源 | 线路 | 直连实测 |
|---|---|---|
| 禁漫 | API 线路 7 条（官方清单，可选）+ 图片源 6 选（官方清单，可选） | 全通 |
| 拷贝漫画 | CDN 线路（大陆/海外，可选）+ API 域名 copy5000.com（自动镜像，只读） | 通 |
| Komiic | komiic.cc / public.komiic.cc（自动镜像，只读） | 通 |
| noyacg | noy1.top + img.noy.asia（只读） | 通 |
| 哔咔 | picaapi.picacomic.com（只读） | 需代理 |
| E-Hentai | e-hentai.org（只读） | 需代理 |
| 绅士漫画 | www.wnacg.com（只读） | 需代理 |
| 漫画18 | 18mh.org（只读） | 需代理 |

线路清单来源：禁漫=官方 App 设置页截图；其余=各源源代码里的真实域名，逐条直连探测过。只读组（单域名）不提供选择，但都能现场测速验证真伪。

### 校验

| 检查项 | 结果 |
|---|---|
| iOS CFBundleShortVersionString / CFBundleVersion | `1.9.65` / `76` |
| CI | success |

## 之前版本

### v1.9.63-64：禁漫官方线路+图片源手选 · v1.9.62：线路实测+哔咔登录态 · v1.9.61：全源线路+哔咔签到 · v1.9.60：源管理优化 · v1.9.59：v3 沉浸式 UI
