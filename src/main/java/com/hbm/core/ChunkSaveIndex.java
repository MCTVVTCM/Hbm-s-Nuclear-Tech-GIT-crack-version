package com.hbm.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * 保存区块时的待处理方块更新查询加速。
 *
 * 原版 {@code WorldServer.getPendingBlockUpdates(chunk, false)} 会把**整个**待处理更新集合
 * （TreeSet，可能几十万条）完整遍历一遍，只为了挑出这个区块范围内的条目。
 * 而 {@code AnvilChunkLoader.writeChunkToNBT} 对**每个**区块都调一次，于是保存一个世界是
 * O(区块数 × 待处理更新数) —— 实测退出游戏时卡在保存界面好几分钟。
 *
 * 这里改成：每次保存会话（同一 tick 内）先扫一遍集合，按区块坐标分桶，
 * 之后每个区块只查自己 + 周围 8 个桶并按原版边界过滤，复杂度变成 O(更新数 + 区块数)。
 *
 * 正确性：
 *   - 返回的条目集合与原版完全一致（同样的坐标范围 [chunkX*16-2, +18) 过滤，并按同样的
 *     NextTickListEntry 顺序排序），所以写进 NBT 的 scheduled ticks 不变；
 *   - 参数 remove=true 时（真正要“取走”条目的场合）直接走原版，不做缓存；
 *   - 只改“怎么找”，不改“找到什么”。
 */
public final class ChunkSaveIndex {

	private ChunkSaveIndex() { }

	/** 每个世界一份索引，按 totalWorldTime 失效 */
	private static final Map<World, Snapshot> CACHE = new HashMap<World, Snapshot>();

	private static Field treeSetField;
	private static boolean fieldLookupDone;

	private static final class Snapshot {
		final long tick;
		final Map<Long, List<NextTickListEntry>> buckets;

		Snapshot(long tick, Map<Long, List<NextTickListEntry>> buckets) {
			this.tick = tick;
			this.buckets = buckets;
		}
	}

	/** 由 ASM 把 writeChunkToNBT 里的 getPendingBlockUpdates 调用重定向到这里 */
	@SuppressWarnings("rawtypes")
	public static List get(World world, Chunk chunk, boolean remove) {

		// 真正要移除条目的路径（原版的 flag=true）不做缓存，保持原语义
		if(remove || world == null || chunk == null) {
			return world == null ? null : world.getPendingBlockUpdates(chunk, remove);
		}

		try {
			Snapshot snapshot = snapshotFor(world);
			if(snapshot == null) return world.getPendingBlockUpdates(chunk, remove);

			int cx = chunk.xPosition;
			int cz = chunk.zPosition;

			// 原版的边界：(chunkX << 4) - 2 到 +18
			int minX = (cx << 4) - 2;
			int maxX = minX + 18;
			int minZ = (cz << 4) - 2;
			int maxZ = minZ + 18;

			List<NextTickListEntry> out = null;

			for(int dx = -1; dx <= 1; dx++) {
				for(int dz = -1; dz <= 1; dz++) {
					List<NextTickListEntry> bucket = snapshot.buckets.get(key(cx + dx, cz + dz));
					if(bucket == null) continue;

					for(NextTickListEntry entry : bucket) {
						if(entry.xCoord < minX || entry.xCoord >= maxX) continue;
						if(entry.zCoord < minZ || entry.zCoord >= maxZ) continue;
						if(out == null) out = new ArrayList<NextTickListEntry>();
						out.add(entry);
					}
				}
			}

			if(out == null) return null;

			// 原版遍历的是 TreeSet，顺序由 NextTickListEntry 的 compareTo 决定；
			// 这里排一次，保证写进 NBT 的顺序与原版一致。
			Collections.sort(out);

			return out;

		} catch(Throwable t) {
			// 任何异常都退回原版，绝不让保存失败
			return world.getPendingBlockUpdates(chunk, remove);
		}
	}

	@SuppressWarnings("unchecked")
	private static Snapshot snapshotFor(World world) throws Exception {

		long tick = world.getTotalWorldTime();
		Snapshot cached = CACHE.get(world);
		if(cached != null && cached.tick == tick) return cached;

		if(!(world instanceof WorldServer)) return null;

		Field field = findTreeSetField();
		if(field == null) return null;

		Object value = field.get(world);
		if(!(value instanceof Iterable)) return null;

		Map<Long, List<NextTickListEntry>> buckets = new HashMap<Long, List<NextTickListEntry>>();

		for(Object o : (Iterable<Object>) value) {
			if(!(o instanceof NextTickListEntry)) continue;
			NextTickListEntry entry = (NextTickListEntry) o;
			long k = key(entry.xCoord >> 4, entry.zCoord >> 4);

			List<NextTickListEntry> bucket = buckets.get(k);
			if(bucket == null) {
				bucket = new ArrayList<NextTickListEntry>();
				buckets.put(k, bucket);
			}
			bucket.add(entry);
		}

		Snapshot snapshot = new Snapshot(tick, buckets);
		CACHE.put(world, snapshot);
		return snapshot;
	}

	/** 找到 WorldServer 里那个 TreeSet 类型的待处理更新集合（按类型找，避免混淆名问题） */
	private static Field findTreeSetField() {

		if(fieldLookupDone) return treeSetField;
		fieldLookupDone = true;

		for(Field f : WorldServer.class.getDeclaredFields()) {
			if(java.util.TreeSet.class.isAssignableFrom(f.getType())
					|| java.util.Set.class.isAssignableFrom(f.getType()) && f.getName().contains("pending")) {
				f.setAccessible(true);
				treeSetField = f;
				break;
			}
		}
		return treeSetField;
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}
}
