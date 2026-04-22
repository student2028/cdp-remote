#!/usr/bin/env bash
# 改完代码后只跑这一条：自动递增 versionCode → 打 APK → 生成 build/ota/version.json → 中继 /version 就能感知 → 手机点更新即可。
# 用法：./scripts/ota/publish_ota.sh          （默认打 debug）
#      ./scripts/ota/publish_ota.sh release  （只打 release）

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT"

COUNTER_FILE="${ROOT}/.ota_build_counter"
BASE=100000
PREV="$(cat "$COUNTER_FILE" 2>/dev/null || echo $((BASE - 1)))"
if ! [[ "$PREV" =~ ^[0-9]+$ ]]; then PREV=$((BASE - 1)); fi
N=$((PREV + 1))
if [[ "$N" -le "$BASE" ]]; then N=$((BASE + 1)); fi
echo "$N" > "$COUNTER_FILE"

export JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    export JAVA_HOME
  fi
fi

MODE="${1:-debug}"
GRADLE=(./gradlew -p "$ROOT" -PotaVersionCode="$N" -PotaVersionName="ota-$N")

if [[ "$MODE" == "release" ]]; then
  "${GRADLE[@]}" :app:assembleRelease
  echo ""
  echo "OK: versionCode=$N （已写入 .ota_build_counter）。APK: app/build/outputs/apk/release/CdpRemote-release.apk"
else
  "${GRADLE[@]}" :app:assembleDebug
  echo ""
  echo "OK: versionCode=$N （已写入 .ota_build_counter）。APK: app/build/outputs/apk/debug/CdpRemote-debug.apk"
fi

echo "下一步：启动 node relay/cdp_relay.js，手机连同一中继即可收到 OTA。"
