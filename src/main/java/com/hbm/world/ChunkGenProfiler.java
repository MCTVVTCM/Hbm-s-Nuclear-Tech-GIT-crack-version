package com.hbm.world;

import com.hbm.main.MainRegistry;

/**
 * 区块生成耗时统计。
 *
 * 只做测量，不改任何行为：把每次 cps.loadChunk(x,z) 的耗时按毫秒分桶累计，
 * 每 N 次在服务端日志里打一行，用来回答"到底慢在哪一段、慢到什么程度"。
 *
 * 说明一个能力边界：1.7.10 的 vanilla 生成器没有分阶段的入口
 * （generateTerrain / MapGenCaves / generateSkylightMap / populate 都在 ChunkProviderGenerate 内部，
 * 且 populate 就在 ChunkProviderServer.loadChunk 的末尾同步调用），
 * 所以不写 ASM 就只能测到"整个区块"这一层的总耗时。
 * 想要分段，只能用这里的结果先判断值不值得做；
 * 真要做分段，做法是继承 ChunkProviderGenerate 覆写 provideChunk/populate 再替换当前 provider。
 */
public class ChunkGenProfiler {

	/** 总开关，关掉后 record 直接返回 */
	public static boolean ENABLED = true;
	/** 每统计多少次打一行日志 */
	public static int REPORT_EVERY = 200;
	/** 超过这个耗时的服务端 tick 记一次现场快照（ms），0 = 关闭 */
	public static int SLOW_TICK_MS = 150;
	/** 快照最多每秒一条，避免尖峰连发刷屏 */
	private static long lastSlowLogAt;
	private static long lastGcTotalMs = -1;
	/** 测量标签：-Dhbm.prof.tag=before / after，方便把两次运行的日志分开对比 */
	public static final String TAG = System.getProperty("hbm.prof.tag", "-");

	/** 分桶边界（ms） */
	private static final double[] BOUNDS = { 2, 5, 10, 20, 40, 80, 160, Double.MAX_VALUE };
	private static final String[] LABELS = { "<2", "<5", "<10", "<20", "<40", "<80", "<160", ">=160" };
	private static final long[] BUCKETS = new long[BOUNDS.length];

	// 生成（磁盘上没有，必须现场生成）—— 这是主线程真正被拖住的量
	private static long genCount;
	private static long genSumNanos;
	private static long genMaxNanos;

	// 读盘（磁盘上有，走异步或同步读）
	private static long loadCount;
	private static long loadSumNanos;
	private static long loadMaxNanos;

	// 每个服务端 tick 里花在区块上的总时间
	private static long tickSumNanos;
	private static long tickMaxNanos;
	private static long tickCount;

	// 整个服务端 tick 的长度（最直接的"卡不卡"指标）
	private static long srvSumNanos;
	private static long srvMaxNanos;
	private static long srvCount;
	/** 服务端 tick 长度分布（ms） */
	private static final double[] TICK_BOUNDS = { 20, 30, 50, 100, 200, 500, Double.MAX_VALUE };
	private static final String[] TICK_LABELS = { "<20", "<30", "<50", "<100", "<200", "<500", ">=500" };
	private static final long[] TICK_BUCKETS = new long[TICK_BOUNDS.length];

	// GC / 内存 / 区块数
	private static long lastGcCount = -1;
	private static long lastGcTimeMs = -1;
	private static long reportedGcMs;
	private static long reportedGcCount;

	/** 记录一个区块的耗时。generated = true 表示这是现场生成，false 表示磁盘加载 */
	public static void record(long nanos, boolean generated) {

		if(!ENABLED || nanos < 0) return;

		if(generated) {
			genCount++;
			genSumNanos += nanos;
			if(nanos > genMaxNanos) genMaxNanos = nanos;
		} else {
			loadCount++;
			loadSumNanos += nanos;
			if(nanos > loadMaxNanos) loadMaxNanos = nanos;
		}

		double ms = nanos / 1000000.0D;
		for(int i = 0; i < BOUNDS.length; i++) {
			if(ms < BOUNDS[i]) { BUCKETS[i]++; break; }
		}
	}

	/** 记录一个完整服务端 tick 的长度 */
	public static void recordServerTick(long nanos) {
		if(!ENABLED || nanos < 0) return;
		srvCount++;
		srvSumNanos += nanos;
		if(nanos > srvMaxNanos) srvMaxNanos = nanos;

		double ms = nanos / 1000000.0D;
		for(int i = 0; i < TICK_BOUNDS.length; i++) {
			if(ms < TICK_BOUNDS[i]) { TICK_BUCKETS[i]++; break; }
		}

		if(SLOW_TICK_MS > 0 && ms >= SLOW_TICK_MS) slowTick(ms);
	}

	/**
	 * 慢 tick 现场快照：把这一 tick 发生时能反映"是谁拖的"的几个数字一起打出来。
	 * 不依赖 /debug（profiler 自己会放大开销），所以测到的耗时就接近真实。
	 */
	private static void slowTick(double ms) {

		long now = System.currentTimeMillis();
		if(now - lastSlowLogAt < 1000L) return;
		lastSlowLogAt = now;

		try {
			net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
			if(server == null || server.worldServers == null || server.worldServers.length == 0) return;

			net.minecraft.world.WorldServer w = server.worldServers[0];
			if(w == null) return;

			long gcMs = gcTotal();
			long gcDelta = lastGcTotalMs < 0 ? 0 : gcMs - lastGcTotalMs;
			lastGcTotalMs = gcMs;

			int unlit = 0;
			Object provider = w.getChunkProvider();
			if(provider instanceof net.minecraft.world.gen.ChunkProviderServer) {
				for(Object o : ((net.minecraft.world.gen.ChunkProviderServer) provider).loadedChunks) {
					if(!((net.minecraft.world.chunk.Chunk) o).isLightPopulated) unlit++;
				}
			}

			Runtime rt = Runtime.getRuntime();
			long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;

			log("[hbm-slowtick] " + String.format(java.util.Locale.ROOT,
					"tick=%.1fms chunks=%d ents=%d unlit=%d heap=%dMB gcDelta=%dms dim=%d",
					ms, w.getChunkProvider().getLoadedChunkCount(), w.loadedEntityList.size(),
					unlit, usedMb, gcDelta, w.provider.dimensionId));

		} catch(Throwable ignored) { }
	}

	private static long gcTotal() {
		long total = 0;
		try {
			for(java.lang.management.GarbageCollectorMXBean b
					: java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
				if(b.getCollectionTime() > 0) total += b.getCollectionTime();
			}
		} catch(Throwable ignored) { }
		return total;
	}

	/** 记录一个 tick 内用于区块加载/生成的总时间 */
	public static void recordTick(long nanos) {

		if(!ENABLED || nanos < 0) return;

		tickCount++;
		tickSumNanos += nanos;
		if(nanos > tickMaxNanos) tickMaxNanos = nanos;
	}

	private static long lastReportAt = System.currentTimeMillis();
	private static long reportedGenCount;

	/** 每 REPORT_EVERY 块、或距上次输出超过 10 秒（且有新数据）就打一行 */
	public static void maybeReport() {

		if(!ENABLED) return;

		long now = System.currentTimeMillis();
		boolean full = genCount > 0 && genCount % REPORT_EVERY == 0;
		boolean stale = genCount > reportedGenCount && now - lastReportAt > 10000L;

		if(full || stale) {
			report();
			lastReportAt = now;
			reportedGenCount = genCount;
		}
	}

	public static void report() {

		StringBuilder sb = new StringBuilder("[hbm-chunkprof] tag=").append(TAG).append(' ');
		sb.append("gen n=").append(genCount)
			.append(" avg=").append(String.format("%.1f", genCount == 0 ? 0 : genSumNanos / 1000000.0D / genCount)).append("ms")
			.append(" max=").append(String.format("%.1f", genMaxNanos / 1000000.0D)).append("ms");

		if(loadCount > 0) {
			sb.append(" | load n=").append(loadCount)
				.append(" avg=").append(String.format("%.1f", loadSumNanos / 1000000.0D / loadCount)).append("ms")
				.append(" max=").append(String.format("%.1f", loadMaxNanos / 1000000.0D)).append("ms");
		}

		if(tickCount > 0) {
			sb.append(" | pregen avg=").append(String.format("%.2f", tickSumNanos / 1000000.0D / tickCount)).append("ms")
				.append(" max=").append(String.format("%.1f", tickMaxNanos / 1000000.0D)).append("ms");
		}

		if(srvCount > 0) {
			sb.append(" | srvTick avg=").append(String.format("%.2f", srvSumNanos / 1000000.0D / srvCount)).append("ms")
				.append(" max=").append(String.format("%.1f", srvMaxNanos / 1000000.0D)).append("ms ");
			for(int i = 0; i < TICK_BUCKETS.length; i++) {
				if(TICK_BUCKETS[i] == 0) continue;
				sb.append(TICK_LABELS[i]).append(":").append(TICK_BUCKETS[i]).append(" ");
			}
		}

		appendRuntime(sb);

		sb.append(" | dist ");
		for(int i = 0; i < BUCKETS.length; i++) {
			if(BUCKETS[i] == 0) continue;
			sb.append(LABELS[i]).append("ms:").append(BUCKETS[i]).append(" ");
		}

		log(sb.toString());
	}

	/** 打印一行并清零，方便下一段独立统计 */
	public static void reportAndReset() {
		report();
		reset();
	}

	public static void reset() {
		genCount = genSumNanos = genMaxNanos = 0;
		loadCount = loadSumNanos = loadMaxNanos = 0;
		tickSumNanos = tickMaxNanos = tickCount = 0;
		srvSumNanos = srvMaxNanos = srvCount = 0;
		java.util.Arrays.fill(TICK_BUCKETS, 0L);
		java.util.Arrays.fill(BUCKETS, 0L);
	}

	/** GC 时间增量 / 堆占用 / 已加载区块数 —— 用来区分"我们自己算得慢"还是"GC 或内存" */
	private static void appendRuntime(StringBuilder sb) {

		try {
			java.lang.management.GarbageCollectorMXBean[] beans =
					java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
							.toArray(new java.lang.management.GarbageCollectorMXBean[0]);

			long gcCount = 0, gcMs = 0;
			for(java.lang.management.GarbageCollectorMXBean b : beans) {
				if(b.getCollectionCount() > 0) gcCount += b.getCollectionCount();
				if(b.getCollectionTime() > 0) gcMs += b.getCollectionTime();
			}

			if(lastGcCount >= 0) {
				reportedGcCount += gcCount - lastGcCount;
				reportedGcMs += gcMs - lastGcTimeMs;
			}
			lastGcCount = gcCount;
			lastGcTimeMs = gcMs;

			Runtime rt = Runtime.getRuntime();
			long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
			long maxMb = rt.maxMemory() / 1048576L;

			sb.append(" | gc +").append(reportedGcMs).append("ms/").append(reportedGcCount)
				.append(" heap ").append(usedMb).append("/").append(maxMb).append("MB");

			reportedGcMs = 0;
			reportedGcCount = 0;

			net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
			if(server != null && server.worldServers != null && server.worldServers.length > 0
					&& server.worldServers[0] != null) {
				sb.append(" chunks ").append(server.worldServers[0].getChunkProvider().getLoadedChunkCount());
				// 实体数：profiler 显示实体 tick 占了 73%，这个数字直接决定服务端负担
				sb.append(" ents ").append(server.worldServers[0].loadedEntityList.size());

				// 未完成光照初始化的区块数。
				// Chunk.func_150804_b() 在 !isLightPopulated 时会**每 tick**重新跑一遍光照填充，
				// profiler 里 tickChunk 的 getBrightness 占到 40%，多半就是这个循环。
				// 正常情况下这个数应该接近 0；持续很大说明有区块卡在"地形有、光照没填"的状态。
				try {
					Object provider = server.worldServers[0].getChunkProvider();
					if(provider instanceof net.minecraft.world.gen.ChunkProviderServer) {
						int unlit = 0;
						for(Object o : ((net.minecraft.world.gen.ChunkProviderServer) provider).loadedChunks) {
							net.minecraft.world.chunk.Chunk c = (net.minecraft.world.chunk.Chunk) o;
							if(!c.isLightPopulated) unlit++;
						}
						sb.append(" unlit ").append(unlit);
					}
				} catch(Throwable ignored) { }
			}
		} catch(Throwable ignored) {
			// 统计不许影响主循环
		}
	}

	private static void log(String s) {
		try {
			MainRegistry.logger.info(s);
		} catch(Throwable ignored) {
			// 统计代码绝不能把服务端 tick 带崩
		}
	}
}
