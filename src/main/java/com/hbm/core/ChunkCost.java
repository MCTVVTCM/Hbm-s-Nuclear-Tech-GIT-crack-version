package com.hbm.core;

import java.io.IOException;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.chunk.storage.IChunkLoader;

/**
 * 区块成本分账：把"加载一个区块"拆成 读盘 和 光照初始化 两段分别计时。
 *
 * 目的：现在的尖峰是"一次加载 100 个区块"造成的，但不知道钱花在读盘、光照还是打包上。
 * 先把前两项量出来（打包那项已经通过 ChunkCompress 把压缩级别降到 1，约 1.5ms/块，不是重点），
 * 再决定要不要做"光照引擎现代化"这种大改。
 *
 * 通过 ASM 重定向两个调用点实现：
 *   - Chunk 里对 func_150809_p() 的调用        → {@link #light(Chunk)}
 *   - ChunkProviderServer 里 currentChunkLoader.loadChunk(...) → {@link #disk}
 *
 * 只做计时，不改任何逻辑；关掉（-Dhbm.chunkcost=false）后连 nanoTime 都不做。
 */
public class ChunkCost {

	public static boolean ENABLED = Boolean.getBoolean("hbm.chunkcost") || System.getProperty("hbm.chunkcost") == null;

	/**
	 * 每 tick 最多卸载（并因此保存）多少个区块。原版写死 100。
	 *
	 * 实测一次保存 5.86ms（NBT 序列化），100 个就是 586ms/tick —— 飞行时的秒级尖峰就是它。
	 * 实测教训：限制卸载会导致区块积压（8 → chunks 6714，24 → chunks 10114、tick 44 秒），
	 * 因为加载速度不由我们控制，卸载一慢就正反馈。
	 * 现在保存成本已经由 ChunkSaveIndex 的分桶索引承担，所以默认恢复原版的 100。
	 * 由 ASM 把 unloadQueuedChunks() 里的常量 100 换成这个字段。
	 */
	public static int UNLOAD_PER_TICK = Integer.getInteger("hbm.unload.budget", 100);

	private static long diskNanos;
	private static long lightNanos;
	private static int diskCount;
	private static int lightCount;
	private static long lightMax;
	private static long lightDoneNanos;
	private static int lightDoneCount;
	private static long diskMax;
	private static long unloadNanos;
	private static int unloadCount;
	private static long unloadMax;
	private static long aiScanNanos;
	private static int aiScanCount;
	private static long aiScanMax;
	private static long saveNanos;
	private static int saveCount;
	private static long saveMax;
	private static long lastReport = System.currentTimeMillis();

	/** 读盘（AnvilChunkLoader.loadChunk 等），由 ChunkProviderServer 的调用点重定向过来 */
	public static Chunk disk(IChunkLoader loader, World world, int x, int z) throws IOException {

		if(!ENABLED) return loader.loadChunk(world, x, z);

		long t0 = System.nanoTime();
		try {
			return loader.loadChunk(world, x, z);
		} finally {
			long dt = System.nanoTime() - t0;
			diskNanos += dt;
			diskCount++;
			if(dt > diskMax) diskMax = dt;
			maybeReport();
		}
	}

	/** 光照初始化（func_150809_p），由 Chunk 内部的调用点重定向过来 */
	public static void light(Chunk chunk) {

		if(!ENABLED || chunk == null) {
			if(chunk != null) chunk.func_150809_p();
			return;
		}

		long t0 = System.nanoTime();
		try {
			chunk.func_150809_p();
		} finally {
			long dt = System.nanoTime() - t0;
			lightNanos += dt;
			lightCount++;
			if(dt > lightMax) lightMax = dt;

			// 这一列/这一块是否真的填完了：填充成功的那些才是真开销，失败的是廉价重试
			if(chunk.isLightPopulated) {
				lightDoneNanos += dt;
				lightDoneCount++;
			}

			maybeReport();
		}
	}

	/**
	 * AI 里的邻居查询计时（World.getEntitiesWithinAABB）。
	 * 2400 只动物每 tick 各扫一次 8 格半径，怀疑是 goalSelector.canUse 的大头。
	 * 只看不改。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static java.util.List aiScan(net.minecraft.world.World world, Class cls,
			net.minecraft.util.AxisAlignedBB box) {

		if(!ENABLED) return world.getEntitiesWithinAABB(cls, box);

		long t0 = System.nanoTime();
		try {
			return world.getEntitiesWithinAABB(cls, box);
		} finally {
			long dt = System.nanoTime() - t0;
			aiScanNanos += dt;
			aiScanCount++;
			if(dt > aiScanMax) aiScanMax = dt;
			maybeReport();
		}
	}

	/** ChunkProviderServer.func_73156_b()：卸载队列，一次最多 100 个区块，每个都要序列化写盘 */
	public static boolean unload(net.minecraft.world.gen.ChunkProviderServer provider) {

		if(!ENABLED) return provider.unloadQueuedChunks();

		long t0 = System.nanoTime();
		try {
			return provider.unloadQueuedChunks();
		} finally {
			long dt = System.nanoTime() - t0;
			unloadNanos += dt;
			unloadCount++;
			if(dt > unloadMax) unloadMax = dt;
			maybeReport();
		}
	}

	/** IChunkLoader.saveChunk(World, Chunk)：真正写 region 文件的地方 */
	public static void save(IChunkLoader loader, World world, Chunk chunk) throws IOException, MinecraftException {

		if(!ENABLED) {
			loader.saveChunk(world, chunk);
			return;
		}

		long t0 = System.nanoTime();
		try {
			loader.saveChunk(world, chunk);
		} finally {
			long dt = System.nanoTime() - t0;
			saveNanos += dt;
			saveCount++;
			if(dt > saveMax) saveMax = dt;
			maybeReport();
		}
	}

	private static void maybeReport() {

		long now = System.currentTimeMillis();
		if(now - lastReport < 10000L) return;
		lastReport = now;

		if(diskCount == 0 && lightCount == 0) return;

		String line = String.format(java.util.Locale.ROOT,
				"[hbm-chunkcost] disk n=%d avg=%.2fms max=%.2fms | light n=%d avg=%.2fms max=%.2fms done=%d(avg=%.2fms)",
				diskCount, diskCount == 0 ? 0 : diskNanos / 1e6 / diskCount, diskMax / 1e6,
				lightCount, lightCount == 0 ? 0 : lightNanos / 1e6 / lightCount, lightMax / 1e6,
				lightDoneCount, lightDoneCount == 0 ? 0 : lightDoneNanos / 1e6 / lightDoneCount);

		if(unloadCount > 0) {
			line += String.format(java.util.Locale.ROOT, " | unload n=%d avg=%.2fms max=%.2fms",
					unloadCount, unloadNanos / 1e6 / unloadCount, unloadMax / 1e6);
		}
		if(aiScanCount > 0) {
			line += String.format(java.util.Locale.ROOT, " | aiScan n=%d avg=%.3fms max=%.2fms total=%.1fms",
					aiScanCount, aiScanNanos / 1e6 / aiScanCount, aiScanMax / 1e6, aiScanNanos / 1e6);
		}
		if(saveCount > 0) {
			line += String.format(java.util.Locale.ROOT, " | save n=%d avg=%.2fms max=%.2fms",
					saveCount, saveNanos / 1e6 / saveCount, saveMax / 1e6);
		}

		try {
			com.hbm.main.MainRegistry.logger.info(line);
		} catch(Throwable t) {
			System.out.println(line);
		}

		diskNanos = lightNanos = lightDoneNanos = 0;
		diskCount = lightCount = lightDoneCount = 0;
		diskMax = lightMax = 0;
		unloadNanos = saveNanos = 0;
		unloadCount = saveCount = 0;
		unloadMax = saveMax = 0;
		aiScanNanos = 0;
		aiScanCount = 0;
		aiScanMax = 0;
	}
}
