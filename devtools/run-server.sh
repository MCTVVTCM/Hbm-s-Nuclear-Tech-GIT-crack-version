#!/usr/bin/env bash
# 启动开发服务端。默认离线；用法同 run-client.sh（支持 --online）。
set -euo pipefail
cd "$(dirname "$0")/.."

OFFLINE="--offline"
ARGS=()
for a in "$@"; do
    if [ "$a" = "--online" ]; then
        OFFLINE=""
    else
        ARGS+=("$a")
    fi
done

exec ./gradlew runServer $OFFLINE -I devtools/hotreload.gradle "${ARGS[@]+"${ARGS[@]}"}"
