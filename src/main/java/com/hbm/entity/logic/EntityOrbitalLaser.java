package com.hbm.entity.logic;

import com.hbm.blocks.ModBlocks;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IDropChanceMutator;
import com.hbm.explosion.vanillant.standard.BlockAllocatorPerBlock;
import com.hbm.explosion.vanillant.standard.BlockAllocatorStandard;
import com.hbm.explosion.vanillant.standard.BlockProcessorStandard;
import com.hbm.explosion.vanillant.standard.FortuneMutatorOre;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.ExplosionEffectWeapon;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.util.DamageResistanceHandler.DamageClass;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;


import java.util.HashSet;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;

public class EntityOrbitalLaser extends Entity {

	/**
	 * 光束存活时间（tick）。爆炸是在生成时立刻执行的，这个值只决定
	 * RenderOrbitalLaser 那根光柱能显示多久（0 = 当 tick 就死，画不出来）。
	 */
	public static final int maxAge = 5;

	/** 打出这一发的人（卫星是玩家指挥的）。用来把击杀算到玩家头上，从而吃到抢夺加成。 */
	public EntityLivingBase owner;

	/**
	 * 激光自带的抢夺等级：不需要玩家手里拿抢夺剑，掉落本身按这个等级结算。
	 * 玩家手上的抢夺更高时以玩家的为准（只补差额，不会叠加两次）。
	 */
	public static final int LASER_LOOTING = 100;

	/**
	 * 爆炸强度，也就是 ExplosionVNT 的 size。它同时决定三件事：
	 * 1. 每条破坏方块的射线初始能量 = size * BLOCK_POWER_MULT * (0.7 ~ 1.3)
	 * 2. 实体伤害半径 = size * 2（EntityProcessorCross 里 size *= 2.0F）
	 * 3. 方块掉落率 = 1 / size（BlockProcessorStandard）
	 */
	public static final float BLAST_SIZE = 5F;

	/**
	 * 方块穿透倍率：只放大破坏方块用的射线能量，不影响实体伤害半径。
	 *
	 * 每破一格方块的消耗 = 0.3 * (方块爆炸抗性 + 0.3)：
	 * 泥土 2.5 -> 0.84，石头 30 -> 9.09，黑曜石 2000 -> 600，基岩 1.8e7 -> 5.4e6。
	 * 有效能量 = BLAST_SIZE * BLOCK_POWER_MULT。注意：改成 3F 之前这里是 1 倍，
	 * 也就是有效能量只有 5，连一格石头（9.09）都破不了。
	 */
	public static final float BLOCK_POWER_MULT = 3*2.4F;

	/**
	 * 基岩的抗性倍率。基岩原版爆炸抗性是 1.8e7，乘这个倍率后变成 18，
	 * 也就是"比泥土(2.5)硬、比石头(30)软"。默认有效能量 15 时大约能往下啃 1 格；
	 * 想挖得更深就调大 BLOCK_POWER_MULT，或把这个倍率再调小。
	 */
	public static final float BEDROCK_RESISTANCE_MULT = 1F / 1_000_000F;

	/** 射线密度：16 -> 4096 条，32 -> 32768 条，越大弹坑越平滑、代价线性上升。 */
	public static final int BLOCK_RESOLUTION = 16;

	/** 炸矿时使用的时运等级（只对 OreDictionary 里 "ore" 开头的方块生效）。 */
	public static final int LASER_FORTUNE = 0;

	/** 矿石的掉落概率：1.0 = 必掉；默认的 1/size 会让 80% 的矿直接消失。 */
	public static final float ORE_DROP_CHANCE = 1.0F;

	public EntityOrbitalLaser(World world) {
		super(world);
		this.ignoreFrustumCheck = true;
	}

	@Override
	protected void entityInit() { }

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) { }
	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) { }

	@Override
	public void onUpdate() {

		if(this.ticksExisted >= maxAge && !worldObj.isRemote) this.setDead();
	}
	
	public void explode() {

		// 临时诊断：确认精密激光卫星的爆炸代码有没有被执行
		LogManager.getLogger("HBM").info("orbital laser explode at " + (int) posX + " " + (int) posY + " " + (int) posZ
				+ " remote=" + worldObj.isRemote);

		ExplosionVNT vnt = new ExplosionVNT(worldObj, posX, posY, posZ, BLAST_SIZE);
		// 只放大方块的穿透能量，实体伤害半径仍然按 BLAST_SIZE 算
		BlockAllocatorPerBlock allocator = new BlockAllocatorPerBlock(BLOCK_RESOLUTION) {
			@Override
			public HashSet<ChunkPosition> allocate(ExplosionVNT explosion, World world,
												   double x, double y, double z, float size) {
				return super.allocate(explosion, world, x, y, z, size * BLOCK_POWER_MULT);
			}
		};

		// —— 每个方块单独调伤害：改这张表就行 ——
		allocator.setMult(Blocks.bedrock, BEDROCK_RESISTANCE_MULT); // 基岩：抗性 1.8e7 -> 18，能挖
		allocator.setMult(Blocks.obsidian, 0.25F);     // 抗性 x0.25 -> 打黑曜石快四倍
		allocator.setMult(Blocks.stone, 20F);           // 抗性 x2   -> 石头更难打
		allocator.setMult(ModBlocks.brick_concrete, 3F);
		allocator.setDefaultMult(6*1.1f);                  // 没列出的方块统一倍率

		vnt.setBlockAllocator(allocator);
		// 矿石：必掉 + 时运；基岩：不掉基岩物品；其它方块保持原样（1/size 概率）
		final FortuneMutatorOre oreFortune = new FortuneMutatorOre(LASER_FORTUNE, ORE_DROP_CHANCE);

		vnt.setBlockProcessor(new BlockProcessorStandard()
				.withChance(new IDropChanceMutator() {
					@Override
					public float mutateDropChance(ExplosionVNT explosion, Block block, int x, int y, int z, float chance) {
						if(block == Blocks.bedrock) return 0F;    // 基岩不掉，否则等于刷石机
						return oreFortune.mutateDropChance(explosion, block, x, y, z, chance);
					}
				})
				.withFortune(oreFortune));
		vnt.setEntityProcessor(new EntityProcessorCrossSmooth(1, 1_000F).setupPiercing(50F, 0.5F).setDamageClass(DamageClass.LASER)
				.withAttacker(this.owner)
				.withLooting(LASER_LOOTING)
				.withNoExperience());
		vnt.setPlayerProcessor(new PlayerProcessorStandard());
		vnt.setSFX(new ExplosionEffectWeapon(15, 3.5F, 1.25F));
		vnt.explode();
	}

	@Override
	@SideOnly(Side.CLIENT)
	public int getBrightnessForRender(float p_70070_1_) {
		return 15728880;
	}

	@Override
	public float getBrightness(float p_70013_1_) {
		return 1.0F;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isInRangeToRenderDist(double distance) {
		return distance < 25000;
	}
}
