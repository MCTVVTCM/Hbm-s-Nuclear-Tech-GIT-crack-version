#!/usr/bin/env bash
# 存档备份。
#
#   tools/backup.sh [目标目录] [世界名]
#
# 例：
#   tools/backup.sh                        # 备份全部世界到 ~/mc-backups/worlds-<时间戳>
#   tools/backup.sh ~/mc-backups "Copy of 新的世界"
#
# 为什么必须"先完全退出游戏"：
#   保存过程中 .mca 的扇区是边写边更新的，这时候复制可能拿到"写了一半"的文件，
#   备份出来的档本身就是坏的 —— 比不备份更危险。
#
# 脚本会检测游戏进程，正在运行就直接拒绝。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SAVES="$ROOT/eclipse/saves"
DEST_BASE="${1:-$HOME/mc-backups}"
WORLD="${2:-}"
KEEP=10

if [[ ! -d "$SAVES" ]]; then
    echo "找不到存档目录: $SAVES" >&2
    exit 1
fi

# 游戏还在跑就别备份
RUNNING="$(pgrep -f 'GradleStart' | tr '\n' ' ' || true)"
if [[ -n "${RUNNING// /}" ]]; then
    echo "游戏还在运行（PID: $RUNNING），先完全退出（不是回主菜单）再备份。" >&2
    exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST_BASE"

if [[ -n "$WORLD" ]]; then
    SRC="$SAVES/$WORLD"
    [[ -d "$SRC" ]] || { echo "找不到世界: $SRC" >&2; exit 1; }
    DEST="$DEST_BASE/$(echo "$WORLD" | tr ' /' '__')-$STAMP"
    cp -a --reflink=auto "$SRC" "$DEST"
else
    DEST="$DEST_BASE/worlds-$STAMP"
    cp -a --reflink=auto "$SAVES" "$DEST"
fi

SIZE="$(du -sh "$DEST" | cut -f1)"
echo "已备份 → $DEST  ($SIZE)"

# 只保留最近 KEEP 份
if [[ -n "$WORLD" ]]; then
    PATTERN="$DEST_BASE/$(echo "$WORLD" | tr ' /' '__')-*"
else
    PATTERN="$DEST_BASE/worlds-*"
fi

mapfile -t OLD < <(ls -1dt $PATTERN 2>/dev/null | tail -n +$((KEEP + 1)) || true)
if [[ ${#OLD[@]} -gt 0 ]]; then
    echo "清理旧备份 ${#OLD[@]} 份："
    printf '  %s\n' "${OLD[@]}"
    rm -rf -- "${OLD[@]}"
fi

echo "当前备份："
ls -1dt $PATTERN 2>/dev/null | head -n 5 | sed 's/^/  /'
