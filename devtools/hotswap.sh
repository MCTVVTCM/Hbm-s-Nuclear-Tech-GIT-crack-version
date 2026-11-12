#!/usr/bin/env bash
# 把 build/classes 里编译好的 class 直接塞进正在运行的游戏（需要 DCEVM + -PdebugPort=5005）。
#
#   devtools/hotswap.sh                     # 换默认那几个筋斗云相关的类
#   devtools/hotswap.sh com.hbm.Foo ...     # 只换指定类
#   devtools/hotswap.sh --set com.hbm.entity.misc.EntityJindouYun.SPEED=32
#
# 注意：热替换不会重跑静态初始化器。改了 static 字段的默认值必须用 --set，
#       否则运行中的类还是旧值（新加的 static 字段会是 0/false/null）。

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-5005}"
JH="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk}"
OUT="$ROOT/devtools/hotswap/classes"
SRC="$ROOT/devtools/hotswap/HotSwap.java"

mkdir -p "$OUT"

if [ ! -f "$OUT/HotSwap.class" ] || [ "$SRC" -nt "$OUT/HotSwap.class" ]; then
    "$JH/bin/javac" -cp "$JH/lib/tools.jar" -d "$OUT" "$SRC" || exit 1
fi

DEFAULT_CLASSES=(
    com.hbm.entity.misc.EntityJindouYun
    com.hbm.render.HudChunkStats
    com.hbm.util.ChunkStats
    com.hbm.world.ChunkPathPreloader
    com.hbm.commands.CommandChunkStats
)

if [ "$#" -eq 0 ]; then
    set -- "${DEFAULT_CLASSES[@]}"
fi

exec "$JH/bin/java" \
    -Dclasses="$ROOT/build/classes/java/main/" \
    -cp "$OUT:$JH/lib/tools.jar" HotSwap "$PORT" "$@"
