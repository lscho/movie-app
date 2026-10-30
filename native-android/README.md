# 超超影视 · Android 本地打包工程

基于 **uni-app x 蒸汽模式离线 SDK 5.26**（`Android-uni-app-x-vapor-SDK@101163-5.26`，与 HBuilderX 5.26.2026091802 完全一致）搭建的本地打包（离线打包）工程。

## 目录布局

```
movie-app/（本仓库根 = uni-app x 工程）
├── build-local.sh                 # ★ 一键打包入口（推荐）
└── native-android/                # 本地打包工程（Android Studio 打开这个）
    ├── dcloud-sdk -> 离线SDK      # 符号链接，不入库；由 build-local.sh 自动创建
    │   ├── SDK/libs/              # 全部 aar 基础库
    │   └── plugins/               # gradle 插件 jar（uts-kotlin / auto-register）
    ├── settings.gradle / build.gradle / gradle.properties
    ├── gradle/ + gradlew          # gradle 9.2.1 wrapper（腾讯镜像）
    ├── native-dlna/               # DLNA 投屏：纯 Kotlin（SSDP + SOAP），另含 UTS 桥接 Kotlin
    │   └── src/main/java/
    │       ├── com/quanmin/dlnacast/       # 原生核心，可用 gradle 真实编译
    │       └── uts/sdk/modules/uniDlnaCast/ # 由 HBuilderX 生成，build-local.sh 自动同步
    └── app/
        ├── build.gradle           # 包名 com.quanmin.movie，minSdk 23 / target 36
        └── src/main/
            ├── AndroidManifest.xml        # 直启 UniAppActivity；含 TV LEANBACK 入口
            ├── assets/apps/<appid>/www/   # HBuilderX 生成的 App 资源（不入库，脚本同步）
            └── res/                       # 图标(mipmap) + TV banner + strings
```

## 打包流程

### 1. 更新 App 资源（uni-app x 代码改动后）

```bash
# 用 HBuilderX CLI 生成（项目路径为 uni-app x 工程根目录）
/Applications/HBuilderX.app/Contents/MacOS/cli publish app-android \
    --type appResource --project /Users/lscho/www/movie

# 推荐直接用仓库根目录的 build-local.sh（自动完成下面所有步骤）
cd <仓库根> && ./build-local.sh
```

（也可在 HBuilderX 界面操作：发行 → 原生App-本地打包 → 生成本地打包App资源）

### 2. 构建 APK

推荐直接用 uni-app x 工程根目录的 `build-local.sh`（版本注入/签名/校验全自动）。

手动 gradle（调试用）：
```bash
cd native-android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug     # 调试包 → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease -PappVersionCode=100 -PappVersionName=1.0.0   # 未签名发布包
```

方式二（Android Studio）：直接打开 `android/` 目录，Run 或 Generate Signed APK。

### 3. 正式发布签名

`build-local.sh release` 已自动处理：首次运行自动生成自签证书
（`uni-app x 工程/build/signing/release.keystore`，随机密码存 `storepass.txt`，权限 600），
用 apksigner 独立签名并校验。要换正式证书，覆盖这两个文件（或设
`KEYSTORE_FILE/KEYSTORE_ALIAS/KEYSTORE_PASSWORD_FILE` 环境变量）即可。

## 关键配置说明

| 项 | 值 | 说明 |
|---|---|---|
| appid | `__UNI__F871D3B` | HBuilderX 导入项目时自动分配，须与 `AndroidManifest.xml` 的 `DCLOUD_UNI_APPID` 和 assets 目录名三方一致 |
| 包名 | `com.quanmin.movie` | 改包名需同步改 `applicationId` |
| ABI | `arm64-v8a` + `armeabi-v7a` | 双 ABI 覆盖 64/32 位电视盒子；需要 x86_64 模拟器再在 `abiFilters` 追加 |
| TV | LEANBACK_LAUNCHER + banner | 电视桌面可直接看到并启动 |
| m3u8/HLS | media3-exoplayer-hls 1.8.0 | video 组件播放所需 |
| 网络 | 已声明 INTERNET 权限，允许明文流量 | 兼容部分 http 资源 CDN |
| 投屏 | `:native-dlna` 模块，零第三方依赖 | SSDP 发现 + AVTransport/RenderingControl 控制；需 `CHANGE_WIFI_MULTICAST_STATE` |
| UTS 插件 | `io.dcloud.uts.kotlin` + `io.dcloud.uts.auto-register` + KSP 2.3.11 | auto-register 依赖 KSP，根 build.gradle 必须声明，否则插件模块无法应用 |

## 版本升级注意

- HBuilderX 升级后，**必须**下载相同版本的离线 SDK 替换 `dcloud-sdk/`，并重新生成 App 资源，否则运行时会提示 SDK 版本不一致
- 依赖仓库已配置阿里云镜像 + jitpack，国内网络可直接构建
- gradle daemon 需要 JDK 25（Android Studio 自带 JBR 即满足），`gradle/gradle-daemon-jvm.properties` 已锁定
