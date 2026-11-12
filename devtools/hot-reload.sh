#!/usr/bin/env bash
# 自动热重载：保存源码 → 增量编译 → 把「内容真的变了」的 class 重定义进正在运行的游戏。
# 不依赖 IDEA 的 Debug / 增量编译，改常量也能立刻验证。
#
# 用法：
#   终端 1:  ./devtools/run-client.sh -PdebugPort=5005
#   终端 2:  ./devtools/hot-reload.sh
#   然后正常改 .java，保存即可。Ctrl+C 退出监听。
#
# 输出含义：
#   OK   com.hbm.xxx.Foo   —— 已经替换进游戏，立刻生效
#   PEND com.hbm.xxx.Bar   —— 游戏里还没加载这个类。**不用替换**：磁盘上的 .class 就是它
#                             第一次被加载时读到的东西，改动已经在里面了。
#                             · 懒加载的类（渲染器 / 工具类 / 某个 GUI 才用到的类）第一次用到时自然是新版本；
#                             · 只有 init 期注册才加载的类（新实体 / 方块 / 物品 / 配方 / 渲染注册）
#                               才必须重启 —— 那时注册那一步早就跑完了，注册表里根本没它。
#   FAIL ...               —— 被 JVM 拒绝（构造器/继承关系等结构性改动，必须重启）
#
# 注意：这里按 **文件内容哈希** 做 diff，不是按编译时间。
# Gradle 增编经常会把一大片「内容其实没变」的类重新写一遍，
# 全推给游戏会让它瞬间卡成 PPT（实测掉到几十秒一帧）。
set -uo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-5005}"
HOST="127.0.0.1"
CLASSES="build/classes/java/main"
TOOLDIR="build/hotswap-tool"
JDK8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"

# 工具源码比 class 新（或还没编译）时重新编译
if [ ! -f "$TOOLDIR/HotSwapBatch.class" ] || [ devtools/hotswap/HotSwapBatch.java -nt "$TOOLDIR/HotSwapBatch.class" ]; then
    mkdir -p "$TOOLDIR"
    echo "[hot-reload] 编译热替换工具…"
    "$JDK8/bin/javac" -nowarn -cp "$JDK8/lib/tools.jar" -d "$TOOLDIR" devtools/hotswap/HotSwapBatch.java || exit 1
fi

# 一次 JVM 操作提交几个类（一块一个 safepoint），以及两块之间的间隔
export HOT_SWAP_CHUNK="${HOT_SWAP_CHUNK:-25}"
export HOT_SWAP_DELAY_MS="${HOT_SWAP_DELAY_MS:-100}"
# 一次改到多少类就判定「热替换不划算」，直接跳过、建议重启
HOT_MAX_PUSH="${HOT_MAX_PUSH:-300}"

# 输出：<md5>  ./相对路径.class
snapshot() {
    ( cd "$CLASSES" && find . -name '*.class' -print0 | xargs -0 md5sum ) 2>/dev/null | sort > "$1"
}

# 只看真正的源文件：忽略 Kate/Vim/Emacs 之类的交换与备份文件，
# 否则编辑时 .xxx.java.kate-swp 一直在变，监听会被反复触发。
src_changes() {
    find src/main -type f \
        \( -name '*.java' -o -path 'src/main/resources/*' \) \
        ! -name '*.kate-swp' ! -name '*.swp' ! -name '*.swo' \
        ! -name '*~' ! -name '*.bak' ! -name '*.tmp' ! -name '.#*' \
        ! -name '.*' \
        -newer "$MARKER" -print -quit 2>/dev/null
}

echo "[hot-reload] 首次编译…"
./gradlew --offline -q classes processResources >/dev/null 2>&1 || echo "[hot-reload] 首次编译失败（先修编译错误）"

snapshot build/.hot-snap
MARKER="build/.hot-marker"
touch "$MARKER"

echo "[hot-reload] 监听 src/main …… 目标游戏 ${HOST}:${PORT}"
while true; do
    sleep 1
    [ -z "$(src_changes)" ] && continue

    echo "[hot-reload] 检测到改动，编译中…"
    if ! ./gradlew --offline -q classes processResources >build/.hot-build.log 2>&1; then
        echo "[hot-reload] ✗ 编译失败："
        grep -E 'error:|错误:' build/.hot-build.log | head -5
        touch "$MARKER"
        snapshot build/.hot-snap
        continue
    fi
    touch "$MARKER"

    snapshot build/.hot-snap.new
    CHANGED=$(comm -13 build/.hot-snap build/.hot-snap.new | sed 's/^[0-9a-f]\{32\}  //')
    mv build/.hot-snap.new build/.hot-snap

    if [ -z "$CHANGED" ]; then
        echo "[hot-reload] 编译完成，class 内容没有变化（只改了资源？资源请在游戏里按 F3+T）"
        continue
    fi

    COUNT=$(printf '%s\n' "$CHANGED" | wc -l)

    # 波及面太广：热替换只会把游戏搞卡，不如重启
    if [ "$COUNT" -gt "$HOT_MAX_PUSH" ]; then
        echo "[hot-reload] ✗ 本次有 ${COUNT} 个类的字节码变了（阈值 ${HOT_MAX_PUSH}），已跳过推送"
        echo "[hot-reload]   受影响最多的包："
        printf '%s\n' "$CHANGED" | sed 's|^\./||' \
            | awk -F/ 'NF>=3 {print $1"/"$2"/"$3} NF<3 {print $1}' \
            | sort | uniq -c | sort -rn | head -6 | sed 's/^/[hot-reload]     /'
        echo "[hot-reload]   通常是改到了被大量内联的常量，或公共接口/父类。建议直接重启客户端。"
        echo "[hot-reload]   确实想硬推：HOT_MAX_PUSH=99999 ./devtools/hot-reload.sh"
        continue
    fi

    echo "[hot-reload] 有 ${COUNT} 个类内容变了，分块替换（每批 ${HOT_SWAP_CHUNK} 个，批间隔 ${HOT_SWAP_DELAY_MS}ms）"
    if [ "$COUNT" -gt 50 ]; then
        echo "[hot-reload] ⚠ 一次改到 ${COUNT} 个类，推送会比较慢，期间游戏可能掉帧"
    fi

    FILES=()
    while IFS= read -r rel; do
        FILES+=("$CLASSES/${rel#./}")
    done <<< "$CHANGED"

    "$JDK8/bin/java" -cp "$TOOLDIR:$JDK8/lib/tools.jar" HotSwapBatch \
        "$HOST" "$PORT" "$CLASSES" "${FILES[@]}" 2>&1 \
        | sed 's/^/[hot-reload] /' \
        || echo "[hot-reload] ✗ 连不上游戏（${PORT}），确认游戏是用 -PdebugPort=${PORT} 启动的"
done
