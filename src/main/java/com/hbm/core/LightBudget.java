package com.hbm.core;

import net.minecraft.world.chunk.Chunk;

/**
 * 光照预算：把"新加载区块的天空光填充"分摊到多个 tick。
 *
 * 背景：1.7.10 里 WorldServer 每个 tick 会对**所有**活跃区块调用 {@code Chunk.func_150804_b(false)}。
 * 对已经点亮过的区块这几乎免费，但对刚加载、{@code isLightPopulated == false} 的区块，
 * 它会跑一遍完整的天空光填充 + 跨区块传播，单块 3~5ms。
 *
 * 飞行时一次跨列会加载一整排区块（视距 16 时 33 块），它们全部处于 unlit 状态，
 * 于是同一个 tick 里要补 300~400 块 × 4ms ≈ 1.5~2 秒 —— 这就是实测到的 2308ms 尖峰。
 *
 * 这里只做"排队"，不改任何光照算法：
 *   - 已经点亮的区块：原样放行（便宜）；
 *   - 未点亮的区块：每 tick 只放行 {@link #PER_TICK} 个，其余跳过，
 *     它们的 isLightPopulated 保持 false，下个 tick 继续补。
 *
 * 结果：尖峰从 2000ms 降到十几毫秒，代价是新加载的区域会短暂偏暗（通常不到 1 秒）。
 * 光照数据本身的计算方式完全没变，所以生成出来的世界与原来一致。
 */
public class LightBudget {

	/**
	 * 每 tick 最多补多少个未点亮区块。
	 *
	 * **默认 0 = 关闭，保持原版行为。**
	 *
	 * 原因：限流会让区块在"光照还没算完"时就被 PlayerManager 发出去（S21 包里光照全 0），
	 * 客户端显示为黑块，而且服务端不会自动重发 —— 这是视觉效果上的硬伤，换 0.5 秒的
	 * tick 平滑不值得。想临时做实验可以 -Dhbm.light.budget=64，但别当默认值。
	 * 注意：早期的一次"限制光照反而更慢"的结论是被预取泄漏污染的（chunks 涨到 4317），
	 * 不是预算本身的问题。预取关掉之后重测，限流是正确方向。
	 */
	public static int PER_TICK = Integer.getInteger("hbm.light.budget", 0);

	private static long currentTick = Long.MIN_VALUE;
	private static int used;

	/** 由 ASM 把 WorldServer 里的 chunk.func_150804_b(flag) 重定向到这里 */
	public static void tick(Chunk chunk, boolean flag) {

		if(chunk == null) return;

		// 已经点亮过的区块：func_150804_b 只做一次 gap 检查和置标志位，很便宜，直接放行
		if(chunk.isLightPopulated) {
			chunk.func_150804_b(flag);
			return;
		}

		if(PER_TICK <= 0) {
			chunk.func_150804_b(flag);
			return;
		}

		long now = chunk.worldObj == null ? 0L : chunk.worldObj.getTotalWorldTime();
		if(now != currentTick) {
			currentTick = now;
			used = 0;
		}

		if(used >= PER_TICK) return;   // 超预算：这一 tick 跳过，下个 tick 继续

		used++;
		chunk.func_150804_b(flag);
	}
}
