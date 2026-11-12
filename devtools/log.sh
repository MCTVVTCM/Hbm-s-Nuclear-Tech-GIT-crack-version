#!/usr/bin/env bash
# 实时看游戏日志（客户端 + 集成服务端都写在这一个文件里）。
#
#   ./devtools/log.sh             # 全部
#   ./devtools/log.sh server      # 只看服务端线程
#   ./devtools/log.sh err         # 只看 WARN / ERROR / 异常栈
#   ./devtools/log.sh hbm         # 只看包含某个关键字的行（这里示例是 hbm）
#   LOG_FILE=... ./devtools/log.sh server   # 指定别的日志文件
#
# 说明：1.7.10 单人游戏里「服务端」就是内置服务端，没有独立控制台，
# 它的输出和客户端混在 eclipse/logs/latest.log 里，只是线程名不同：
#   [21:39:17] [Client thread/INFO]: ...
#   [21:39:17] [Server thread/INFO]: ...
set -uo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-all}"
LOG_FILE="${LOG_FILE:-eclipse/logs/latest.log}"

if [ ! -f "$LOG_FILE" ]; then
    echo "日志还不存在: $LOG_FILE"
    echo "先把游戏跑起来：./devtools/run-client.sh -PdebugPort=5005"
    exit 1
fi

case "$MODE" in
    all)
        exec tail -F -n 50 "$LOG_FILE"
        ;;
    server)
        exec tail -F -n 200 "$LOG_FILE" | grep --line-buffered -E '\[Server|Server thread|FMLServerHandler'
        ;;
    err)
        exec tail -F -n 300 "$LOG_FILE" | grep --line-buffered -E 'ERROR|WARN|Exception|^\s+at com\.hbm'
        ;;
    *)
        exec tail -F -n 100 "$LOG_FILE" | grep --line-buffered -i -- "$MODE"
        ;;
esac
