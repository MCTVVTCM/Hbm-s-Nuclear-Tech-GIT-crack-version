package com.hbm.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Prints the entity types around the player, most numerous first.
 *
 * Useful to tell "the client is dying because of ten thousand item entities" apart from
 * "something is spawning entities every tick", without installing Opis.
 */
public class CommandEntityStats extends CommandBase {

	@Override
	public String getCommandName() {
		return "ntmcount";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return String.format(Locale.US, "%s/%s [radius]%s - Lists entity types around you, most numerous first (default radius 96)",
				EnumChatFormatting.GREEN, getCommandName(), EnumChatFormatting.LIGHT_PURPLE);
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	@SuppressWarnings("rawtypes")
	public void processCommand(ICommandSender sender, String[] args) {

		EntityPlayer player = getCommandSenderAsPlayer(sender);

		// /ntmcount all —— 统计整个世界所有已加载实体（不限距离），用来找泄漏源
		if(args.length > 0 && "all".equalsIgnoreCase(args[0])) {
			countAll(sender, player);
			return;
		}

		double radius = args.length > 0 ? parseDoubleWithMin(sender, args[0], 1D) : 96D;

		AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
				player.posX - radius, player.posY - radius, player.posZ - radius,
				player.posX + radius, player.posY + radius, player.posZ + radius);

		List list = player.worldObj.getEntitiesWithinAABB(Entity.class, box);

		HashMap<String, Integer> counts = new HashMap<String, Integer>();
		int total = 0;

		for(Object o : list) {
			Entity e = (Entity) o;
			if(e.getDistanceSq(player.posX, player.posY, player.posZ) > radius * radius) continue;
			String name = e.getClass().getSimpleName();
			if(name.isEmpty()) name = e.getClass().getName();
			Integer c = counts.get(name);
			counts.put(name, c == null ? 1 : c + 1);
			total++;
		}

		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "Entities within " + (int) radius + " blocks: "
				+ EnumChatFormatting.WHITE + total + EnumChatFormatting.GOLD
				+ "  (loaded in world: " + player.worldObj.loadedEntityList.size() + ")"));

		List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());

		int shown = 0;
		for(Map.Entry<String, Integer> entry : sorted) {
			EnumChatFormatting color = entry.getValue() > 500 ? EnumChatFormatting.RED
					: entry.getValue() > 100 ? EnumChatFormatting.YELLOW : EnumChatFormatting.GRAY;
			sender.addChatMessage(new ChatComponentText(color + "  " + entry.getKey() + " x" + entry.getValue()));
			if(++shown >= 12) break;
		}
	}

	/**
	 * 全世界已加载实体的分类统计。
	 * 15,000 个实体摊在 1,300 个区块里，用 128 格半径是看不见的 —— 必须全量统计才能找到泄漏源。
	 * 结果同时写服务端日志，方便事后核对。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void countAll(ICommandSender sender, EntityPlayer player) {

		HashMap<String, Integer> counts = new HashMap<String, Integer>();
		int total = 0;

		for(Object o : player.worldObj.loadedEntityList) {
			Entity e = (Entity) o;

			String name = e.getClass().getSimpleName();
			if(name.isEmpty()) name = e.getClass().getName();

			Integer c = counts.get(name);
			counts.put(name, c == null ? 1 : c + 1);
			total++;
		}

		List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());

		StringBuilder log = new StringBuilder("[ntmcount] all loaded=" + total + " | ");
		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD
				+ "All loaded entities: " + EnumChatFormatting.WHITE + total));

		int shown = 0;
		for(Map.Entry<String, Integer> entry : sorted) {
			EnumChatFormatting color = entry.getValue() > 500 ? EnumChatFormatting.RED
					: entry.getValue() > 100 ? EnumChatFormatting.YELLOW : EnumChatFormatting.GRAY;
			sender.addChatMessage(new ChatComponentText(color + "  " + entry.getKey() + " x" + entry.getValue()));
			log.append(entry.getKey()).append('=').append(entry.getValue()).append(' ');
			if(++shown >= 20) break;
		}

		try {
			com.hbm.main.MainRegistry.logger.info(log.toString());
		} catch(Throwable ignored) { }
	}
}
