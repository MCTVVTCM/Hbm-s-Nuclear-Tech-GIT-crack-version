package com.hbm.saveddata.satellites;

import java.util.Locale;

import com.hbm.entity.logic.EntityOrbitalLaser;
import com.hbm.items.ModItems;
import com.hbm.items.special.ItemSatellite.EnumSatType;

import api.hbm.redstoneoverradio.IRORInteractive;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import org.apache.logging.log4j.LogManager;
import net.minecraft.world.World;

public class SatellitePrecisionLaser extends SatelliteBase {
	
	public static final String CMD_FIRE = "fire";
	public static final String CMD_CANFIRE = "canfire";
	public static final String CMD_SETENTITYTARGET = "setentitytarget";
	
	public static final int MAX_TARGET_RANGE = 1_000;
	public static final int CHARGE_TIME = 0;
	
	public long lastShot;
	public int targetedEntity = -1;
	
	public SatellitePrecisionLaser() { }

	@Override public String getType() { return "ORBITAL_TATOO_REMOVER"; }
	
	@Override
	public IChatComponent[] getInfo(World world) {
		//control ground  station
		boolean canFire = lastShot + CHARGE_TIME < world.getTotalWorldTime();
		int cooldown = (int) ((lastShot + CHARGE_TIME) - world.getTotalWorldTime());
		
		return new IChatComponent[] {
				new ChatComponentTranslation(ModItems.satellite.getUnlocalizedName(new ItemStack(ModItems.satellite, 1, EnumSatType.PRECISION_LASER.ordinal())) + ".name"),
				canFire ? new ChatComponentTranslation("satellite.ready")
						: new ChatComponentTranslation("satellite.cooldown", String.format(Locale.US, "%.1fs", cooldown / 20F)),
		};
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("lastShot", lastShot);
		nbt.setInteger("targetedEntity", targetedEntity);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		lastShot = nbt.getLong("lastShot");
		targetedEntity = nbt.getInteger("targetedEntity");
	}

	@Override
	public void onCommandImpl(World world, String... cmd) {
		if(cmd.length <= 0) return;
		
		if(cmd[0].equals(CMD_FIRE)) {
			
			if(this.targetedEntity != -1) {
				Entity e = world.getEntityByID(this.targetedEntity);
				this.targetedEntity = -1;
				
				if(e == null || e.isDead) return;
				
				int x = (int) Math.floor(e.posX);
				int z = (int) Math.floor(e.posZ);
				
				double dX = x - targetX;
				double dZ = z - targetZ;
				
				if(dX * dX + dZ * dZ <= MAX_TARGET_RANGE * MAX_TARGET_RANGE) {
					// minor offset for slight damage variation and for skirting around an issue with ExVNT
					double offX = world.rand.nextDouble() * 0.05 - 0.025;
					double offY = world.rand.nextDouble() * 0.05 - 0.025;
					double offZ = world.rand.nextDouble() * 0.05 - 0.025;
					this.deathBlast(world, e.posX + offX, e.posY + offY, e.posZ + offZ);
					return;
				}
			}
			
			deathBlast(world, targetX, targetZ);
			return;
		}
		
		if(cmd[0].equals(CMD_CANFIRE)) {
			this.tx = (lastShot + CHARGE_TIME < world.getTotalWorldTime()) + "";
			this.tx = this.tx.toUpperCase(Locale.US);
			return;
		}
		
		if(cmd[0].equals(CMD_SETENTITYTARGET) && cmd.length == 2) {
			this.targetedEntity = IRORInteractive.parseInt(cmd[1]);
			return;
		}
	}

	@Override
	public void onCoordAction(World world, EntityPlayer player, int x, int y, int z) {
		// 临时诊断
		// LogManager.getLogger("HBM").info("precision-laser onCoordAction x=" + x + " y=" + y + " z=" + z + " remote=" + world.isRemote);
		this.setTarget(x, z);
		this.deathBlast(world, player, x, y, z);
	}

	/** 没有明确 y（雷达/红石链路，y = -1）时退回地表。 */
	public void deathBlast(World world, int x, int z) {
		deathBlast(world, x, -1, z);
	}

	public void deathBlast(World world, int x, int y, int z) {
		deathBlast(world, null, x, y, z);
	}

	public void deathBlast(World world, EntityPlayer player, int x, int y, int z) {
		if(y < 0 || y >= 256) y = world.getHeightValue(x, z);
		deathBlast(world, player, x + 0.5, y + 0.5, z + 0.5);
	}

	public void deathBlast(World world, double x, double y, double z) {
		deathBlast(world, null, x, y, z);
	}

	public void deathBlast(World world, EntityPlayer player, double x, double y, double z) {

		// 临时诊断
		// LogManager.getLogger("HBM").info("precision-laser deathBlast x=" + (int) x + " y=" + (int) y + " z=" + (int) z
				// + " remote=" + world.isRemote
				// + " cooldownOk=" + (lastShot + CHARGE_TIME < world.getTotalWorldTime()));

		if(lastShot + CHARGE_TIME < world.getTotalWorldTime()) {
		   // if( 1==1 ){
			lastShot = world.getTotalWorldTime();
			this.markDirty();

			// deals 1,000 fucking damage
			EntityOrbitalLaser blast = new EntityOrbitalLaser(world);
			blast.owner = player;
			blast.posX = x;
			blast.posY = y;
			blast.posZ = z;
			blast.explode();

			world.spawnEntityInWorld(blast);
		}
	}
}
