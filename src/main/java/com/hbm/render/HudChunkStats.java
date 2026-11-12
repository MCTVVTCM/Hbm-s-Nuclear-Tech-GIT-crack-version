package com.hbm.render;

import com.hbm.entity.misc.EntityJindouYun;
import com.hbm.util.ChunkStats;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/**
 * 骑筋斗云时自动出现的实时 HUD —— 不需要敲命令、不写日志。
 *
 * 显示三行：
 *   筋斗云  当前速度 b/t（括号里是配置的巡航上限）
 *   区块    客户端已加载数量 / 每秒加载 / 每秒新生成
 *   预取    挂起数 / 上限，服务端 TPS
 *
 * 数据来源：客户端区块数来自 IChunkProvider.getLoadedChunkCount()；
 * 加载/生成速率来自服务端线程维护的 ChunkStats 计数（单人同 JVM，volatile 读即可）；
 * TPS 用 MinecraftServer.getTickCounter() 的增量算。
 *
 * 每 1000ms 才重算一次并重建字符串，其余帧只画缓存，不产生每帧分配。
 */
public class HudChunkStats {

	/** true = 什么时候都显示；false = 只在骑筋斗云时显示 */
	public static boolean ALWAYS = true;

	private long lastSample;
	private long lastLoaded, lastGenerated;
	private int lastTick;
	private double loadPerSec, genPerSec, tps = 20.0D;

	private String line1 = "", line2 = "", line3 = "";

	@SubscribeEvent
	public void onRenderOverlay(RenderGameOverlayEvent.Text event) {

		Minecraft mc = Minecraft.getMinecraft();
		EntityClientPlayerMP player = mc.thePlayer;
		if(player == null) return;

		// F3 打开时让位给原版调试信息
		if(mc.gameSettings.showDebugInfo) return;

		Entity cloud = player.ridingEntity;

		boolean riding = cloud instanceof EntityJindouYun;
		if(!ALWAYS && !riding) return;

		long now = System.currentTimeMillis();
		if(now - lastSample >= 1000L) {
			sample(mc, riding ? (EntityJindouYun) cloud : null, now);
		}

		ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
		FontRenderer fr = mc.fontRenderer;

		int x = 4;
		int y = 4;

		fr.drawStringWithShadow(line1, x, y, 0xFFFFFF);
		fr.drawStringWithShadow(line2, x, y + 10, 0xFFFFFF);
		fr.drawStringWithShadow(line3, x, y + 20, 0xFFFFFF);
	}

	private void sample(Minecraft mc, EntityJindouYun cloud, long now) {

		double seconds = (now - lastSample) / 1000.0D;
		if(lastSample == 0 || seconds <= 0.001D) seconds = 1.0D;
		lastSample = now;

		// ---- 速率 ----
		long loaded = ChunkStats.loadedTotal;
		long generated = ChunkStats.generatedTotal;

		loadPerSec = (loaded - lastLoaded) / seconds;
		genPerSec = (generated - lastGenerated) / seconds;

		lastLoaded = loaded;
		lastGenerated = generated;

		// ---- TPS：单人才能拿到服务端 tick 计数 ----
		MinecraftServer srv = MinecraftServer.getServer();
		if(srv != null) {
			int tick = srv.getTickCounter();
			if(lastTick != 0) {
				double delta = tick - lastTick;
				if(seconds > 0.001D) tps = delta / seconds;
			}
			lastTick = tick;
		}

		// ---- 客户端已加载区块数 ----
		int chunks = mc.theWorld == null ? 0 : mc.theWorld.getChunkProvider().getLoadedChunkCount();

		// ---- 当前速度 ----
		double speed = 0.0D;
		if(cloud != null) {
			speed = Math.sqrt(cloud.motionX * cloud.motionX
					+ cloud.motionY * cloud.motionY
					+ cloud.motionZ * cloud.motionZ);
		}

		String speedColor = speed > 0.5D ? "\u00a7b" : "\u00a77";

		if(cloud == null) {
			// 玩家速度由右下角的 HudPortalSpeed 常驻显示，这里不重复
			line1 = "\u00a77筋斗云 \u00a78未骑乘";
		} else {
			line1 = "\u00a7b筋斗云 \u00a7r" + speedColor + fmt(speed) + " b/t"
					+ " \u00a78= " + fmt(speed * 20.0D) + " b/s"
					+ " (" + fmt(EntityJindouYun.SPEED) + " \u00d7" + EntityJindouYun.BOOST_MULT + ")\u00a7r"
					+ (cloud.isWarping() ? " \u00a7d曲速" : "");
		}

		line2 = "\u00a77区块 \u00a7r" + chunks
				+ " \u00a77加载 \u00a7r" + fmt(loadPerSec) + "/s"
				+ " \u00a77生成 \u00a7r" + colorGen(fmt(genPerSec)) + "/s";

		line3 = "\u00a77预取 \u00a7r" + EntityJindouYun.getPrefetchOutstanding()
				+ "/" + EntityJindouYun.PREFETCH_MAX_OUTSTANDING
				+ " \u00a77TPS \u00a7r" + colorTps(fmt(tps));
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.US, "%.1f", v);
	}

	private static String colorGen(String s) {
		try {
			double v = Double.parseDouble(s);
			if(v > 8.0D) return "\u00a7c" + s;   // 生成太快：主线程压力大
			if(v > 0.01D) return "\u00a7e" + s;  // 正在开新地形
			return "\u00a7a" + s;                // 纯读盘
		} catch(NumberFormatException e) {
			return s;
		}
	}

	private static String colorTps(String s) {
		try {
			double v = Double.parseDouble(s);
			if(v < 15.0D) return "\u00a7c" + s;
			if(v < 19.0D) return "\u00a7e" + s;
			return "\u00a7a" + s;
		} catch(NumberFormatException e) {
			return s;
		}
	}
}
