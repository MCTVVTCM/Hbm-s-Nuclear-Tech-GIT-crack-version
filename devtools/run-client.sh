#!/usr/bin/env bash
# 启动开发客户端。
#   ./devtools/run-client.sh                  # 默认离线启动（依赖已缓存，秒开）
#   ./devtools/run-client.sh -Pxmx=6G         # 加大内存
#   ./devtools/run-client.sh -PdebugPort=5005 # 附带 JDWP，可被 IDEA Attach 后热替换
#   ./devtools/run-client.sh --online         # 需要联网（新增依赖 / 首次下载）时才用
#
# 默认加 --offline：ForgeGradle 的旧仓库在高延迟网络下会让「配置阶段」卡住，
# 依赖已经全部缓存，离线启动既快又不会挂。
set -euo pipefail
cd "$(dirname "$0")/.."

# ForgeGradle 1.2 / Gradle 4.6 只认 Java 8；系统默认 java 是 21 时直接报
# "Could not determine java version from ..."，这里兜个底。
: "${JAVA_HOME:=/usr/lib/jvm/java-8-openjdk}"
export JAVA_HOME

OFFLINE="--offline"
ARGS=()
for a in "$@"; do
    if [ "$a" = "--online" ]; then
        OFFLINE=""
    else
        ARGS+=("$a")
    fi
done

exec ./gradlew runClient $OFFLINE "${ARGS[@]+"${ARGS[@]}"}"
