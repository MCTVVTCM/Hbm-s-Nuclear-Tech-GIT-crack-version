package com.hbm.entity.logic;

import com.hbm.handler.threading.PacketThreading;
import com.hbm.packet.toclient.AuxParticlePacketNT;

import org.apache.logging.log4j.LogManager;
import cpw.mods.fml.common.network.NetworkRegistry.TargetPoint;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

public class EntityDeathBlast extends Entity {

	/**
	 * 蓄力时间（tick）。原来 60 = 3 秒，设计器点下去要等三秒才炸，感觉像没反应。
	 * 改成 1：下一个 tick 就落地，渲染那边用的是 maxAge 做收缩动画，1 也安全。
	 */
	public static final int maxAge = 0;

	public EntityDeathBlast(World world) {
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

		if(this.ticksExisted >= maxAge && !worldObj.isRemote) {
			// 放在最前面：即使下面的爆炸生成抛异常，也能看到这次确实触发了
			// LogManager.getLogger("HBM").info("death blast fired at " + (int) posX + " " + (int) posY + " " + (int) posZ);
			this.setDead();

			worldObj.spawnEntityInWorld(EntityNukeExplosionMK5.statFacNoRad(worldObj, 40, posX, posY, posZ));
			worldObj.spawnEntityInWorld(EntityNukeExplosionMK5.statFacNoRad(worldObj, 100, posX, posY, posZ));

			NBTTagCompound data = new NBTTagCompound();
			data.setString("type", "muke");
			PacketThreading.createAllAroundThreadedPacket(new AuxParticlePacketNT(data, posX, posY + 0.5, posZ), new TargetPoint(worldObj.provider.dimensionId, posX, posY, posZ, 250));
			worldObj.playSoundEffect(posX, posY, posZ, "hbm:weapon.mukeExplosion", 25.0F, 0.9F);
		}
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
		return distance < 2500000;
	}
}
