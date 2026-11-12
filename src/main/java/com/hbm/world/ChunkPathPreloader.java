package com.hbm.world;

import java.util.List;

import com.hbm.entity.misc.EntityJindouYun;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * 沿飞行方向、用"空闲 tick 预算"提前把新地形生成出来。
 *
 * 为什么需要它：1.7.10 的新区块只能在服务端主线程同步生成（ChunkProviderServer.originalLoadChunk），
 * 而 PlayerManager 是在玩家跨列的那一刻才去要这些区块 —— 于是高速飞行时每一 tick 都要在
 * 主线程里集中生成一大片，表现为周期性卡顿、前方虚空。
 *
 * 这里把"集中生成"摊平成"每 tick 花几毫秒"，提前几列把正前方的地形先生成好，
 * 等玩家真正飞到时它已经在内存里了，PlayerManager 就不用再同步生成。
 *
 * 三条边界：
 *   1. 只处理"磁盘上没有"的区块（新地形）；磁盘上有的交给 ChunkIOExecutor 异步读，绝不在这里同步读盘
 *   2. 每 tick 有时间预算 + 数量上限，绝不会把 tick 撑爆
 *   3. 只沿飞行方向生成，不铺开整个视距，避免生成玩家用不到的区块
 *
 * 注意：这会改变区块被生成的"时机/顺序"。对依赖邻居的结构（村庄、跨区块结构）理论上存在
 * 顺序敏感性，但玩家自己走过去时顺序本来就是随机的，因此实际结果与正常游玩一致。
 */
public class ChunkPathPreloader {

	/** 总开关 */
	public static boolean ENABLED = true;
	/** 每 tick 最多花多少毫秒做提前生成 */
	public static int BUDGET_MS = 4;
	/** 每 tick 最多生成几个区块（硬上限，防止单区块生成特别慢时超时） */
	public static int CHUNKS_PER_TICK = 8;
	/** 提前多少列 */
	public static int AHEAD = 8;
	/** 横向覆盖：飞行方向左右各多少列（整排填满，别只生成中线） */
	public static int HALF_WIDTH = 6;
	/** 低于这个速度（格/tick）就不提前生成，正常走路交给原版 */
	public static double MIN_SPEED = 1.0D;

	private long tickStartNanos;
	private int serverTicks;

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {

		if(event.phase == TickEvent.Phase.START) {
			this.tickStartNanos = System.nanoTime();
			TickWatchdog.tickStart();
			return;
		}

		// 即使不预生成，也每 10 秒打一行：srvTick 的 max 就是"卡不卡"的直接证据
		long tickNanos = System.nanoTime() - this.tickStartNanos;
		ChunkGenProfiler.recordServerTick(tickNanos);
		TickWatchdog.tickEnd(tickNanos);
		if(++this.serverTicks % 200 == 0) ChunkGenProfiler.report();

		if(!ENABLED) return;

		MinecraftServer server = MinecraftServer.getServer();
		if(server == null) return;

		WorldServer[] worlds = server.worldServers;
		if(worlds == null) return;

		long deadline = System.nanoTime() + BUDGET_MS * 1000000L;

		for(int w = 0; w < worlds.length; w++) {
			WorldServer world = worlds[w];
			if(world == null) continue;

			List<?> players = world.playerEntities;
			if(players == null || players.isEmpty()) continue;

			for(int i = 0; i < players.size(); i++) {

				Object o = players.get(i);
				if(!(o instanceof EntityPlayer)) continue;

				EntityPlayer player = (EntityPlayer) o;
				if(!(player.ridingEntity instanceof EntityJindouYun)) continue;

				EntityJindouYun cloud = (EntityJindouYun) player.ridingEntity;

				double vx = cloud.motionX, vz = cloud.motionZ;
				double speed = Math.sqrt(vx * vx + vz * vz);
				if(speed < MIN_SPEED) continue;

				int budget = CHUNKS_PER_TICK;
				long t0 = System.nanoTime();
				budget = generateAhead(world, cloud, vx, vz, budget, deadline);
				ChunkGenProfiler.recordTick(System.nanoTime() - t0);
				if(budget <= 0 || System.nanoTime() > deadline) return;
			}
		}
	}

	private int generate(WorldServer world, ChunkProviderServer cps, AnvilChunkLoader loader,
			int tx, int tz, int budget, long deadline) {

		if(budget <= 0 || System.nanoTime() > deadline) return budget;
		if(cps.chunkExists(tx, tz)) return budget;                 // 已经在内存
		if(loader.chunkExists(world, tx, tz)) return budget;        // 磁盘上有：交给异步预取

		long t0 = System.nanoTime();
		cps.loadChunk(tx, tz);                                      // 只能同步生成
		ChunkGenProfiler.record(System.nanoTime() - t0, true);
		ChunkGenProfiler.maybeReport();
		return budget - 1;
	}

	private int generateAhead(WorldServer world, EntityJindouYun cloud, double vx, double vz, int budget, long deadline) {

		Object provider = world.getChunkProvider();
		if(!(provider instanceof ChunkProviderServer)) return budget;

		ChunkProviderServer cps = (ChunkProviderServer) provider;
		if(!(cps.currentChunkLoader instanceof AnvilChunkLoader)) return budget;

		AnvilChunkLoader loader = (AnvilChunkLoader) cps.currentChunkLoader;

		int cx = net.minecraft.util.MathHelper.floor_double(cloud.posX) >> 4;
		int cz = net.minecraft.util.MathHelper.floor_double(cloud.posZ) >> 4;

		int dx = (int) Math.signum(vx);
		int dz = (int) Math.signum(vz);
		if(dx == 0 && dz == 0) return budget;

		// 侧向单位向量
		int sx = dz, sz = dx;

		for(int d = 2; d <= AHEAD && budget > 0; d++) {

			for(int w = 0; w <= HALF_WIDTH && budget > 0; w++) {

				if(w == 0) {
					budget = generate(world, cps, loader, cx + dx * d, cz + dz * d, budget, deadline);
					continue;
				}

				budget = generate(world, cps, loader, cx + dx * d - sx * w, cz + dz * d - sz * w, budget, deadline);
				if(budget <= 0) break;

				budget = generate(world, cps, loader, cx + dx * d + sx * w, cz + dz * d + sz * w, budget, deadline);

				if(System.nanoTime() > deadline) return budget;
			}
		}

		return budget;
	}
}
