package com.hbm.commands;

import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderGenerate;

/**
 * 并行生成的验证工具。**实验性质，结论已定：不可行，见 MapGenRand 的类注释。**
 *
 * 保留它是因为将来如果再碰这个问题，可以直接用它对任意改动做回归：
 *   match == n  → 生成结果与串行一致
 *   match <  n  → 有共享状态没隔离干净
 *
 * /ntmasync &lt;块数&gt; [区块偏移]
 *
 * 并行化区块生成的前置验证：**独立实例 + 同一种子，是否逐块产生完全相同的方块**。
 *
 * 做两件事：
 *   1. 用两个独立的 ChunkProviderGenerate（同种子）分别生成同一批区块，比较哈希；
 *   2. 第二个在**工作线程**上跑，同时记录主线程耗时和工作线程耗时。
 *
 * 结果判读：
 *   match = n           → 生成器是确定性的，且可以安全地每线程一个实例 → 并行化可行
 *   match < n           → 噪声器/BiomeCache 有共享状态，必须先解决隔离问题
 *
 * 注意：这条命令只在"还没被改动过的"坐标上做（用偏移量保证），
 * 生成出来的 Chunk 直接丢弃，不写世界、不影响存档。
 */
public class CommandAsyncBench extends CommandBase {

	@Override
	public String getCommandName() {
		return "ntmasync";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/ntmasync <chunks> [chunkOffset=20000]";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) {

		int count = args.length > 0 ? parseIntBounded(sender, args[0], 1, 500) : 50;
		int offset = args.length > 1 ? parseIntBounded(sender, args[1], -100000, 100000) : 20000;

		WorldServer world = (WorldServer) sender.getEntityWorld();
		long seed = world.getSeed();

		int baseX = (MathHelper.floor_double(sender.getPlayerCoordinates().posX) >> 4) + offset;
		int baseZ = (MathHelper.floor_double(sender.getPlayerCoordinates().posZ) >> 4) + offset;

		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY
				+ "[async] 验证 " + count + " 块 @ " + baseX + "," + baseZ + "（种子 " + seed + "）"));

		final ChunkProviderGenerate serial = new ChunkProviderGenerate(world, seed, false);
		final ChunkProviderGenerate worker = new ChunkProviderGenerate(world, seed, false);

		final long[] serialHash = new long[count];
		final long[] workerHash = new long[count];
		final java.util.concurrent.atomic.AtomicReference<Throwable> error =
				new java.util.concurrent.atomic.AtomicReference<Throwable>();
		final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

		// 工作线程：真并发跑另一个生成器实例
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					for(int i = 0; i < count; i++) {
						workerHash[i] = hash(worker.provideChunk(baseX + i * 2, baseZ));
					}
				} catch(Throwable e) {
					error.set(e);
				} finally {
					done.countDown();
				}
			}
		}, "ntm-async-bench");
		t.setDaemon(true);

		long t0 = System.nanoTime();
		t.start();

		// 主线程同时跑自己那份
		try {
			for(int i = 0; i < count; i++) {
				serialHash[i] = hash(serial.provideChunk(baseX + i * 2, baseZ));
			}
		} catch(Throwable e) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[async] 主线程异常: " + e));
		}

		// 最多等 20 秒，不再无限阻塞
		boolean finished;
		try {
			finished = done.await(20, java.util.concurrent.TimeUnit.SECONDS);
		} catch(InterruptedException e) {
			finished = false;
		}
		long elapsed = System.nanoTime() - t0;

		if(!finished) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
					+ "[async] 工作线程 20 秒未完成 —— 说明确实被阻塞了"));
			try { com.hbm.main.MainRegistry.logger.info("[async] TIMEOUT worker stuck"); } catch(Throwable ignored) { }
			return;
		}

		if(error.get() != null) {
			Throwable e = error.get();
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[async] 工作线程异常: " + e));
			try {
				com.hbm.main.MainRegistry.logger.error("[async] worker exception", e);
			} catch(Throwable ignored) { }
			return;
		}

		int match = 0;
		for(int i = 0; i < count; i++) {
			if(serialHash[i] == workerHash[i]) match++;
		}

		String line = String.format(Locale.ROOT,
				"[async] CONCURRENT n=%d match=%d elapsed=%.1fms %s",
				count, match, elapsed / 1e6,
				match == count ? "DETERMINISTIC" : "MISMATCH!");

		EnumChatFormatting color = match == count ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
		sender.addChatMessage(new ChatComponentText(color + line));

		try {
			com.hbm.main.MainRegistry.logger.info(line);
		} catch(Throwable ignored) { }
	}

	private static long hash(Chunk chunk) {

		if(chunk == null) return 0L;

		long h = 0xcbf29ce484222325L;
		for(int x = 0; x < 16; x++) {
			for(int z = 0; z < 16; z++) {
				for(int y = 0; y < 256; y++) {
					Block b = chunk.getBlock(x, y, z);
					int id = b == null ? 0 : Block.getIdFromBlock(b);
					h ^= (id & 0xFFFF);
					h *= 0x100000001b3L;
				}
			}
		}
		return h;
	}
}
