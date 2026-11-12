package com.hbm.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hbm.main.MainRegistry;

/**
 * 慢 tick 栈采样器（"穷人版 jstack"）。
 *
 * 为什么不用 /debug：1.7.10 的 profiler 在启用后会给每个 section 做 HashMap 查找 + 两次 nanoTime，
 * 这个包里的 section 数量极多，实测会把 14ms 的 tick 放大到 70ms+，而且窗口失焦时服务端会被拖住，
 * 采到的 TPS 完全不可信。
 *
 * 这里改用一个后台线程，只在"当前 tick 已经跑了超过 SLOW_MS"时，按固定间隔去抓服务端线程的栈，
 * 统计叶子方法（真正在执行的那一行）。开销只有 getStackTrace，对主线程没有插桩。
 *
 * 输出示例：
 *   [hbm-watch] tick=318ms n=9 top: World.updateEntities 44% | ChunkProviderServer.func_147456_g 33% | ...
 */
public final class TickWatchdog {

	public static int SLOW_MS = Integer.getInteger("hbm.watch.slow", 120);
	public static int SAMPLE_MS = Integer.getInteger("hbm.watch.sample", 20);

	private static volatile Thread serverThread;
	private static volatile long tickStartNanos;
	private static volatile boolean running;

	private static final Map<String, Integer> hits = new HashMap<String, Integer>();
	private static int samples;

	private static boolean threadStarted;

	private TickWatchdog() { }

	public static void tickStart() {
		serverThread = Thread.currentThread();
		synchronized(hits) {
			hits.clear();
			samples = 0;
		}
		tickStartNanos = System.nanoTime();
		ensureSampler();
	}

	public static void tickEnd(long nanos) {

		tickStartNanos = 0L;

		if(nanos < SLOW_MS * 1000000L) return;

		List<Map.Entry<String, Integer>> top;
		int total;
		synchronized(hits) {
			total = samples;
			if(total == 0) return;
			top = new ArrayList<Map.Entry<String, Integer>>(hits.entrySet());
		}

		top.sort((a, b) -> b.getValue() - a.getValue());

		StringBuilder sb = new StringBuilder();
		sb.append("[hbm-watch] tick=").append(String.format(java.util.Locale.ROOT, "%.0fms", nanos / 1e6))
			.append(" n=").append(total).append(" top: ");

		int shown = 0;
		for(Map.Entry<String, Integer> e : top) {
			if(shown++ >= 5) break;
			sb.append(e.getKey()).append(' ').append(e.getValue() * 100 / total).append("% | ");
		}

		try {
			MainRegistry.logger.info(sb.toString());
		} catch(Throwable ignored) { }
	}

	private static void ensureSampler() {
		if(threadStarted) return;
		threadStarted = true;

		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				while(true) {
					try {
						Thread.sleep(SAMPLE_MS);
					} catch(InterruptedException ignored) { }

					long start = tickStartNanos;
					if(start == 0L) continue;
					if(System.nanoTime() - start < SLOW_MS * 1000000L) continue;

					Thread target = serverThread;
					if(target == null || !target.isAlive()) continue;

					String leaf = leafOf(target.getStackTrace());
					if(leaf == null) continue;

					synchronized(hits) {
						Integer c = hits.get(leaf);
						hits.put(leaf, c == null ? 1 : c + 1);
						samples++;
					}
				}
			}
		}, "hbm-tick-watchdog");

		t.setDaemon(true);
		t.start();
	}

	/** 取第一个"属于游戏逻辑"的栈帧，跳过线程框架和我们自己的采样代码 */
	private static String leafOf(StackTraceElement[] st) {

		for(StackTraceElement e : st) {
			String cn = e.getClassName();
			if(cn.startsWith("java.") || cn.startsWith("sun.") || cn.startsWith("jdk.")) continue;
			if(cn.startsWith("com.hbm.world.TickWatchdog")) continue;
			return cn + "." + e.getMethodName();
		}
		return null;
	}
}
