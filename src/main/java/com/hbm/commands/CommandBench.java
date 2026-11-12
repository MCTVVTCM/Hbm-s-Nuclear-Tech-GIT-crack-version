package com.hbm.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * /ntmbench &lt;块数&gt; [区块偏移]
 *
 * 在玩家附近但**从未生成过**的区域，按固定顺序串行生成 N 个区块，输出：
 *   - 每块耗时：avg / p50 / p95 / max
 *   - 所有区块方块的 FNV-1a 64 位校验和
 *   - 与历史基线（bench-results.csv）的自动比对结论
 *
 * 用途一：衡量"每块生成多快"。改代码前后各跑一次，看 avg 和 p95。
 * 用途二：**证明改动没有改变世界**。同一组坐标、同一个种子，校验和必须逐位相同。
 *
 * 也可以由 -Dhbm.bench.auto=N 触发（见 BenchAuto），用于脚本化 A/B 对比。
 */
public class CommandBench extends CommandBase {

	private static final String CSV = "bench-results.csv";

	@Override
	public String getCommandName() {
		return "ntmbench";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/ntmbench <chunks> [chunkOffset]   —— chunkOffset 默认 5000（约 8 万格外，保证未生成）";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) {

		int count = args.length > 0 ? parseIntBounded(sender, args[0], 1, 2000) : 50;
		int offset = args.length > 1 ? parseIntBounded(sender, args[1], -200000, 200000) : 5000;

		WorldServer world = (WorldServer) sender.getEntityWorld();
		String line = run(world, count, offset, System.getProperty("hbm.prof.tag", "-"));

		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + line));
		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[bench] 详细记录："
				+ new File(CSV).getAbsolutePath()));
	}

	/**
	 * 核心：生成、计时、哈希、比对、写 CSV。返回一行结果。
	 * 命令和自动跑（BenchAuto）共用这一份实现，保证两条路径测出来的东西完全一样。
	 */
	public static String run(WorldServer world, int count, int offset, String tag) {

		ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();

		int px = MathHelper.floor_double(world.getSpawnPoint().posX) >> 4;
		int pz = MathHelper.floor_double(world.getSpawnPoint().posZ) >> 4;

		// 自动避开已经生成过的区域，否则测到的是缓存、哈希也没法比
		int baseX = px + offset;
		int baseZ = pz + offset;

		boolean cached = true;
		int tries = 0;
		while(cached && tries++ < 16) {
			cached = false;
			for(int i = 0; i < count && !cached; i++) {
				cached = cps.chunkExists(baseX + i * 2, baseZ);
			}
			if(cached) {
				offset += 200;
				baseX = px + offset;
				baseZ = pz + offset;
			}
		}

		long[] times = new long[count];
		long hash = 0xcbf29ce484222325L;   // FNV-1a 64 offset basis
		long start = System.nanoTime();

		for(int i = 0; i < count; i++) {
			int cx = baseX + i * 2;        // 隔一列，避免邻居互相触发 populate 造成抖动
			long t0 = System.nanoTime();
			Chunk chunk = cps.loadChunk(cx, baseZ);
			times[i] = System.nanoTime() - t0;
			hash = hashChunk(chunk, hash);
		}

		long total = System.nanoTime() - start;

		long[] sorted = times.clone();
		Arrays.sort(sorted);

		double sum = 0;
		for(long t : times) sum += t;

		double avgMs = sum / count / 1e6;
		double p50Ms = sorted[count / 2] / 1e6;
		double p95Ms = sorted[Math.min(count - 1, (int) Math.ceil(count * 0.95D) - 1)] / 1e6;
		double maxMs = sorted[count - 1] / 1e6;

		long seed = world.getSeed();
		String verdict = cached ? "INVALID(区块已存在)" : "NEW-BASELINE";

		if(!cached) {
			String prev = findBaseline(seed, count, offset);
			if(prev != null) {
				verdict = prev.equalsIgnoreCase(String.format("%016x", hash))
						? "WORLD-IDENTICAL"
						: "WORLD-CHANGED(baseline " + prev + ")";
			}
		}

		appendResult(seed, count, offset, cached, avgMs, p95Ms, maxMs, hash, tag);

		return String.format(Locale.ROOT,
				"[bench] tag=%s n=%d offset=%d total=%.1fms avg=%.1fms p50=%.1f p95=%.1f max=%.1f hash=%016x verdict=%s",
				tag, count, offset, total / 1e6, avgMs, p50Ms, p95Ms, maxMs, hash, verdict);
	}

	/** 读上一次同 seed / 同 n / 同 offset 的有效结果里的 hash */
	private static String findBaseline(long seed, int count, int offset) {

		File f = new File(CSV);
		if(!f.isFile()) return null;

		String want = seed + "," + count + "," + offset + ",no,";

		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			String line, last = null;
			while((line = r.readLine()) != null) {
				if(line.startsWith("#")) continue;
				if(line.startsWith(want)) last = line;
			}
			if(last == null) return null;
			String[] parts = last.split(",");
			return parts.length >= 8 ? parts[7] : null;
		} catch(Throwable ignored) {
			return null;
		}
	}

	private static void appendResult(long seed, int count, int offset, boolean cached,
			double avg, double p95, double max, long hash, String tag) {

		try {
			File f = new File(CSV);
			boolean fresh = !f.isFile();
			try (FileWriter w = new FileWriter(f, true)) {
				if(fresh) {
					w.write("# seed,n,offset,cached,avg_ms,p95_ms,max_ms,hash,tag\n");
				}
				w.write(String.format(Locale.ROOT, "%d,%d,%d,%s,%.2f,%.2f,%.2f,%016x,%s\n",
						seed, count, offset, cached ? "yes" : "no", avg, p95, max, hash, tag));
			}
		} catch(Throwable ignored) { }
	}

	/**
	 * 对整块的所有方块做 FNV-1a 64 位哈希。
	 * 只取方块 id，不含 metadata —— 地形/洞穴/矿物这些在 id 层面就能体现差异。
	 * 同一个种子 + 同一组坐标，串行和并行必须得到完全相同的结果。
	 */
	private static long hashChunk(Chunk chunk, long hash) {

		if(chunk == null) return hash;

		for(int x = 0; x < 16; x++) {
			for(int z = 0; z < 16; z++) {
				for(int y = 0; y < 256; y++) {
					Block block = chunk.getBlock(x, y, z);
					int id = block == null ? 0 : Block.getIdFromBlock(block);
					hash ^= (id & 0xFFFF);
					hash *= 0x100000001b3L;
				}
			}
		}
		return hash;
	}
}
