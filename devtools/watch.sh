#!/usr/bin/env bash
# 持续增量编译（Gradle continuous build）。
#   用法： ./devtools/watch.sh        （Ctrl+C 停止）
#
# ⚠️ 它会一直停在 "Waiting for changes"，并占着工程的 Gradle 锁：
#    这期间 IDEA 同步 / 其它 gradlew 命令都会排队，看起来像卡死。
#    日常改代码用 IDEA 的 Ctrl+F9 就够了，别一边开 watch 一边让 IDEA 同步。
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew -t --offline -I devtools/hotreload.gradle classes processResources "$@"
