package com.hbm.commands;

import java.util.Locale;

import com.hbm.blocks.ModBlocks;
import com.hbm.entity.logic.EntityOrbitalLaser;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

/**
 * Burn text into the ground with the orbital laser.
 *
 * <pre>
 * /ntmtext &lt;text&gt; [x y z] [pixelSize] [carve|burn]
 * </pre>
 *
 * - 没给坐标时以执行者脚下为起点，自动贴合地形高度
 * - pixelSize 是一个"像素"占多少格（默认 2），字母高 7 像素
 * - carve（默认）：把字母像素位置的方块清掉，坑底铺矿渣，看起来像激光烧灼
 * - burn：对每个像素真的打一发 EntityOrbitalLaser（很贵，字别太长）
 *
 * 字母沿 +X 排开，每一行沿 +Z 往下，5x7 像素字体。
 */
public class CommandTextLaser extends CommandBase {

	private static final int GLYPH_W = 5;
	private static final int GLYPH_H = 7;

	@Override
	public String getCommandName() {
		return "ntmtext";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return EnumChatFormatting.GREEN + "/ntmtext <text> [x y z] [pixelSize] [carve|burn]"
				+ EnumChatFormatting.LIGHT_PURPLE + " - 用轨道激光在地面上写字（'_' 当空格）";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) {

		if(args.length < 1) throw new WrongUsageException(getCommandUsage(sender));

		World world = sender.getEntityWorld();
		String text = args[0].toUpperCase(Locale.US).replace('_', ' ');

		int index = 1;
		int x, y, z;

		if(args.length >= 4 && isNumber(args[1]) && isNumber(args[2]) && isNumber(args[3])) {
			x = (int) parseDouble(sender, args[1]);
			y = (int) parseDouble(sender, args[2]);
			z = (int) parseDouble(sender, args[3]);
			index = 4;
		} else if(sender instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) sender;
			x = (int) Math.floor(player.posX);
			z = (int) Math.floor(player.posZ);
			y = world.getHeightValue(x, z);
		} else {
			throw new WrongUsageException("控制台执行时必须给出坐标: " + getCommandUsage(sender));
		}

		int pixel = 2;
		if(index < args.length) pixel = parseIntBounded(sender, args[index++], 1, 16);

		boolean burn = false;
		if(index < args.length) {
			String mode = args[index];
			if(mode.equalsIgnoreCase("burn")) burn = true;
			else if(!mode.equalsIgnoreCase("carve")) throw new WrongUsageException("未知模式: " + mode);
		}

		int placed = stamp(world, text, x, y, z, pixel, burn);

		sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[NTM] "
				+ EnumChatFormatting.RESET + "在 " + x + " " + y + " " + z + " 写字 \"" + text + "\"："
				+ placed + " 个像素点，" + (burn ? "爆裂模式" : "烧灼模式")));
	}

	/** 在世界上盖章，返回处理了多少个像素点。 */
	private int stamp(World world, String text, int originX, int originY, int originZ, int pixel, boolean burn) {

		int placed = 0;
		int cursorX = originX;

		for(int ci = 0; ci < text.length(); ci++) {

			int[] glyph = glyph(text.charAt(ci));
			if(glyph == null) {
				cursorX += (GLYPH_W + 1) * pixel;
				continue;
			}

			for(int row = 0; row < GLYPH_H; row++) {
				int bits = glyph[row];

				for(int col = 0; col < GLYPH_W; col++) {
					if((bits & (1 << (GLYPH_W - 1 - col))) == 0) continue;

					for(int dx = 0; dx < pixel; dx++) {
						for(int dz = 0; dz < pixel; dz++) {
							int x = cursorX + col * pixel + dx;
							int z = originZ + row * pixel + dz;

							// 贴合地形：每个像素都取自己那一列的地表
							int top = world.getHeightValue(x, z) - 1;
							if(top < 1 || top > 254) continue;

							if(burn) {
								EntityOrbitalLaser laser = new EntityOrbitalLaser(world);
								laser.posX = x + 0.5;
								laser.posY = top + 1.5;
								laser.posZ = z + 0.5;
								laser.explode();
							} else {
								// 表层挖掉，底下一层换成矿渣，看起来像被激光烧穿
								world.setBlockToAir(x, top, z);
								int below = top - 1;
								if(below > 0 && world.getBlock(x, below, z) != Blocks.air) {
									world.setBlock(x, below, z, ModBlocks.block_slag);
								}
							}
							placed++;
						}
					}
				}
			}

			cursorX += (GLYPH_W + 1) * pixel;
		}

		return placed;
	}

	private static boolean isNumber(String s) {
		try {
			Double.parseDouble(s);
			return true;
		} catch(NumberFormatException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------
	// 5x7 像素字体，每行 5 bit，从最高位往低位是左到右
	// ------------------------------------------------------------------
	private static final int[][] FONT = new int[128][];
	private static final int[] SPACE = new int[]{0, 0, 0, 0, 0, 0, 0};

	static {
		FONT[' '] = SPACE;
		FONT['A'] = new int[]{0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
		FONT['B'] = new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110};
		FONT['C'] = new int[]{0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110};
		FONT['D'] = new int[]{0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110};
		FONT['E'] = new int[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111};
		FONT['F'] = new int[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000};
		FONT['G'] = new int[]{0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111};
		FONT['H'] = new int[]{0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
		FONT['I'] = new int[]{0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
		FONT['J'] = new int[]{0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100};
		FONT['K'] = new int[]{0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001};
		FONT['L'] = new int[]{0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111};
		FONT['M'] = new int[]{0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001};
		FONT['N'] = new int[]{0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001};
		FONT['O'] = new int[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
		FONT['P'] = new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000};
		FONT['Q'] = new int[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101};
		FONT['R'] = new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001};
		FONT['S'] = new int[]{0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110};
		FONT['T'] = new int[]{0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100};
		FONT['U'] = new int[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
		FONT['V'] = new int[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100};
		FONT['W'] = new int[]{0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001};
		FONT['X'] = new int[]{0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001};
		FONT['Y'] = new int[]{0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100};
		FONT['Z'] = new int[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111};
		FONT['0'] = new int[]{0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110};
		FONT['1'] = new int[]{0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
		FONT['2'] = new int[]{0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111};
		FONT['3'] = new int[]{0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110};
		FONT['4'] = new int[]{0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010};
		FONT['5'] = new int[]{0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110};
		FONT['6'] = new int[]{0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110};
		FONT['7'] = new int[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000};
		FONT['8'] = new int[]{0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110};
		FONT['9'] = new int[]{0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100};
		FONT['-'] = new int[]{0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000};
		FONT['.'] = new int[]{0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100};
		FONT['!'] = new int[]{0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100};
	}

	private static int[] glyph(char c) {
		if(c >= 128) return null;
		int[] g = FONT[c];
		return g == null ? null : g;
	}
}
