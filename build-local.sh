#!/usr/bin/env bash
# ============================================================
# 超超影视 · 一键本地打包脚本 v2
# 流程：HBuilderX 生成资源(严格校验/重试) → 同步宿主(版本/appid/app名) →
#       gradle 构建(版本号注入) → release 走 apksigner 独立签名校验 → 产物报告
# 用法:
#   ./build-local.sh                 # debug 包
#   ./build-local.sh release         # release 包（apksigner 签名）
#   ./build-local.sh --apk-only      # 跳过 uni 编译，仅重打 APK
#   ./build-local.sh release --clean # 清理后打包
#   ./build-local.sh --out xx.apk release
# 选项: --apk-only/--skip-compile | --clean | --out <file> | -h
# 环境变量: UNI_PROJECT / ANDROID_PROJ / ANDROID_SDK / JBR_JAVA /
#           KEYSTORE_FILE / KEYSTORE_ALIAS / KEYSTORE_PASSWORD_FILE
# 说明: uni-app x 不支持脱离 HBuilderX 编译；脚本会在其未运行时
#       后台隐藏拉起（不弹窗、不抢焦点），之后常驻复用。
# ============================================================
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNI_PROJECT="${UNI_PROJECT:-$SCRIPT_DIR}"
ANDROID_PROJ="${ANDROID_PROJ:-$UNI_PROJECT/native-android}"
# SDK 存放于系统应用支持目录（稳定、不易被误删）；可用 DCLOUD_SDK_ROOT 覆盖；缺失时自动下载
SDK_ROOT="${DCLOUD_SDK_ROOT:-$HOME/Library/Application Support/QuanMinMovie/dcloud-sdk}"
SDK_URL="https://web-ext-storage.dcloud.net.cn/uni-app-x/sdk/Android-Vapor/Android-uni-app-x-vapor-SDK@101163-5.26.zip"
HBX_CLI="${HBX_CLI:-/Applications/HBuilderX.app/Contents/MacOS/cli}"
JBR_JAVA="${JBR_JAVA:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
KEYSTORE_FILE="${KEYSTORE_FILE:-$UNI_PROJECT/build/signing/release.keystore}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-quanmin}"
KEYSTORE_PASSWORD_FILE="${KEYSTORE_PASSWORD_FILE:-$UNI_PROJECT/build/signing/storepass.txt}"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; OFF='\033[0m'
LOG=/dev/null
log()  { printf '==> %s\n' "$*" | tee -a "$LOG"; }
ok()   { printf "${GREEN}✔ %s${OFF}\n" "$*" | tee -a "$LOG"; }
warn() { printf "${YELLOW}警告: %s${OFF}\n" "$*" | tee -a "$LOG" >&2; }
die()  { printf "${RED}错误: %s${OFF}\n" "$*" | tee -a "$LOG" >&2; exit 1; }

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; }

# ---------- 参数解析 ----------
BUILD_TYPE="debug"
APK_ONLY=0
CLEAN=0
OUTPUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    debug|release) BUILD_TYPE="$1"; shift ;;
    --apk-only|--skip-compile) APK_ONLY=1; shift ;;
    --clean) CLEAN=1; shift ;;
    --out) [[ $# -ge 2 ]] || die "--out 需要文件路径"; OUTPUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1（--help 查看用法）" ;;
  esac
done

# ---------- manifest 解析 ----------
read_json() {
  local file="$1" key="$2"
  if command -v plutil >/dev/null 2>&1; then
    plutil -extract "$key" raw "$file" 2>/dev/null && return
  fi
  sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "$file" | head -1
}
MANIFEST="$UNI_PROJECT/manifest.json"
[[ -f "$MANIFEST" ]] || die "找不到 manifest.json: $MANIFEST"
APPID="$(read_json "$MANIFEST" appid || true)"
APP_NAME="$(read_json "$MANIFEST" name || true)"
VERSION_NAME="$(read_json "$MANIFEST" versionName || true)"
VERSION_CODE="$(read_json "$MANIFEST" versionCode || true)"
[[ -n "$APPID" && -n "$APP_NAME" && -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] ||
  die "manifest.json 缺少 appid/name/versionName/versionCode"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "versionCode 必须是整数: $VERSION_CODE"
[[ "$APPID" == __UNI__* ]] || die "appid 格式异常: $APPID"

# ---------- 环境检查 ----------
[[ -d "$ANDROID_PROJ/app" ]] || die "安卓工程不存在: $ANDROID_PROJ"
# SDK 自愈：缺失则自动下载到系统应用支持目录（约240MB，一次性）
if [[ ! -e "$SDK_ROOT/SDK/libs/app-runtime-release.aar" ]]; then
  warn "离线SDK缺失，开始自动下载（约240MB，一次性）…"
  DL_DIR="$(dirname "$SDK_ROOT")"
  mkdir -p "$DL_DIR"
  curl -sL --fail -o "$DL_DIR/dcloud-sdk-download.zip" "$SDK_URL" || die "SDK 下载失败：$SDK_URL"
  unzip -q -o "$DL_DIR/dcloud-sdk-download.zip" -d "$DL_DIR/tmp-sdk"
  rm -rf "$SDK_ROOT"
  mv "$DL_DIR/tmp-sdk/Android-uni-app-x-vapor-SDK@"* "$SDK_ROOT"
  rm -rf "$DL_DIR/tmp-sdk" "$DL_DIR/dcloud-sdk-download.zip" "$DL_DIR/__MACOSX" 2>/dev/null || true
  [[ -e "$SDK_ROOT/SDK/libs/app-runtime-release.aar" ]] || die "SDK 解压异常: $SDK_ROOT"
  ok "SDK 已就位: $SDK_ROOT"
fi
# 符号链接指向真实SDK（gradle fileTree 需要真实路径，脚本内已做 canonical 处理）
ln -sfn "$SDK_ROOT" "$ANDROID_PROJ/dcloud-sdk"
[[ -x "$HBX_CLI" ]] || die "未找到 HBuilderX CLI: $HBX_CLI"
[[ -d "$ANDROID_SDK" ]] || die "找不到 Android SDK: $ANDROID_SDK"
export ANDROID_HOME="$ANDROID_SDK"
BUILD_TOOLS="$(ls -1d "$ANDROID_SDK"/build-tools/*/ 2>/dev/null | sed 's#/$##' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
[[ -n "$BUILD_TOOLS" ]] || die "Android SDK 缺少 build-tools"
APKSIGNER="$BUILD_TOOLS/apksigner"; AAPT="$BUILD_TOOLS/aapt"; ZIPALIGN="$BUILD_TOOLS/zipalign"

# JDK 探测：JAVA_HOME → gradle jdks → Android Studio JBR → HBuilderX corretto → PATH
resolve_java_home() {
  local candidates=() c major
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  [[ -d "$HOME/.gradle/jdks" ]] && while IFS= read -r c; do candidates+=("$c"); done \
    < <(find "$HOME/.gradle/jdks" -type f -path '*/bin/java' 2>/dev/null | sed 's#/bin/java$##' | sort -r)
  candidates+=("$JBR_JAVA" "/Applications/HBuilderX.app/Contents/HBuilderX/plugins/amazon-corretto")
  command -v java >/dev/null 2>&1 && candidates+=("$(cd "$(dirname "$(command -v java)")/.." && pwd)")
  for c in "${candidates[@]}"; do
    [[ -x "$c/bin/java" ]] || continue
    major="$("$c/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
    [[ "$major" =~ ^[0-9]+$ ]] || continue
    (( major >= 17 )) && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}
JAVA_HOME_RESOLVED="$(resolve_java_home || true)"
[[ -n "$JAVA_HOME_RESOLVED" ]] || die "找不到 JDK 17+（可设置 JAVA_HOME）"
export JAVA_HOME="$JAVA_HOME_RESOLVED"
[[ -f "$ANDROID_PROJ/local.properties" ]] || echo "sdk.dir=$ANDROID_SDK" > "$ANDROID_PROJ/local.properties"

LOG_DIR="$UNI_PROJECT/build/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/build-$(date '+%Y%m%d-%H%M%S').log"
: > "$LOG"

log "项目: $APP_NAME ($APPID)  版本: $VERSION_NAME($VERSION_CODE)  类型: $BUILD_TYPE"
log "JDK: $JAVA_HOME   SDK: $ANDROID_SDK   宿主: $ANDROID_PROJ   日志: $LOG"

RESOURCE_ROOT="$UNI_PROJECT/unpackage/resources/app-android"
RES_WWW="$RESOURCE_ROOT/$APPID/www"

# ---------- 1/4 编译 ----------
if [[ "$APK_ONLY" == "1" ]]; then
  log "[1/4] 跳过 uni 编译（--apk-only）"
  [[ -s "$RES_WWW/app-service.js" ]] || die "无历史资源: ${RES_WWW}，请先完整执行一次"
else
  log "[1/4] HBuilderX 生成 App 资源"
  marker="$LOG_DIR/.compile-start-$$"; : > "$marker"
  attempt_out=""
  for attempt in 1 2 3 4 5; do
    attempt_out="$LOG_DIR/publish-$(date '+%Y%m%d-%H%M%S')-$attempt.log"
    if ! pgrep -f "HBuilderX.app" >/dev/null 2>&1; then
      open -gj -a HBuilderX   # 隐藏拉起：不弹窗不抢焦点
      for i in $(seq 1 30); do pgrep -f "HBuilderX.app" >/dev/null 2>&1 && break; sleep 1; done
      sleep 5
    fi
    "$HBX_CLI" project open --path "$UNI_PROJECT" >/dev/null 2>&1 || true
    set +e
    "$HBX_CLI" publish app-android --type appResource --project "$UNI_PROJECT" 2>&1 |
      tr '\r' '\n' | tee -a "$LOG" "$attempt_out" >/dev/null
    status=${PIPESTATUS[0]}
    set -e
    if [[ $status -eq 0 ]] && grep -q '编译成功' "$attempt_out" && grep -q '导出 android 成功' "$attempt_out"; then
      if ! find "$RES_WWW" -type f -newer "$marker" -print -quit 2>/dev/null | grep -q .; then
        warn "发行资源文件时间未更新，请检查日志: $attempt_out"
      fi
      break
    fi
    if grep -q '未检测到已打开的HBuilderX' "$attempt_out"; then
      warn "HBuilderX 未就绪，重试（$attempt/5）…"; sleep 3; continue
    fi
    die "HBuilderX 编译失败（第 $attempt 次），日志: $attempt_out"
  done
  [[ -s "$RES_WWW/app-service.js" ]] || die "发行资源不存在或为空: $RES_WWW/app-service.js"
fi

# 资源 appid / 版本一致性
RES_APPID="$(read_json "$RES_WWW/manifest.json" id || true)"
[[ "$RES_APPID" == "$APPID" ]] || die "发行资源 appid 不一致: 资源=$RES_APPID 工程=$APPID"

# ---------- 2/4 同步宿主 ----------
log "[2/4] 同步资源到安卓工程"
ASSETS_WWW="$ANDROID_PROJ/app/src/main/assets/apps/$APPID/www"
mkdir -p "$ASSETS_WWW"
rsync -a --delete "$RES_WWW/" "$ASSETS_WWW/"
MANIFEST_XML="$ANDROID_PROJ/app/src/main/AndroidManifest.xml"
sed -i '' "s/__UNI__[A-Z0-9]*/$APPID/g" "$MANIFEST_XML"
rm -rf "${ASSETS_WWW%$APPID*}"/*/__UNI__* 2>/dev/null || true
find "$ANDROID_PROJ/app/src/main/assets/apps" -maxdepth 1 -mindepth 1 -type d ! -name "$APPID" -exec rm -rf {} + 2>/dev/null || true
# app 名同步 strings.xml
STRINGS_XML="$ANDROID_PROJ/app/src/main/res/values/strings.xml"
APP_NAME_ENV="$APP_NAME" perl -0pi -e 's#(<string name="app_name">)[^<]*(</string>)#$1$ENV{APP_NAME_ENV}$2#' "$STRINGS_XML" 2>/dev/null || true

# 模块依赖自检（告警不阻断）
MOD_AAR_MAP="VideoPlayer=uni-video-release.aar"
BUILTIN_MODULES="uni-list-view uni-form uni-getElementById uni-pullDownRefresh uni-rpx2px uni-dialogPage uni-event uni-crash uni-theme"
MODULES="$(plutil -extract app-android.distribute.modules json -o - "$RES_WWW/manifest.json" 2>/dev/null |
  grep -o '"[^"]*"[[:space:]]*:' | tr -d '": ' | sort -u || true)"
for m in $MODULES; do
  [[ " $BUILTIN_MODULES " == *" $m "* ]] && continue
  aar="${MOD_AAR_MAP#*$m=}"; [[ "$aar" == "$MOD_AAR_MAP" ]] && aar="${m}-release.aar"
  [[ -f "$SDK_ROOT/SDK/libs/$aar" ]] || warn "资源声明模块 ${m}，SDK 缺少 ${aar}（运行时异常时需补依赖）"
done
grep -q 'media3-exoplayer' "$ANDROID_PROJ/app/build.gradle" || warn "宿主缺少 media3/exoplayer 依赖，m3u8 播放可能崩溃"
grep -q 'uni-screenBrightness-release.aar' "$ANDROID_PROJ/app/build.gradle" || warn "宿主缺少 uni-screenBrightness 依赖，video 组件起播时会崩溃"
ok "资源/appid/app名/模块自检完成"

# ---------- 3/4 gradle 构建 ----------
log "[3/4] gradle 构建 $BUILD_TYPE"
GRADLE_TASK="assemble$(echo "$BUILD_TYPE" | tr '[:lower:]' '[:upper:]')"
(
  cd "$ANDROID_PROJ"
  tasks=("$GRADLE_TASK")
  [[ "$CLEAN" == "1" ]] && tasks=(clean "${tasks[@]}")
  ./gradlew "${tasks[@]}" --quiet -PappVersionCode="$VERSION_CODE" -PappVersionName="$VERSION_NAME" 2>&1 | tee -a "$LOG"
) || die "gradle 构建失败，日志: $LOG"

if [[ "$BUILD_TYPE" == "debug" ]]; then
  APK="$ANDROID_PROJ/app/build/outputs/apk/debug/app-debug.apk"
else
  APK="$ANDROID_PROJ/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ ! -f "$APK" ]]; then
    APK="$(find "$ANDROID_PROJ/app/build/outputs/apk/release" -maxdepth 1 -name '*.apk' ! -name '*signed*' 2>/dev/null | head -1)"
  fi
fi
[[ -n "$APK" && -f "$APK" ]] || die "未找到 gradle 产物 APK，日志: $LOG"

# release 瘦身：AAR 内的 dev/非 arm64 运行时快照无法用 gradle assets 过滤，打包后剔除
STRIP_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
  STRIP_TMP="${APK%.apk}-stripped.apk"
  cp "$APK" "$STRIP_TMP"
  if zip -q -d "$STRIP_TMP" \
      'assets/uni-app-x-framework-vapor-dev' \
      'assets/uni-app-x-framework-vapor-dev-arm.snapshot' \
      'assets/uni-app-x-framework-vapor-dev-arm64.snapshot' \
      'assets/uni-app-x-framework-vapor-dev-x86_64.snapshot' \
      'assets/uni-app-x-framework-vapor-arm.snapshot' \
      'assets/uni-app-x-framework-vapor-x86_64.snapshot' 2>>"$LOG"; then
    STRIP_APK="${APK%.apk}-stripped-aligned.apk"
    "$ZIPALIGN" -f 4 "$STRIP_TMP" "$STRIP_APK" >>"$LOG" 2>&1 || die "APK 瘦身后对齐失败"
    rm -f "$STRIP_TMP"
    APK="$STRIP_APK"
    log "已剔除 dev/非 arm64 运行时快照"
  else
    rm -f "$STRIP_TMP"
    warn "运行时快照剔除失败，保留原包"
  fi
fi

# ---------- 4/4 签名（release）与校验 ----------
if [[ "$BUILD_TYPE" == "release" ]]; then
  log "[4/4] apksigner 签名与校验"
  # 首次自动生成自签证书（可自行替换为正式证书）
  if [[ ! -f "$KEYSTORE_FILE" ]]; then
    warn "未找到签名证书，自动生成自签证书: ${KEYSTORE_FILE}（发布前可替换）"
    mkdir -p "$(dirname "$KEYSTORE_FILE")" "$(dirname "$KEYSTORE_PASSWORD_FILE")"
    PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom 2>/dev/null | head -c 20 || true)"
    "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE_FILE" -alias "$KEYSTORE_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10950 -storepass "$PASS" -keypass "$PASS" \
      -dname "CN=quanmin-movie, OU=dev, O=quanmin, C=CN" >/dev/null 2>&1 || die "生成 keystore 失败"
    printf '%s' "$PASS" > "$KEYSTORE_PASSWORD_FILE"; chmod 600 "$KEYSTORE_PASSWORD_FILE" "$KEYSTORE_FILE"
  fi
  [[ -f "$KEYSTORE_PASSWORD_FILE" ]] || die "缺少证书密码文件: $KEYSTORE_PASSWORD_FILE"
  SIGNED="${APK%.apk}-signed-tmp.apk"
  "$APKSIGNER" sign --ks "$KEYSTORE_FILE" --ks-key-alias "$KEYSTORE_ALIAS" \
    --ks-pass "file:$KEYSTORE_PASSWORD_FILE" --out "$SIGNED" "$APK" >>"$LOG" 2>&1 || die "apksigner 签名失败"
  VERIFY_OUT="$("$APKSIGNER" verify --print-certs "$SIGNED" 2>&1)" || die "APK 签名校验失败"
  [[ -x "$ZIPALIGN" ]] && ! "$ZIPALIGN" -c 4 "$SIGNED" >/dev/null 2>&1 && warn "APK 未通过 4 字节对齐检查"
  APK="$SIGNED"
fi

# dex 完整性门禁：SDK 运行时类必须存在，防止 fileTree 路径失效时产出"空壳APK"静默成功
# 注意：不能用 grep -q（提前退出会触发 unzip 的 SIGPIPE，在 pipefail 下误报），用 -c 读完整流
DCLOUD_CNT="$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -ac 'io/dcloud/uniappxv/UniApplication' || true)"
if [[ "$DCLOUD_CNT" -eq 0 ]]; then
  die "APK 缺少 uni-app x 运行时类！检查 native-android/dcloud-sdk 符号链接与 app/build.gradle 的 fileTree 路径"
fi

# 版本一致性校验
AAPT_OUT="$("$AAPT" dump badging "$APK" 2>/dev/null || true)"
[[ -n "$AAPT_OUT" ]] || die "aapt 无法读取 APK"
printf '%s\n' "$AAPT_OUT" | grep -q "versionCode='$VERSION_CODE'" || die "APK versionCode 与 manifest.json 不一致"
printf '%s\n' "$AAPT_OUT" | grep -q "versionName='$VERSION_NAME'" || die "APK versionName 与 manifest.json 不一致"

# ---------- 产物 ----------
OUTPUT="${OUTPUT:-$UNI_PROJECT/unpackage/apk/quanmin-movie-v$VERSION_NAME-$(date +%Y%m%d-%H%M)-$BUILD_TYPE.apk}"
mkdir -p "$(dirname "$OUTPUT")"
cp "$APK" "$OUTPUT"
[[ "${APK##*/}" == *"-signed-tmp.apk" ]] && rm -f "$APK"
[[ -z "$STRIP_APK" ]] || rm -f "$STRIP_APK"

SHA256="$(shasum -a 256 "$OUTPUT" | awk '{print $1}')"
SIZE="$(du -h "$OUTPUT" | cut -f1)"
CERT_SHA="$(printf '%s\n' "${VERIFY_OUT:-}" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
PACKAGE_LINE="$(printf '%s\n' "$AAPT_OUT" | grep -m1 '^package:')"
NATIVE_LINE="$(printf '%s\n' "$AAPT_OUT" | grep -m1 '^native-code:')"

printf '\n'
ok "打包完成"
printf '    产物: %s\n' "$OUTPUT"
printf '    大小: %s\n' "$SIZE"
printf '    SHA-256: %s\n' "$SHA256"
[[ -n "$CERT_SHA" ]] && printf '    证书 SHA-256: %s\n' "$CERT_SHA"
printf '    %s\n' "$PACKAGE_LINE"
[[ -n "$NATIVE_LINE" ]] && printf '    %s\n' "$NATIVE_LINE"
printf '    日志: %s\n' "$LOG"
printf '    安装: adb install -r %s\n' "$OUTPUT"
