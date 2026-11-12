package com.hbm.util;

/**
 * 区块加载/生成的轻量计数器。
 *
 * 只做自增，没有任何日志输出；想看数据就执行 /ntmchunks，聊天栏出一行。
 * 不占 tick 时间，也不写磁盘。
 */
public class ChunkStats {

	/** 进入内存的区块总数（包括读盘和新生成） */
	public static volatile long loadedTotal;
	/** 新地形被 populate 的次数（≈ 真正新生成的区块数） */
	public static volatile long generatedTotal;

	private static long lastQueryTime;
	private static long lastQueryLoaded;
	private static long lastQueryGenerated;

	/** 上一次查询到现在：加载速率（区块/秒），首次查询为 -1 */
	public static double loadRate = -1;
	/** 上一次查询到现在：生成速率（区块/秒），首次查询为 -1 */
	public static double generateRate = -1;

	/**
	 * 采样一次速率。以"上次查询"为窗口，不额外开 tick 钩子。
	 */
	public static void sample() {
		long now = System.currentTimeMillis();

		if(lastQueryTime == 0) {
			lastQueryTime = now;
			lastQueryLoaded = loadedTotal;
			lastQueryGenerated = generatedTotal;
			return;
		}

		double seconds = (now - lastQueryTime) / 1000.0D;
		if(seconds < 0.05D) seconds = 0.05D;

		loadRate = (loadedTotal - lastQueryLoaded) / seconds;
		generateRate = (generatedTotal - lastQueryGenerated) / seconds;

		lastQueryTime = now;
		lastQueryLoaded = loadedTotal;
		lastQueryGenerated = generatedTotal;
	}
}
