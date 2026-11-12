package com.hbm.main;

import java.util.List;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.SaveFormatComparator;

/**
 * 脚本化的基准测试入口：启动客户端后自动进入存档 → 等世界稳定 → 跑一次 CommandBench → 退出。
 *
 * 由 JVM 参数控制（build.gradle 会把 -P 转发成 -D）：
 *   -Dhbm.bench.auto=100        跑多少块，0/缺省 = 关闭
 *   -Dhbm.bench.offset=5000     起始区块偏移
 *   -Dhbm.bench.delay=100       进入世界后等多少 tick 再跑（默认 100）
 *   -Dhbm.bench.world=存档目录名 指定存档，缺省用列表里第一个
 *   -Dhbm.prof.tag=before/after 结果标签
 *
 * 这样 ./tools/bench.sh before 就能全自动跑出一行可比的结果。
 */
public class BenchAuto {

	private static final int COUNT = Integer.getInteger("hbm.bench.auto", 0);
	private static final int OFFSET = Integer.getInteger("hbm.bench.offset", 5000);
	private static final int DELAY = Integer.getInteger("hbm.bench.delay", 100);
	private static final String WORLD = System.getProperty("hbm.bench.world", null);
	/** 进世界后要执行的命令，多个用 | 分隔（会依次执行，间隔 hbm.bench.gap tick） */
	private static final String CMD = System.getProperty("hbm.bench.cmd", null);
	private static final int CMD_GAP = Integer.getInteger("hbm.bench.gap", 400);

	private boolean launched;
	private boolean done;
	private int ticks;
	private int cmdIndex;
	private final String[] cmds = CMD == null ? new String[0] : CMD.split("\\|");

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {

		if(event.phase != TickEvent.Phase.END || done) return;
		if(COUNT <= 0 && CMD == null) return;

		Minecraft mc = Minecraft.getMinecraft();

		if(mc.theWorld == null) {
			if(!launched) {
				launched = true;
				launchWorld(mc);
			}
			return;
		}

		if(mc.thePlayer == null) return;
		if(++this.ticks < DELAY) return;

		// 支持 "cmd1|cmd2|cmd3"，每条间隔 CMD_GAP tick（默认 20 秒）
		if(CMD != null && this.cmdIndex < this.cmds.length) {
			if(this.ticks >= DELAY + this.cmdIndex * CMD_GAP) {
				String cmd = this.cmds[this.cmdIndex++].trim();
				if(!cmd.isEmpty()) {
					try {
						MainRegistry.logger.info("[bench-auto] 执行命令(" + cmdIndex + "/" + cmds.length + "): /" + cmd);
						server().getCommandManager().executeCommand(server(), cmd);
					} catch(Throwable t) {
						MainRegistry.logger.error("[bench-auto] 命令失败: " + cmd, t);
					}
				}
				if(this.cmdIndex < this.cmds.length) return;   // 还有下一条，等下个间隔
			} else {
				return;
			}
		}

		this.done = true;

		MinecraftServer server = MinecraftServer.getServer();
		if(server == null || server.worldServers == null || server.worldServers.length == 0) return;

		if(CMD != null) {
			try {
				MainRegistry.logger.info("[bench-auto] 执行命令: /" + CMD);
				server.getCommandManager().executeCommand(server, CMD);
			} catch(Throwable t) {
				MainRegistry.logger.error("[bench-auto] 命令执行失败", t);
			}
		} else {
			String line = com.hbm.commands.CommandBench.run(server.worldServers[0], COUNT, OFFSET,
					System.getProperty("hbm.prof.tag", "auto"));
			MainRegistry.logger.info(line);
		}

		// 等 CSV 落盘再退出，脚本可以立刻读结果
		final Minecraft mcFinal = mc;
		Thread exit = new Thread(new Runnable() {
			@Override
			public void run() {
				try { Thread.sleep(1500L); } catch(InterruptedException ignored) { }
				try { mcFinal.shutdown(); } catch(Throwable ignored) { }
				System.exit(0);
			}
		}, "bench-exit");
		exit.setDaemon(true);
		exit.start();
	}

	private static MinecraftServer server() {
		return MinecraftServer.getServer();
	}

	private void launchWorld(Minecraft mc) {

		try {
			List<?> saves = mc.getSaveLoader().getSaveList();
			if(saves == null || saves.isEmpty()) {
				MainRegistry.logger.error("[bench-auto] 没有可用存档");
				return;
			}

			SaveFormatComparator pick = null;
			for(Object o : saves) {
				SaveFormatComparator c = (SaveFormatComparator) o;
				if(WORLD == null || WORLD.equals(c.getFileName()) || WORLD.equals(c.getDisplayName())) {
					pick = c;
					break;
				}
			}
			if(pick == null) pick = (SaveFormatComparator) saves.get(0);

			MainRegistry.logger.info("[bench-auto] 自动进入世界: " + pick.getFileName());
			mc.launchIntegratedServer(pick.getFileName(), pick.getDisplayName(),
					new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT));

		} catch(Throwable t) {
			MainRegistry.logger.error("[bench-auto] 自动进入世界失败", t);
		}
	}
}
