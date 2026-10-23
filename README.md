# 全民影视（uni-app x 蒸汽模式）

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

1. HBuilderX ≥ 5.21（本机 5.26），导入本项目
2. 运行 → 运行到手机或模拟器 → Android（首次会编译蒸汽模式字节码，耗时较长）

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

## 已知边界与后续可做

- 解析接口失效是常态：在「我的 → 解析接口」开关/切换即可，无需改代码；后续可加远程 JSON 配置热更新接口清单
- web-view 解析页含第三方广告，页面内勿输入任何账号/支付信息；进阶可做 UTS 插件嗅探 m3u8 直链转原生播放
- 播放进度按「片+集」存 storage；历史最多保留 100 条
- 未做：分类浏览、投屏、画中画、弹幕、 iOS/鸿蒙适配验证
