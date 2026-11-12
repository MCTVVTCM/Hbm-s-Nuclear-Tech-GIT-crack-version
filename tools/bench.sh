#!/usr/bin/env bash
# 全自动区块生成基准测试。
#
#   tools/bench.sh <tag> [chunks] [offset] [world]
#
# 例：
#   tools/bench.sh before 100 5000            # 改代码之前跑一次，结果记为基线
#   tools/bench.sh after  100 5000            # 改完之后再跑，自动给出 WORLD-IDENTICAL / WORLD-CHANGED
#   tools/bench.sh after  100 5000 "Copy of 新的世界"   # 指定存档目录名
#
# 它会：
#   1. 启动客户端（DCEVM + CMS）
#   2. 自动进入存档、等世界稳定
#   3. 在从未生成过的区域串行生成 N 个区块，计时 + 计算方块哈希
#   4. 写入 eclipse/bench-results.csv 后自动退出
#
# 判定：
#   hash 相同  + avg 降 >=15%  → 有效且没改世界
#   hash 不同                  → 世界被改了，不合格

set -euo pipefail

TAG="${1:-run}"
COUNT="${2:-100}"
OFFSET="${3:-5000}"
WORLD="${4:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk}"

CSV="eclipse/bench-results.csv"
BEFORE_LINES=0
if [[ -f "$CSV" ]]; then
    BEFORE_LINES=$(wc -l < "$CSV")
fi

echo "[bench] tag=$TAG chunks=$COUNT offset=$OFFSET"
echo "[bench] 日志: /tmp/bench-$TAG.log"

set +e
./gradlew runClient --offline \
    -P"hbm.prof.tag=$TAG" \
    -P"hbm.bench.auto=$COUNT" \
    -P"hbm.bench.offset=$OFFSET" \
    ${WORLD:+-P"hbm.bench.world=$WORLD"} \
    2>&1 | tee "/tmp/bench-$TAG.log"
set -e

echo
echo "================ 结果 ================"
if [[ ! -f "$CSV" ]]; then
    echo "没有生成 $CSV，看看日志里有没有 [bench-auto] 的报错"
    exit 1
fi

AFTER_LINES=$(wc -l < "$CSV")
if [[ "$AFTER_LINES" -le "$BEFORE_LINES" ]]; then
    echo "本次没有新增结果（示例：世界没进去 / 区块已存在），日志：/tmp/bench-$TAG.log"
    tail -n 3 "$CSV"
    exit 1
fi

grep -h "\[bench\]" "/tmp/bench-$TAG.log" | tail -1 || true

echo
echo "---- 最近记录 ----"
printf '%-10s %-6s %-8s %-9s %-9s %-9s %-18s\n' TAG N OFFSET AVG_ms P95_ms MAX_ms HASH
tail -n 5 "$CSV" | grep -v '^#' | awk -F',' '{
    printf "%-10s %-6s %-8s %-9s %-9s %-9s %-18s\n", $9, $2, $3, $5, $6, $7, $8
}'
