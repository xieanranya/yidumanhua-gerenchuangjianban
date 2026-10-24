# 画质漫画 · Android 端源码

纯自用的本地漫画阅读器（Kotlin + Jetpack Compose，阅读区是手写 Canvas 渲染，不用现成图片控件）。
iOS 端在仓库根目录（`EnhanceComic.xcodeproj`）。**两端版本号必须同号，不允许一头高一头。**

| | versionName | versionCode |
|---|---|---|
| Android | `1.9.4` | `15` |
| iOS | `1.9.4` | `15` |

> 版本号只增不减。**不要写 `1.10`** —— 字符串比较里 `"1.10" < "1.9"`，国产 ROM 安装器会判成降级，
> 报「版本不匹配」，必须卸载才能装。宁可写 `1.9.5`。书架标题下会显示 `v1.9.4 (15)`，
> 这是确认「到底装上没装上」的唯一可靠方式。

## 构建

```bash
export JAVA_HOME=<JDK 21>
export ANDROID_HOME=<Android SDK，compileSdk 37，build-tools 37.0.0>
./gradlew :app:assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`，交付用 release 包（debug 包体积大且有额外校验）。

## 签名（重要，不可更换）

`key/enhancecomic.jks` 从 v0.1 起一直沿用，别名 `androiddebugkey`，密码 `android`。
证书指纹 SHA-256：

```
D5:08:4C:AF:65:DD:0D:A0:CB:7C:E7:98:26:E8:23:B5:E1:52:59:1A:BA:10:8F:31:89:AF:27:2B:D8:9F:A4:2D
```

**换了密钥，已装的机器就只能卸载重装，书库和阅读进度全丢。** 换机器编译时把 `key/` 目录拷过去即可。

## 代码结构

```
app/src/main/java/com/enhance/comic/
├── MainActivity.kt
├── VolumeKeyBridge.kt          音量键翻页
├── data/
│   ├── BookStore.kt            书库持久化 + 导入（含 7z/tar 整包解到目录）
│   ├── PageSource.kt           页数据源：ZIP/RAR/7Z/TAR/PDF/文件夹；自动裁白边扫描
│   ├── ArchiveExtractor.kt     commons-compress 解 7z / tar 系列
│   ├── ImageUtil.kt            图像增强 + 内容框检测 detectContentBox
│   └── Enhancer.kt
├── model/
├── render/
│   ├── PageCanvasView.kt       主力渲染（Canvas 2D，解码线程池、预取、蓝光颜色矩阵）
│   ├── PageGLView.kt           可选 GL 渲染
│   └── PageProvider.kt         页面供给接口（displayAspect / cropOf）
└── ui/
    ├── LibraryScreen.kt
    ├── ReaderScreen.kt
    └── ReaderSkin.kt           阅读器皮肤（工具栏、滑杆手绘）
```

## 支持的漫画格式

ZIP/CBZ、RAR/CBR（含 RAR5）、7Z/CB7、TAR/CBT、TAR.GZ/TGZ、TAR.BZ2、TAR.XZ、PDF、图片文件夹。

加密（带密码）的压缩包暂不支持。

## 已知约定

- **自动裁白边**：内容框在**测量阶段**算好，布局用**裁后**的宽高比。只裁图不改比例 = 画面拉伸变形。
- **蓝光过滤**是 0–100 强度，用对角颜色矩阵实现，不能蒙半透明灰（黑色乘完必须还是黑）。
- 布局热路径（每帧都跑的）不加锁、不分配数组。
