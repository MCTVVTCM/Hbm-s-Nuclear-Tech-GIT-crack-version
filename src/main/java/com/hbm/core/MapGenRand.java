package com.hbm.core;

import java.util.Random;

import net.minecraft.world.gen.MapGenBase;

/**
 * ============================================================================
 * 【已关闭的实验特性】并行区块生成 —— 结论：在 1.7.10 上不可靠，默认关闭。
 * ============================================================================
 *
 * 目标：把地形生成搬到工作线程，消除"飞进新地形时单 tick 500~600ms"的尖峰。
 *
 * 已验证成立：
 *   - 两个独立的 ChunkProviderGenerate（同一种子）串行生成同一批区块，方块哈希逐位一致
 *     （/ntmasync → match=30 DETERMINISTIC）；
 *   - BiomeCache 的并发查询靠加 synchronized 可以解决（不再 NPE，见 ChunkCostTransformer）。
 *
 * 失败原因（真并发 /ntmasync → match=0/20）：
 *   1. 原版 MapGenBase.public Random rand 是所有生成器共享的 —— 本类用 ThreadLocal 修掉了；
 *   2. 但 HBM 的世界生成器走 Forge 的 IWorldGenerator.generate(Random, ...)，
 *      Random 是**外部传进来的参数**，不经过 MapGenBase.rand，本类管不到；
 *   3. 各 mod 还可能持有自己的静态缓存、World 引用、跨区块状态，无法集中隔离。
 *
 * 要让并行生成成立，必须**逐个 mod 证明其世界生成器线程安全**。
 * 1.7.10 生态没有这种约束，实际做不到（官方是靠重写整个区块系统 + 强制新 API 才实现的）。
 *
 * 所以：
 *   - 默认 false，ASM 不注入任何转发，游戏行为与完全原版一致；
 *   - 只有显式加 -Dhbm.chunk.async=true 才启用（仅用于继续研究/回归测试）；
 *   - 启用后生成结果可能与串行不同 —— **会改变世界**，不要用在正式存档。
 * ============================================================================
 */
public final class MapGenRand {

	/** 开关：默认关闭。ASM 只有在它为 true 时才会把 MapGenBase.rand 的读取转发到这里 */
	public static boolean ENABLED = Boolean.parseBoolean(System.getProperty("hbm.chunk.async", "false"));

	private static final ThreadLocal<Random> PER_THREAD = new ThreadLocal<Random>() {
		@Override
		protected Random initialValue() {
			return new Random();
		}
	};

	private MapGenRand() { }

	/** 由 ASM 把对 MapGenBase.rand 的读取重定向到这里 */
	public static Random get(MapGenBase generator) {
		return PER_THREAD.get();
	}
}
