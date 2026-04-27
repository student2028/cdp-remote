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
# Android Gradle Plugin 需要 JDK 17+；环境若仍是 11，java_home 自动切到 17/21
_java_ok=0
if [[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/java" ]]; then
  if "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])'; then
    _java_ok=1
  fi
fi
if [[ "$_java_ok" -eq 0 ]] && [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  export JAVA_HOME
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

restart_relay_dev() {
  local label="com.cdp.remote.relay-dev"
  local uid
  uid="$(id -u)"

  if ! command -v launchctl >/dev/null 2>&1; then
    echo "提示：未找到 launchctl，跳过 relay 自动重启。"
    return 0
  fi

  # 清掉手动启动的旧 relay。否则它会继续占用 19336，LaunchAgent 的新版 relay 无法接管。
  if command -v lsof >/dev/null 2>&1; then
    local pids pid cmd
    pids="$(lsof -tiTCP:19336 -sTCP:LISTEN 2>/dev/null || true)"
    for pid in $pids; do
      cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
      if [[ "$cmd" == *"cdp_relay.js"* ]]; then
        echo "重启 relay：停止旧 cdp_relay.js PID $pid"
        kill "$pid" 2>/dev/null || true
      fi
    done
    sleep 1
  fi

  if launchctl print "gui/${uid}/${label}" >/dev/null 2>&1; then
    launchctl kickstart -k "gui/${uid}/${label}" >/dev/null 2>&1 || true
    echo "已重启 relay LaunchAgent：${label}"
  else
    echo "提示：未加载 ${label}，请手动启动 relay-dev/cdp_relay.js。"
  fi
}

restart_relay_dev

echo "下一步：手机退出聊天页重新进入，或在主机列表刷新，即可收到 OTA。"
