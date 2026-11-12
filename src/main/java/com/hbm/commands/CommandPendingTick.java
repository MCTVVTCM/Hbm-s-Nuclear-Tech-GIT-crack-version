package com.hbm.commands;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;

/**
 * /ntmpending —— 看看世界的"待处理方块更新"队列有多大、都是什么方块在排队。
 *
 * 为什么需要它：
 *   原版保存区块时会对每个区块调用 WorldServer.getPendingBlockUpdates()，
 *   而这个方法要遍历整个 pendingTickListEntriesTreeSet。实测退出游戏时会在
 *   AnvilChunkLoader.writeChunkToNBT 里卡十几分钟 —— 队列越大越慢，是 O(区块数 × 队列长度)。
 *
 * 这个命令直接读出队列长度，并按方块类型统计，用来找到"谁在疯狂 scheduleBlockUpdate"。
 *
 * 判读：
 *   几万条以内   → 正常
 *   几十万条     → 有东西在刷更新，退出保存会很慢
 *   上百万条     → 必须处理，否则保存基本卡死
 */
public class CommandPendingTick extends CommandBase {

	private static Field pendingSetField;
	private static Field pendingThisTickField;
	private static Field blockField;
	private static boolean lookupDone;

	@Override
	public String getCommandName() {
		return "ntmpending";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/ntmpending  —— 输出待处理方块更新队列长度与来源方块的 Top 10";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void processCommand(ICommandSender sender, String[] args) {

		if(!(sender.getEntityWorld() instanceof WorldServer)) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[pending] 只能在服务端世界执行"));
			return;
		}

		WorldServer world = (WorldServer) sender.getEntityWorld();
		lookup();

		// /ntmpending purge <方块名>  —— 清掉指定方块的所有待处理更新
		// /ntmpending purge all      —— 全部清掉
		// 安全性：这些方块被玩家/爆炸/流体再次扰动时会自动重新排程，
		// 代价只是"原本就在排队的那一次 tick"被跳过。
		if(args.length > 0 && "purge".equalsIgnoreCase(args[0])) {
			purge(sender, world, args.length > 1 ? args[1] : "all");
			return;
		}

		if(pendingSetField == null) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[pending] 找不到待处理更新集合（字段名变了）"));
			return;
		}

		int total = 0;
		Map<String, Integer> counts = new HashMap<String, Integer>();

		try {
			Object value = pendingSetField.get(world);
			if(value instanceof Iterable) {
				for(Object o : (Iterable<Object>) value) {
					if(!(o instanceof NextTickListEntry)) continue;
					total++;
					String name = blockName((NextTickListEntry) o);
					Integer c = counts.get(name);
					counts.put(name, c == null ? 1 : c + 1);
				}
			}
		} catch(Throwable t) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[pending] 读取失败: " + t));
			return;
		}

		int thisTick = 0;
		try {
			if(pendingThisTickField != null) {
				Object v = pendingThisTickField.get(world);
				if(v instanceof java.util.Collection) thisTick = ((java.util.Collection<?>) v).size();
			}
		} catch(Throwable ignored) { }

		EnumChatFormatting color = total > 500000 ? EnumChatFormatting.RED
				: total > 100000 ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN;

		String head = "[pending] 待处理方块更新 " + total + " 条（本 tick " + thisTick + " 条），"
				+ "保存时每块都要扫这个表";
		sender.addChatMessage(new ChatComponentText(color + head));

		List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());

		StringBuilder log = new StringBuilder("[pending] total=" + total + " thisTick=" + thisTick + " | ");
		int shown = 0;
		for(Map.Entry<String, Integer> e : sorted) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY
					+ "  " + e.getKey() + " x" + e.getValue()));
			log.append(e.getKey()).append('=').append(e.getValue()).append(' ');
			if(++shown >= 10) break;
		}

		try {
			com.hbm.main.MainRegistry.logger.info(log.toString());
		} catch(Throwable ignored) { }
	}

	@SuppressWarnings("unchecked")
	private static void purge(ICommandSender sender, WorldServer world, String what) {

		if(pendingSetField == null) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[pending] 找不到队列字段"));
			return;
		}

		boolean all = "all".equalsIgnoreCase(what);
		int removed = 0;

		try {
			Object value = pendingSetField.get(world);
			if(value instanceof Set) {
				java.util.Iterator<Object> it = ((Set<Object>) value).iterator();
				while(it.hasNext()) {
					Object o = it.next();
					if(!(o instanceof NextTickListEntry)) continue;
					if(all || what.equalsIgnoreCase(blockName((NextTickListEntry) o))) {
						it.remove();
						removed++;
					}
				}
			}
		} catch(Throwable t) {
			sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[pending] 清理失败: " + t));
			return;
		}

		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN
				+ "[pending] 已清除 " + removed + " 条（" + (all ? "全部" : what) + "）"));
		try {
			com.hbm.main.MainRegistry.logger.info("[pending] purge " + what + " removed=" + removed
					+ " left=" + (pendingSetField.get(world) instanceof java.util.Collection
							? ((java.util.Collection<?>) pendingSetField.get(world)).size() : -1));
		} catch(Throwable ignored) { }
	}

	private static String blockName(NextTickListEntry entry) {
		try {
			if(blockField == null) return "?";
			Object b = blockField.get(entry);
			if(b instanceof Block) {
				String s = ((Block) b).getUnlocalizedName();
				return s == null ? "?" : s;
			}
		} catch(Throwable ignored) { }
		return "?";
	}

	private static void lookup() {

		if(lookupDone) return;
		lookupDone = true;

		try {
			for(Field f : WorldServer.class.getDeclaredFields()) {
				if(TreeSet.class.isAssignableFrom(f.getType()) && pendingSetField == null) {
					f.setAccessible(true);
					pendingSetField = f;
				} else if(List.class.isAssignableFrom(f.getType()) && pendingThisTickField == null) {
					f.setAccessible(true);
					pendingThisTickField = f;
				}
			}
		} catch(Throwable ignored) { }

		try {
			for(Field f : NextTickListEntry.class.getDeclaredFields()) {
				if(Block.class.isAssignableFrom(f.getType())) {
					f.setAccessible(true);
					blockField = f;
					break;
				}
			}
		} catch(Throwable ignored) { }
	}
}
