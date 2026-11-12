package com.hbm.explosion.vanillant.standard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.hbm.explosion.vanillant.ExplosionVNT;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * 按方块类型调伤害的爆炸分配器。
 *
 * 默认直接继承 {@link BlockAllocatorStandard} 的能量射线算法，只是把“这一格要扣多少能量”
 * 换成了可配置的：
 *
 * <pre>
 * BlockAllocatorPerBlock allocator = new BlockAllocatorPerBlock(16);
 * allocator.setMult(Blocks.obsidian, 0.25F);   // 抗性 x0.25 -> 好打四倍
 * allocator.setMult(Blocks.stone, 2F);         // 抗性 x2    -> 难打一倍
 * allocator.setImmune(Blocks.bedrock);         // 完全免疫，射线停在这里
 * allocator.setDefaultMult(1.5F);              // 没列出来的方块统统 x1.5
 * </pre>
 *
 * 倍率改的是“抗性”，所以和 {@code size}（初始能量）是两个独立的旋钮：
 * 前者只影响指定方块，后者整体缩放。
 */
public class BlockAllocatorPerBlock extends BlockAllocatorStandard {

	/** 方块 -> 抗性倍率，>1 更难打，<1 更好打。 */
	protected final Map<Block, Float> resistanceMult = new HashMap<>();
	/** 完全免疫的方块。 */
	protected final Set<Block> immune = new HashSet<>();
	/** 没单独列出的方块使用的倍率。 */
	protected float defaultMult = 1F;

	public BlockAllocatorPerBlock() {
		super();
	}

	public BlockAllocatorPerBlock(int resolution) {
		super(resolution);
	}

	/** 单独调某个方块的抗性倍率。 */
	public BlockAllocatorPerBlock setMult(Block block, float mult) {
		this.resistanceMult.put(block, mult);
		return this;
	}

	/** 让某个方块完全免疫：射线打到它就停。 */
	public BlockAllocatorPerBlock setImmune(Block block) {
		this.immune.add(block);
		return this;
	}

	/** 没列出的方块统一乘这个倍率。 */
	public BlockAllocatorPerBlock setDefaultMult(float mult) {
		this.defaultMult = mult;
		return this;
	}

	@Override
	protected float getBlockResistance(ExplosionVNT explosion, World world, int blockX, int blockY, int blockZ,
									   Block block, double originX, double originY, double originZ) {

		if(this.immune.contains(block)) return Float.MAX_VALUE;

		float base = super.getBlockResistance(explosion, world, blockX, blockY, blockZ, block, originX, originY, originZ);
		Float mult = this.resistanceMult.get(block);
		return base * (mult == null ? this.defaultMult : mult);
	}
}
