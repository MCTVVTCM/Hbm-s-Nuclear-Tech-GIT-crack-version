package com.hbm.saveddata.satellites;

import java.util.Locale;

import com.hbm.entity.logic.EntityDeathBlast;
import com.hbm.entity.logic.EntityOrbitalLaser;
import com.hbm.items.ModItems;
import com.hbm.items.special.ItemSatellite.EnumSatType;

import org.apache.logging.log4j.LogManager;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

public class SatelliteDeathRay extends SatelliteBase {
	
	public static final String CMD_FIRE = "fire";
	public static final String CMD_CANFIRE = "canfire";
	
	public static final int CHARGE_TIME = 0 ;
	
	public long lastShot;
	
	public SatelliteDeathRay() { }

	@Override public String getType() { return "ORBITAL_FUN_PLATFORM_:)"; }
	
	@Override
	public IChatComponent[] getInfo(World world) {
		
		boolean canFire = lastShot + CHARGE_TIME < world.getTotalWorldTime();
		int cooldown = (int) ((lastShot + CHARGE_TIME) - world.getTotalWorldTime());
		
		return new IChatComponent[] {
				new ChatComponentTranslation(ModItems.satellite.getUnlocalizedName(new ItemStack(ModItems.satellite, 1, EnumSatType.DEATH_RAY.ordinal())) + ".name"),
				canFire ? new ChatComponentTranslation("satellite.ready") : new ChatComponentTranslation("satellite.cooldown", cooldown / 20 + "s"),
		};
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("lastShot", lastShot);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		lastShot = nbt.getLong("lastShot");
	}

	@Override
	public void onCommandImpl(World world, String... cmd) {
		if(cmd.length <= 0) return;
		
		if(cmd[0].equals(CMD_FIRE)) {
			deathBlast(world, targetX, targetZ);
			return;
		}
		
		if(cmd[0].equals(CMD_CANFIRE)) {
			this.tx = (lastShot + CHARGE_TIME < world.getTotalWorldTime()) + "";
			this.tx = this.tx.toUpperCase(Locale.US);
			return;
		}
	}

	@Override
	public void onCoordAction(World world, EntityPlayer player, int x, int y, int z) {
		this.setTarget(x, z);
		this.deathBlast(world, x, y, z);
	}

	/** 没有明确 y（雷达/红石链路，y = -1）时退回地表。 */
	public void deathBlast(World world, int x, int z) {
		deathBlast(world, x, -1, z);
	}

	public void deathBlast(World world, int x, int y, int z) {
		if(y < 0 || y >= 256) y = world.getHeightValue(x, z);

		// 临时诊断：确认这个入口有没有被调用、是不是被冷却挡住、是不是跑在客户端
		LogManager.getLogger("HBM").info("deathBlast() x=" + x + " z=" + z
				+ " remote=" + world.isRemote
				+ " cooldownOk=" + (lastShot + CHARGE_TIME < world.getTotalWorldTime())
				+ " lastShot=" + lastShot + " now=" + world.getTotalWorldTime());

		if(lastShot + CHARGE_TIME < world.getTotalWorldTime()) {
			lastShot = world.getTotalWorldTime();
			// this.markDirty();

			EntityDeathBlast blast = new EntityDeathBlast(world);
			blast.posX = x + 0.5;
			blast.posY = y;
			blast.posZ = z + 0.5;

			world.spawnEntityInWorld(blast);

			// 死光本身只有一下球形闪光，扫射时看不出"线"；补一根光柱，
			// 和精密激光共用 RenderOrbitalLaser，连续开火时相邻光柱会叠成一片。
			EntityOrbitalLaser beam = new EntityOrbitalLaser(world);
			beam.setPosition(x + 0.5, y, z + 0.5);
			world.spawnEntityInWorld(beam);
		}
	}
}
