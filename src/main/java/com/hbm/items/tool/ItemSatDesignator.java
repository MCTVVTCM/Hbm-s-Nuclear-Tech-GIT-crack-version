package com.hbm.items.tool;

import com.hbm.blocks.ModBlocks;
import com.hbm.items.machine.ItemSatChip;
import com.hbm.lib.Library;
import com.hbm.saveddata.SatelliteSavedData;
import com.hbm.saveddata.satellites.SatelliteBase;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/**
 * 卫星指示器。
 *
 * 按住右键持续开火：每隔 {@link #FIRE_INTERVAL} tick 对当前准星指向的方块打一发，
 * 拖鼠标扫过去就是一整条连续的打击线。松手即停。
 */
public class ItemSatDesignator extends ItemSatChip {

	/** 连续开火的间隔（tick）。卫星自己的 CHARGE_TIME 也会再挡一次，取两者较大值。 */
	public static final int FIRE_INTERVAL = 2;

	/** 手写笔尖半径（格）。0.8 -> 约 2 格宽的笔画。 */
	public static final float PEN_RADIUS = 0.8F;
	/** 手写时往下烧穿几格。 */
	public static final int PEN_DEPTH = 2;
	/** 一次 tick 内最多补多少个点（准星甩太快时的插值上限）。 */
	public static final int PEN_MAX_STEPS = 32;

	private static final int USE_DURATION = 72000;

	@Override
	public int getMaxItemUseDuration(ItemStack stack) {
		return USE_DURATION;
	}

	@Override
	public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {

		// 潜行 = 手写笔（细笔画），普通 = 原来的卫星打击
		if(!world.isRemote) {
			if(player.isSneaking()) {
				this.pen(stack, world, player);
			} else {
				this.fire(stack, world, player);
			}
		}

		player.setItemInUse(stack, USE_DURATION);
		return stack;
	}

	@Override
	public void onUsingTick(ItemStack stack, EntityPlayer player, int count) {

		if(player.worldObj.isRemote) return;

		// count 是剩余使用时间，用它取模就是"每 FIRE_INTERVAL tick 一发"
		if(count % FIRE_INTERVAL == 0) {
			if(player.isSneaking()) {
				this.pen(stack, player.worldObj, player);
			} else {
				this.fire(stack, player.worldObj, player);
			}
		}
	}

	/**
	 * 手写笔：把准星指到的地面烧出一段细笔画。
	 *
	 * 每 FIRE_INTERVAL tick 取一次准星落点，和上一次的落点之间按 1 格步长补点，
	 * 所以快速扫过去也是一条连贯的线，而不是一串断开的点。
	 */
	private void pen(ItemStack stack, World world, EntityPlayer player) {

		// 笔也需要卫星：频率对不上（没发射/没部署）就画不出来
		SatelliteBase sat = SatelliteSavedData.getData(world).getSatFromFreq(this.getFreq(stack));
		if(sat == null) return;

		MovingObjectPosition pos = Library.rayTrace(player, 300, 1);
		if(pos == null) return;

		NBTTagCompound data = player.getEntityData();
		boolean hasLast = data.getBoolean("ntmPenSet");
		int lastX = data.getInteger("ntmPenX");
		int lastY = data.getInteger("ntmPenY");
		int lastZ = data.getInteger("ntmPenZ");

		int steps = 1;
		if(hasLast) {
			double dx = pos.blockX - lastX;
			double dy = pos.blockY - lastY;
			double dz = pos.blockZ - lastZ;
			steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz)));
		}
		steps = Math.min(steps, PEN_MAX_STEPS);

		for(int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			int x = (int) Math.round(lastX + (pos.blockX - lastX) * t);
			int y = (int) Math.round(lastY + (pos.blockY - lastY) * t);
			int z = (int) Math.round(lastZ + (pos.blockZ - lastZ) * t);

			if(!hasLast && i == 1) { x = pos.blockX; y = pos.blockY; z = pos.blockZ; }
			carveDot(world, x, y, z);
		}

		data.setBoolean("ntmPenSet", true);
		data.setInteger("ntmPenX", pos.blockX);
		data.setInteger("ntmPenY", pos.blockY);
		data.setInteger("ntmPenZ", pos.blockZ);
	}

	/** 在一个点上烧出一个小小的圆坑。 */
	private static void carveDot(World world, int cx, int cy, int cz) {

		int r = (int) Math.ceil(PEN_RADIUS);
		int r2 = (int) (PEN_RADIUS * PEN_RADIUS + 0.5F);

		for(int dx = -r; dx <= r; dx++) {
			for(int dz = -r; dz <= r; dz++) {
				if(dx * dx + dz * dz > r2) continue;

				for(int depth = 0; depth < PEN_DEPTH; depth++) {
					int y = cy - depth;
					if(y < 1) break;

					Block block = world.getBlock(cx + dx, y, cz + dz);
					if(block == Blocks.air) continue;

					// 最底下一层留矿渣，看起来像烧灼痕迹
					if(depth == PEN_DEPTH - 1) {
						world.setBlock(cx + dx, y, cz + dz, ModBlocks.block_slag);
					} else {
						world.setBlockToAir(cx + dx, y, cz + dz);
					}
				}
			}
		}
	}

	private void fire(ItemStack stack, World world, EntityPlayer player) {

		SatelliteBase sat = SatelliteSavedData.getData(world).getSatFromFreq(this.getFreq(stack));
		if(sat == null) return;

		MovingObjectPosition pos = Library.rayTrace(player, 300, 1);
		if(pos == null) return; // 看天：没有目标

		// 以前这里会沿点击面再外移一格（hit + sideHit），等于永远偏一个方块；
		// 而且 y 还被抬到方块上方，激光卫星又会把它丢掉重新贴地表，
		// 结果就是"瞄准 A 方块，炸 B 位置"。这里直接交命中方块本身。
		sat.onCoordAction(world, player, pos.blockX, pos.blockY, pos.blockZ);
	}
}
