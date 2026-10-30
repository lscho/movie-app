# 超超影视（uni-app x 蒸汽模式）

参照 daga.cc 机制实现的影视聚合搜索与播放 App，采用 **uni-app x + 蒸汽模式（Vapor）**，目标平台为 Android（iOS 可按需启用）。

> 免责声明：本项目仅作技术学习演示。资源与解析接口均来自第三方公开站点，请尊重版权，支持正版。

## 架构

双通道播放策略：

1. **直链通道（主）**：片名搜索 → 苹果CMS 标准资源站 API → 拿到 m3u8 → 原生 `video` 组件播放（ExoPlayer/AVPlayer，支持 HLS）
2. **解析通道（辅）**：粘贴腾讯/爱奇艺/优酷等 VIP 链接 → web-view 加载解析接口（抓取自 daga.cc）播放

## 目录

```
api/
  config.uts    内置资源站与解析接口清单、线路名映射、链接识别
  vod.uts       苹果CMS API 封装（搜索/详情/多源并发聚合）
  store.uts     本地存储（历史/收藏/播放进度/接口配置）
types/
  model.uts     数据模型（VodItem/PlaySource/Episode/HistoryItem 等）
pages/
  index/        首页：搜索入口、VIP链接识别、继续观看
  search/       搜索页：多资源站并发搜索 + 片名去重聚合
  detail/       详情页：简介、多线路切换、剧集网格、收藏
  player/       播放器：原生 m3u8 播放、进度记忆、自动连播、换线重试
  parse/        解析播放：web-view 承载解析接口、可热切换接口
  mine/         我的：历史、收藏、资源站开关、解析接口开关
static/
  icons/        App 图标（各密度 + 自适应前景/背景）
  brand/        品牌标识：logo-mark / 单色版 / 横竖组合
design/
  logo-spec.png 品牌规范板（尺寸、色值、安全边距、深浅场景组合）
tools/
  gen_icons.py  品牌与图标生成脚本（改顶部设计令牌后重跑即可覆盖全部导出）
build-local.sh  一键本地打包（编译→同步→构建→签名→校验）
native-android/ Android 本地打包工程（离线SDK通过符号链接接入，不入库）
```

**新环境初始化**：
1. HBuilderX ≥ 5.21 导入本目录；
2. 下载与本机 HBuilderX 版本一致的[蒸汽模式离线SDK](https://doc.dcloud.net.cn/uni-app-x/native/download/android.html)解压后，设 `DCLOUD_SDK_ROOT` 指向解压目录（首次执行 `./build-local.sh` 会自动建立 `native-android/dcloud-sdk` 符号链接）。

## 已验证的数据源（2026-10-23 实测）

**资源站（m3u8 直链可用）**

| 名称 | API | 备注 |
|---|---|---|
| 360资源 | `https://360zy.com/api.php/provide/vod` | |
| 非凡资源 | `https://cj.ffzyapi.com/api.php/provide/vod` | |
| 暴风资源 | `https://bfzyapi.com/api.php/provide/vod` | |
| 量子资源 | `https://cj.lziapi.com/api.php/provide/vod` | |
| 无损云 | `https://api.wsyzy.net/api.php/provide/vod/` | 来自 [88lin/video_vip](https://github.com/88lin/video_vip)；搜索不支持 `wd=`，走 `wsyzy.cc/index.php/ajax/suggest` 联想接口（`fetchVodSuggest` 专项适配） |

接口协议：`?ac=videolist&wd=片名`（搜索）、`?ac=videolist&ids=ID`（详情，含 `vod_play_from`/`vod_play_url`，`$$$` 分线路、`#` 分集、`$` 分名称与地址）。

**解析接口（来自 daga.cc 与 88lin/video_vip）**

| 名称 | 前缀 | 来源 |
|---|---|---|
| 万能解析·搜片名 | `https://z1.m1907.top/?jx=` | daga.cc（唯一存活） |
| 智能解析(备用) | `https://z1.m1907.cn/?jx=` | daga.cc（302 跳转，不稳定） |
| 优酷/爱奇艺备用 | `https://www.daga.cc/vip2\|vip3/?url=` | daga.cc |
| TXNQ解析 | `https://bfq.txnp.cn/player?url=` | video_vip（实测 200） |
| 789解析 | `https://jiexi.789jiexi.com/?url=` | video_vip（实测 200） |
| 冰豆解析 | `https://bd.jx.cn/?url=` | video_vip（实测 200） |
| 麒麟解析 | `https://free.maccms.xyz/?url=` | video_vip（实测 200） |
| LX/七哥解析 | — | video_vip（实测 403，未集成） |

## 蒸汽模式要点（本项目已遵循）

- manifest.json：`"uni-app-x": { "vapor": true, "vapor-render-target": "bytecode" }`
- 全部页面使用组合式 API（`<script setup lang="uts">`），无 Options API / mixin
- CSS 仅用简单 class 与分组选择器（BEM 风格），无后代选择器
- `list-view` 的 `list-item` 内 `v-for` 均带 `:key`
- 页面显示钩子用 `onPageShow`（组合式 API 下非 `onShow`）
- 事件类型：`UniVideoTimeUpdateEvent` / `UniVideoErrorEvent` / `UniVideoLoadedMetadataEvent` / `UniInputEvent` / `UniWebViewErrorEvent` / `UniSwitchChangeEvent`

## 运行

1. HBuilderX ≥ 5.21（本机 5.34），导入本项目
2. 运行 → 运行到手机或模拟器 → Android（首次会编译蒸汽模式字节码，耗时较长）
3. 鸿蒙：另装 DevEco Studio（HBuilderX 4.81+ 要求 ≥5.1.0.849），在 工具 → 设置 → 运行配置 → 鸿蒙运行配置 中指定其安装路径；把 `manifest.json` 的 `app-harmony.distribute.bundleName` 改成自己的包名（需与 AGC 应用一致，默认占位 `com.example.chaochao`）。首次运行还需配置签名证书（用 DevEco 打开生成的 `unpackage/dist/dev/app-harmony` → Project Structure → Signing Configs → 自动签名，或使用 HBuilderX 的鸿蒙签名配置）；未配置时只能产出未签名 HAP，无法安装到真机。然后 运行 → 运行到鸿蒙。鸿蒙端投屏已实现（NetworkKit MulticastSocket 发现 + SOAP 控制），其余功能待真机验证

## 打包

- **一键本地打包（推荐）**：项目根目录执行

  ```bash
  ./build-local.sh                 # debug 包
  ./build-local.sh release         # release 包（apksigner 签名 + 完整校验）
  ./build-local.sh --apk-only      # uni 代码没变时跳过编译，只重打 APK
  ./build-local.sh release --clean # 清理 gradle 缓存后打包
  ```

  自动完成：HBuilderX 编译（严格校验 + 未就绪重试）→ 同步宿主（appid/app名/模块自检）→ gradle 构建（**版本号从 manifest.json 注入**，与 APK 双向校验）→ release 走 apksigner 独立签名（首次自动生成自签证书，密码在 `build/signing/storepass.txt`）→ 产物报告（SHA-256/证书指纹）。全程约 30 秒，日志在 `build/logs/`。

  说明：uni-app x 不支持脱离 HBuilderX 编译，脚本会在其未运行时**后台隐藏拉起**（不弹窗不抢焦点），之后常驻复用。

- **云打包**：发行 → 原生App-云打包（Android 已声明 VideoPlayer 模块）
- **本地打包（离线）工程**：位于本仓库 `native-android/`，基于蒸汽模式离线 SDK 5.26（与本机 HBuilderX 版本严格一致），已配置 TV 入口与 m3u8 依赖，详见该目录下 README。SDK 本体不入库，经 `native-android/dcloud-sdk` 符号链接接入（`build-local.sh` 自动创建）。

## 电视（Android TV / 盒子）安装「应用不兼容」排查

安装器提示「应用不兼容」几乎都是 **APK 的硬件/ABI 声明与电视不匹配**，按以下顺序排查（本项目 1.0.1 起已全部修正，`build-local.sh` 里有对应门禁，打包时自动校验）：

| 原因 | 现象 | 本项目处理 |
|---|---|---|
| **只含 64 位原生库**（`abiFilters 'arm64-v8a'`） | 32 位老电视盒子（大量运营商/早期盒子）提示不兼容；adb 报 `INSTALL_FAILED_NO_MATCHING_ABIS` | 1.0.1 起同时出 `arm64-v8a` + `armeabi-v7a` |
| **required 的 `uses-feature`**（相机/定位/触屏） | 无摄像头、无 GPS 的电视被安装器或应用市场判定不兼容 | 移除 `uni-web-view` aar 带进来的 `CAMERA`/`ACCESS_FINE_LOCATION`，并显式声明 camera/location 系列 `required="false"` |
| **minSdk 高于电视系统版本** | 提示需更高 Android 版本 | 保持 `minSdk 23`（Android 6.0），已是最低可编译值 |
| **32 位运行时快照被瘦身脚本删掉** | 能装但启动/渲染崩溃（非「不兼容」） | 瘦身只删 dev/x86_64 快照，保留 `-arm.snapshot` + `-arm64.snapshot` |

自查命令（把 APK 换成实际文件）：

```bash
aapt dump badging xxx.apk | grep -E "native-code|uses-feature|sdkVersion"   # 应看到 arm64-v8a 与 armeabi-v7a，且无 uses-feature:
adb install --abi armeabi-v7a xxx.apk                                       # 模拟 32 位设备安装判定
```

电视上仍装不上时，优先确认电视系统版本（设置 → 关于，需 Android 6.0+）与 CPU 架构（32 位盒子选 1.0.1 起的通用包即可）。

## 投屏（DLNA）

播放页右上角「投屏」→ 扫描局域网设备 → 选中电视后起播。**投屏期间手机可以息屏、切后台、甚至被系统回收，电视继续播放**——因为 DLNA 是「电视自己拉流」，手机只下发 URL 与控制指令，视频数据完全不经过手机。

实现按平台分层，职责清晰：

| 层 | 位置 | 职责 |
|---|---|---|
| 前端 | `pages/player/player.uvue` | 投屏按钮、设备选择弹层、遥控面板（暂停/进度/音量/停止）、3 秒轮询状态 |
| UTS 桥接（Android） | `uni_modules/uni-dlna-cast/utssdk/app-android/index.uts` | 线程切换（io→main）、JSON 解析、回调派发；HBuilderX 编译为 Kotlin |
| 原生核心（Android） | `native-android/native-dlna/`（纯 Kotlin） | SSDP 发现（含 MulticastLock）、SOAP 控制、设备描述 XML 解析 |
| 鸿蒙实现 | `uni_modules/uni-dlna-cast/utssdk/app-harmony/index.uts` | 直接用 `@kit.NetworkKit` 的 MulticastSocket（UDPSocket 扩展）收发 SSDP，`uni.request` 拉描述/发 SOAP；对外 API 与 Android 完全一致 |

鸿蒙端的三个关键点（`utssdk/app-harmony/index.uts` 顶部注释有完整说明）：

- 主 socket 绑**随机端口**而不是 1900：单播 M-SEARCH 响应只回到源端口，1900 又常被系统投屏服务占用；
- 先 `NetHandle.bindSocket(udp)` 再 `bind`，把 socket 固定到当前默认网络，避免 Wi-Fi + 蜂窝 + VPN 多网卡时组播走错网卡；
- **API 20 起 `addMembership` / `setMulticastTTL` 只存在于 `MulticastSocket`**（`UDPSocket` 已移除这两个方法），所以用 `socket.constructMulticastSocketInstance()` 创建 socket；`bind` / `send` / `on('message')` 等仍继承自 `UDPSocket`。

权限声明在 `uni_modules/uni-dlna-cast/utssdk/app-harmony/module.json5`（`INTERNET`、`GET_NETWORK_INFO`），HBuilderX 编译时会合并进 HAR 的 `module.json5`。

### 搜不到设备时

播放页点「投屏」后若为空，面板会显示一行**诊断信息**（点一下可复制），据此判断：

| 诊断字段 | 含义与处理 |
|---|---|
| `iface=none` | 没有可用的局域网连接，确认手机连的是 Wi-Fi 而不是仅蜂窝 |
| `iface=192.168.x.x, packets=0` | 组播发不出去：路由器开了 AP 隔离、连了访客网络，或电视侧投屏开关没开 |
| `packets>0, candidates=0` | 收到了包但都不是 SSDP 响应，通常是有其他设备在广播杂包，重试即可 |
| `candidates>0` 但仍无设备 | 设备描述拉取失败（电视 DLNA 服务异常），重启电视 |

**Android 实现要点**（HarmonyOS 端沿用同一套发现/控制协议，平台差异见上；以下每条都对应真机上真实发生的失败）：

- **不能用 1900 端口收包**。真机上 1900 常被系统投屏服务/其他投屏 App 占用，UDP 的 `SO_REUSEADDR` 允许多个 socket 绑同一端口，但单播响应只投递给其中一个 —— 抢不到就永远搜不到，这正是「别的投屏软件能搜到、我们搜不到」的根因。主 socket 改绑**随机端口**，另尽力再绑一个 1900 收 NOTIFY。
- **显式指定组播出站网卡**（`ConnectivityManager` → `LinkProperties.interfaceName`）。多网卡时系统可能把组播发到蜂窝或 VPN。
- **先收包、后拉描述**。设备描述 XML 是独立 HTTP 请求（1~2 秒），放在收包循环里会把后续设备响应全堵住。
- **按 UDN 去重 + 允许坏 LOCATION 回退**。实测创维电视会同时回 `:1206`（HTTP 500）和 `:1838`（正常）两个地址，必须让好的救回来且只展示一台。
- **支持 `NOTIFY ssdp:alive`**（少数设备只广播不响应搜索），并过滤 `ssdp:byebye`。
- **`MX: 2` + 两轮重发 + 广播兜底**，兼顾响应速度与弱网环境。

Android 工程约束（改代码前必读，均为实际踩过的坑）：

- **UTS ↔ Kotlin 跨界只用 JSON 字符串 / 基本类型**。UTS 编译器对 Kotlin data class 属性访问、`Array.length` 的映射不可靠，直接跨边界会得到 `Unresolved reference 'getName'`。
- **UTS 对外 API 用 `export function`**，不要用「函数类型 typealias + `export const`」——后者不会生成到 Kotlin 里。
- **HBuilderX 不会自动生成 gradle 模块**：`build-local.sh` 会把 `unpackage/resources/app-android/uni_modules/*/utssdk/app-android/src/index.kt` 同步进 `native-dlna`（新增 UTS 插件需在脚本的 `case` 里登记落地路径）。
- **`CHANGE_WIFI_MULTICAST_STATE` 必填**，缺了会出现「第一次能搜到设备、之后再也搜不到」。

离线验证 Android 核心逻辑（设备描述解析、`controlURL` 相对路径拼接、SOAP 标签/错误码、DIDL-Lite、时间格式、URL 校验，共 31 项，不需要设备）：

```bash
./tools/dlna-verify/run.sh          # 离线逻辑测试（31 项，无需设备）
./tools/dlna-verify/lan-probe.py    # 在电脑上复刻同一套发现算法，先确认网络环境
```

`lan-probe.py` 与 App 内的 `SsdpDiscovery.kt` 算法一致。**App 搜不到设备时先跑它**：它能发现设备说明是 App 问题，它同样发现不了就是网络/电视侧问题，避免瞎试。

已知限制：

- 投屏地址必须是电视可直接拉取的 **http/https** 直链；`file://`、相册本地文件无法投屏（插件会拦下并提示）。
- HLS(m3u8) 能否播放取决于**电视端解码器**，个别老机型只认 H.264+AAC 的 mp4，会表现为「投屏成功但电视黑屏」。
- 投屏为**单集**模式：播完不会自动连播下一集（自动连播需要手机常驻，与「手机可关闭」冲突），需在手机上点下一集。
- 手机与电视必须同一局域网，且路由器**未开启 AP 隔离**。

## 已知边界与后续可做

- 解析接口失效是常态：在「我的 → 解析接口」开关/切换即可，无需改代码；后续可加远程 JSON 配置热更新接口清单
- web-view 解析页含第三方广告，页面内勿输入任何账号/支付信息；进阶可做 UTS 插件嗅探 m3u8 直链转原生播放
- 播放进度按「片+集」存 storage；历史最多保留 100 条
- 未做：分类浏览、画中画、弹幕、iOS 适配；鸿蒙端已可编译并实现投屏（`uni_modules/uni-dlna-cast/utssdk/app-harmony`），其余功能待真机验证
- 投屏可增强：记住上次设备并自动重连、DLNA 事件订阅（`SUBSCRIBE`）替代 3 秒轮询、多集连播的前台服务方案
