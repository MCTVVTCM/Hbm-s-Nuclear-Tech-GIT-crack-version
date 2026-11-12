package com.hbm.explosion.vanillant.standard;

import java.util.HashSet;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IBlockAllocator;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;

public class BlockAllocatorStandard implements IBlockAllocator {

	protected int resolution;

	public BlockAllocatorStandard() {
		this(16);
	}

	public BlockAllocatorStandard(int resolution) {
		this.resolution = resolution;
	}

	/**
	 * 单格方块消耗多少能量才算被这颗射线打穿。默认就是方块的爆炸抗性。
	 *
	 * 想“对每个方块单独调伤害”就覆写这个方法：返回越大越难炸，返回 Float.MAX_VALUE 等于完全免疫
	 * （射线会在这一格停下）。方法里拿得到方块、坐标和爆心，所以可以按方块类型、材质、高度甚至
	 * 距离写规则。
	 */
	protected float getBlockResistance(ExplosionVNT explosion, World world, int blockX, int blockY, int blockZ,
									   Block block, double originX, double originY, double originZ) {

		return explosion.exploder != null
				? explosion.exploder.func_145772_a(explosion.compat, world, blockX, blockY, blockZ, block)
				: block.getExplosionResistance(explosion.exploder, world, blockX, blockY, blockZ, originX, originY, originZ);
	}

	@Override
	public HashSet<ChunkPosition> allocate(ExplosionVNT explosion, World world, double x, double y, double z, float size) {

		HashSet<ChunkPosition> affectedBlocks = new HashSet();

		for(int i = 0; i < this.resolution; ++i) {
			for(int j = 0; j < this.resolution; ++j) {
				for(int k = 0; k < this.resolution; ++k) {
					
					if(i == 0 || i == this.resolution - 1 || j == 0 || j == this.resolution - 1 || k == 0 || k == this.resolution - 1) {
						
						double d0 = (double) ((float) i / ((float) this.resolution - 1.0F) * 2.0F - 1.0F);
						double d1 = (double) ((float) j / ((float) this.resolution - 1.0F) * 2.0F - 1.0F);
						double d2 = (double) ((float) k / ((float) this.resolution - 1.0F) * 2.0F - 1.0F);
						double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
						
						d0 /= d3;
						d1 /= d3;
						d2 /= d3;
						
						float powerRemaining = size * (0.7F + world.rand.nextFloat() * 0.6F);
						double currentX = x;
						double currentY = y;
						double currentZ = z;

						for(float stepSize = 0.3F; powerRemaining > 0.0F; powerRemaining -= stepSize * 0.75F) {
							
							int blockX = MathHelper.floor_double(currentX);
							int blockY = MathHelper.floor_double(currentY);
							int blockZ = MathHelper.floor_double(currentZ);
							
							Block block = world.getBlock(blockX, blockY, blockZ);

							if(block.getMaterial() != Material.air) {
								float blockResistance = getBlockResistance(explosion, world, blockX, blockY, blockZ, block, x, y, z);
								powerRemaining -= (blockResistance + 0.3F) * stepSize;
							}

							if(powerRemaining > 0.0F && (explosion.exploder == null || explosion.exploder.func_145774_a(explosion.compat, world, blockX, blockY, blockZ, block, powerRemaining))) {
								affectedBlocks.add(new ChunkPosition(blockX, blockY, blockZ));
							}

							currentX += d0 * (double) stepSize;
							currentY += d1 * (double) stepSize;
							currentZ += d2 * (double) stepSize;
						}
					}
				}
			}
		}
		
		return affectedBlocks;
	}
}
