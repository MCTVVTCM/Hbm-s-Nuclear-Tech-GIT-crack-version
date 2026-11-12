package com.hbm.commands;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Deletes dropped item entities around the player.
 *
 * 1.7.10 has no {@code /kill @e}, so a crater littered with thousands of drops (every
 * explosion with a BlockProcessorStandard spits out {@code 1/size} of the destroyed
 * blocks as items) can only be cleaned up by hand. Those items are also the main
 * reason such a crater runs at single digit FPS.
 */
public class CommandClearItems extends CommandBase {

	@Override
	public String getCommandName() {
		return "ntmclearitems";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return String.format(Locale.US, "%s/%s [radius] [orbs]%s - Removes dropped items around you (default radius 96, add 'orbs' to also wipe XP orbs)",
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
		double radius = args.length > 0 ? parseDoubleWithMin(sender, args[0], 1D) : 96D;
		boolean orbs = args.length > 1 && "orbs".equalsIgnoreCase(args[1]);

		AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
				player.posX - radius, player.posY - radius, player.posZ - radius,
				player.posX + radius, player.posY + radius, player.posZ + radius);

		int removed = 0;

		List items = player.worldObj.getEntitiesWithinAABB(EntityItem.class, box);
		for(Object o : items) {
			EntityItem item = (EntityItem) o;
			if(item.getDistanceSq(player.posX, player.posY, player.posZ) <= radius * radius) {
				item.setDead();
				removed++;
			}
		}

		int orbCount = 0;
		if(orbs) {
			List list = player.worldObj.getEntitiesWithinAABB(EntityXPOrb.class, box);
			for(Object o : list) {
				EntityXPOrb orb = (EntityXPOrb) o;
				if(orb.getDistanceSq(player.posX, player.posY, player.posZ) <= radius * radius) {
					orb.setDead();
					orbCount++;
				}
			}
		}

		ChatComponentText msg = new ChatComponentText(EnumChatFormatting.GREEN + "Removed " + removed + " item entities"
				+ (orbs ? " and " + orbCount + " XP orbs" : "") + " within " + (int) radius + " blocks.");
		sender.addChatMessage(msg);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public List addTabCompletionOptions(ICommandSender sender, String[] args) {
		if(args.length == 1) return getListOfStringsMatchingLastWord(args, "32", "64", "96", "128", "256");
		if(args.length == 2) return getListOfStringsMatchingLastWord(args, "orbs");
		return null;
	}
}
