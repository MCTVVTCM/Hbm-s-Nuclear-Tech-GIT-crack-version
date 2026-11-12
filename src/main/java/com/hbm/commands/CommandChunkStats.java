package com.hbm.commands;

import java.util.Locale;

import com.hbm.entity.misc.EntityJindouYun;
import com.hbm.util.ChunkStats;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * /ntmchunks —— 一行看完区块加载状况，按需查询，不写日志。
 *
 * 输出示例：
 *   [chunks] 总计 加载 12043 / 生成 218
 *   [chunks] 自上次查询 3.2s：加载 21.4/s，生成 2.1/s，预取挂起 0/48
 *
 * 判读方式：
 *   加载速率高、生成速率≈0 → 正在读已探索过的地形（正常，且应该很顺）
 *   生成速率 > 0            → 正在开新地形，这是主线程同步生成，掉速就是它
 *   预取挂起长期顶到上限     → IO 跟不上，或者主线程插世界被压住了
 */
public class CommandChunkStats extends CommandBase {

	@Override
	public String getCommandName() {
		return "ntmchunks";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/ntmchunks";
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) {

		ChunkStats.sample();

		sender.addChatMessage(new ChatComponentText(
				EnumChatFormatting.GRAY + "[chunks] " + EnumChatFormatting.WHITE
				+ "总计 加载 " + ChunkStats.loadedTotal
				+ " / 生成 " + ChunkStats.generatedTotal));

		String rate = ChunkStats.loadRate < 0
				? EnumChatFormatting.GRAY + "（再执行一次看速率）"
				: EnumChatFormatting.WHITE + String.format(Locale.US, "%.1f/s", ChunkStats.loadRate)
				  + EnumChatFormatting.GRAY + "，生成 " + EnumChatFormatting.WHITE
				  + String.format(Locale.US, "%.1f/s", ChunkStats.generateRate);

		sender.addChatMessage(new ChatComponentText(
				EnumChatFormatting.GRAY + "[chunks] " + EnumChatFormatting.WHITE
				+ "预算 " + EntityJindouYun.PREFETCH_BUDGET + "/tick"
				+ EnumChatFormatting.GRAY + "，预取 " + EnumChatFormatting.WHITE
				+ EntityJindouYun.getPrefetchOutstanding() + "/" + EntityJindouYun.PREFETCH_MAX_OUTSTANDING
				+ EnumChatFormatting.GRAY + "，速度 " + EnumChatFormatting.WHITE
				+ EntityJindouYun.SPEED + " 格/tick"
				+ EnumChatFormatting.GRAY + "，速率 " + rate));

		return;
	}
}
