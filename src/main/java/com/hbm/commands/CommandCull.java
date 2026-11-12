package com.hbm.commands;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;

/**
 * /ntmcull &lt;实体类名&gt; [保留半径] [最大数量]
 *
 * 例：
 *   /ntmcull Pig 64        干掉离所有玩家都超过 64 格的猪
 *   /ntmcull EntityItem 48 干掉远处的掉落物
 *   /ntmcull Cow 96 200    远处最多再留 200 头牛
 *
 * 为什么需要它：1.7.10 没有 @e[type=...] 选择器，被动生物又不会自然消失，
 * 世界跑久了就会积累几千只猪牛羊，每只都跑 AI，服务端 tick 直接顶满。
 * 这个命令只清"玩家看不见的那部分"，附近的农场/宠物一律保留。
 *
 * 安全检查：
 *   - 有自定义名字的（命名牌）不杀；
 *   - 已驯服的（EntityTameable.isTamed）不杀；
 *   - 玩家附近 keepRadius 内的一律不杀。
 */
public class CommandCull extends CommandBase {

	@Override
	public String getCommandName() {
		return "ntmcull";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/ntmcull <实体类名> [保留半径=64] [远处最多保留数量=0]";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void processCommand(ICommandSender sender, String[] args) {

		if(args.length == 0) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
			return;
		}

		String type = args[0];
		double keepRadius = args.length > 1 ? parseDoubleWithMin(sender, args[1], 0D) : 64D;
		int keepFar = args.length > 2 ? parseIntBounded(sender, args[2], 0, 100000) : 0;

		WorldServer world = (WorldServer) sender.getEntityWorld();

		int matched = 0;
		int killed = 0;
		Map<String, Integer> nearby = new HashMap<String, Integer>();

		// 先收集远处候选，保留最近的 keepFar 个
		java.util.List<Entity> far = new java.util.ArrayList<Entity>();

		for(Object o : world.loadedEntityList) {
			if(!(o instanceof Entity)) continue;
			Entity e = (Entity) o;

			String name = e.getClass().getSimpleName();
			if(name.isEmpty()) name = e.getClass().getName();
			if(!name.equalsIgnoreCase(type) && !name.equalsIgnoreCase("Entity" + type)) continue;

			matched++;

			if(e instanceof EntityPlayer) continue;
			if(e.isDead) continue;

			// 命名牌 / 已驯服的不动
			if(e instanceof EntityLiving && ((EntityLiving) e).hasCustomNameTag()) continue;
			if(e instanceof net.minecraft.entity.passive.EntityTameable
					&& ((net.minecraft.entity.passive.EntityTameable) e).isTamed()) continue;

			double nearest = Double.MAX_VALUE;
			for(Object p : world.playerEntities) {
				EntityPlayer player = (EntityPlayer) p;
				double d = e.getDistanceSqToEntity(player);
				if(d < nearest) nearest = d;
			}

			if(nearest <= keepRadius * keepRadius) {
				String key = "近处";
				Integer c = nearby.get(key);
				nearby.put(key, c == null ? 1 : c + 1);
				continue;
			}

			far.add(e);
		}

		// keepFar 保留距离最近的若干个
		if(keepFar > 0 && far.size() > keepFar) {
			far.sort((a, b) -> Double.compare(nearestDistSq(a, world), nearestDistSq(b, world)));
			for(int i = keepFar; i < far.size(); i++) {
				far.get(i).setDead();
				killed++;
			}
		} else {
			for(Entity e : far) {
				e.setDead();
				killed++;
			}
		}

		String msg = "[cull] " + type + "：共 " + matched + " 个，清除 " + killed + " 个（保留半径 " + (int) keepRadius + " 格）";
		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + msg));
		try {
			com.hbm.main.MainRegistry.logger.info(msg);
		} catch(Throwable ignored) { }
	}

	private static double nearestDistSq(Entity e, WorldServer world) {
		double best = Double.MAX_VALUE;
		for(Object p : world.playerEntities) {
			double d = e.getDistanceSqToEntity((Entity) p);
			if(d < best) best = d;
		}
		return best;
	}
}
