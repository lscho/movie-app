#!/usr/bin/env bash
# ============================================================
# DLNA 核心逻辑离线验证（不需要 Android 设备 / 模拟器）
#
# 直接编译 native-dlna 模块里的**生产代码**（不含 Android 依赖的几个文件），
# 验证：设备描述 XML 解析、controlURL 相对路径拼接、SOAP 标签与错误码提取、
#       JSON 序列化转义、时间格式往返、DIDL-Lite 元数据、投屏 URL 校验。
#
# 用法: ./tools/dlna-verify/run.sh
# 依赖: Android Studio 自带的 kotlinc 与 JDK（与 App 编译环境一致）
# ============================================================
set -Eeuo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
SRC="$ROOT/native-android/native-dlna/src/main/java/com/quanmin/dlnacast"

KC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JBR}"
export PATH="$JAVA_HOME/bin:$PATH"

[[ -x "$KC" ]] || { echo "未找到 kotlinc: $KC（需要安装 Android Studio）" >&2; exit 1; }
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "未找到 JDK: $JAVA_HOME" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 准备（复制被测生产代码，跳过依赖 Android 的 DlnaCast / DlnaCastApi / SsdpDiscovery）"
cp "$SRC/DlnaModels.kt" "$SRC/SoapClient.kt" "$SRC/Json.kt" "$SRC/DlnaHelpers.kt" "$WORK/"
cp "$DIR/Main.kt" "$WORK/"

KSTD="$(find "$HOME/.gradle" -name 'kotlin-stdlib-2*.jar' 2>/dev/null | head -1)"
[[ -n "$KSTD" ]] || { echo "未找到 kotlin-stdlib（先执行一次 gradle 构建）" >&2; exit 1; }

echo "==> 编译"
"$KC" -nowarn -d "$WORK/out.jar" \
  "$WORK/DlnaModels.kt" "$WORK/SoapClient.kt" "$WORK/Json.kt" \
  "$WORK/DlnaHelpers.kt" "$WORK/Main.kt" 2>&1 | grep -vE '^warning:' || true
[[ -f "$WORK/out.jar" ]] || { echo "编译失败" >&2; exit 1; }

echo "==> 运行"
DLNA_FIXTURE="$DIR/Sample.xml" "$JAVA_HOME/bin/java" -cp "$WORK/out.jar:$KSTD" MainKt
