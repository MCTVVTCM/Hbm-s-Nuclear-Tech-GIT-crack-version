package com.hbm.items.special;

import java.util.List;

import com.hbm.items.ISatChip;
import com.hbm.items.ItemEnumMulti;
import com.hbm.util.i18n.I18nUtil;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;

public class ItemSatellite extends ItemEnumMulti implements ISatChip {

	public ItemSatellite() {
		super(EnumSatType.class, true, true);
	}

	public static enum EnumSatType {
		SPY,
		SCANNER,
		RADAR,
		MINER_ASTRO,
		MINER_LUNAR,
		PRECISION_LASER,
		DEATH_RAY,
		XENIUM_RESONATOR,
		RELAY,
		DETECTOR,
		RAY_SCAN,
		SCIENCE,
		SCIENCE_ASSEMBLER,
		SCIENCE_SENSOR,
		ASAT,
		ASAT_AMMO,
		/** 轨道区块挖掘机：清空一个区块的整列，方块按正常破坏掉落成物品 */
		CHUNK_MINER,
	}

	@Override
	@SideOnly(Side.CLIENT)
	public IIcon getIconFromDamage(int meta) {
		if(meta == EnumSatType.CHUNK_MINER.ordinal()) return super.getIconFromDamage(EnumSatType.PRECISION_LASER.ordinal());
		return super.getIconFromDamage(meta);
	}

	@Override
	public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean bool) {
		list.add(EnumChatFormatting.AQUA + I18nUtil.resolveKey("satchip.frequency") + ": " + getFreq(stack));
	}
}
