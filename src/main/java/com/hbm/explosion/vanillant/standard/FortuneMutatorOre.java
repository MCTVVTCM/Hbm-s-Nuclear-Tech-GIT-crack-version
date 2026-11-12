package com.hbm.explosion.vanillant.standard;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IDropChanceMutator;
import com.hbm.explosion.vanillant.interfaces.IFortuneMutator;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 只对矿石生效的"时运"和"掉落率"修改器。
 *
 * {@link BlockProcessorStandard} 默认的掉落率是 {@code 1 / explosion.size}（size=5 时只有 20%），
 * 而且完全不带时运。爆炸炸矿时这两种都很亏：煤、钻石、红石这类矿石 80% 直接消失。
 *
 * 这个类同时实现 {@link IFortuneMutator} 和 {@link IDropChanceMutator}，所以两个方法可以共用一个实例：
 *
 * <pre>
 * FortuneMutatorOre fortune = new FortuneMutatorOre(3, 1.0F);
 * vnt.setBlockProcessor(new BlockProcessorStandard().withChance(fortune).withFortune(fortune));
 * </pre>
 *
 * 非矿石方块保持原来的掉落率和不带时运的行为，不会被这个改动影响。
 */
public class FortuneMutatorOre implements IFortuneMutator, IDropChanceMutator {

	protected final int fortuneLevel;
	protected final float oreChance;

	/**
	 * @param fortuneLevel 矿石使用的时运等级（0 = 不带时运）
	 * @param oreChance    矿石的掉落概率（1.0 = 必掉）
	 */
	public FortuneMutatorOre(int fortuneLevel, float oreChance) {
		this.fortuneLevel = Math.max(0, fortuneLevel);
		this.oreChance = Math.max(0F, Math.min(1F, oreChance));
	}

	@Override
	public int mutateFortune(ExplosionVNT explosion, Block block, int x, int y, int z) {
		return isOre(block) ? this.fortuneLevel : 0;
	}

	@Override
	public float mutateDropChance(ExplosionVNT explosion, Block block, int x, int y, int z, float chance) {
		return isOre(block) ? this.oreChance : chance;
	}

	/**
	 * 用 OreDictionary 判断是不是矿石：注册名以 "ore" 开头（oreIron、oreGold、oreUranium…），
	 * vanilla 和绝大多数模组的矿石都遵守这个命名。
	 */
	public static boolean isOre(Block block) {

		if(block == null) return false;

		int[] ids = OreDictionary.getOreIDs(new ItemStack(block));
		for(int id : ids) {
			String name = OreDictionary.getOreName(id);
			if(name != null && name.startsWith("ore")) return true;
		}

		return false;
	}
}
