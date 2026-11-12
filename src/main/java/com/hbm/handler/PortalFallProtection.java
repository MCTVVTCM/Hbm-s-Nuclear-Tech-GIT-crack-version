package com.hbm.handler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;

/**
 * Portals must not kill you. Fall damage is worked out while the client's position packet is
 * processed, which happens before the entity tick that teleports you - resetting
 * {@code fallDistance} at teleport time is therefore too late. The portal stamps a short grace
 * period on the entity instead and this handler cancels the damage during it.
 *
 * @author lead
 */
public class PortalFallProtection {

	public static final String NBT_GRACE = "hbmPortalFallGrace";
	/** Guards against registering twice when the lazy hot-reload path kicks in. */
	public static boolean registered = false;

	@SubscribeEvent
	public void onFall(LivingFallEvent event) {

		if(event.entityLiving == null || event.entityLiving.worldObj == null) return;

		long grace = event.entityLiving.getEntityData().getLong(NBT_GRACE);
		if(grace > event.entityLiving.worldObj.getTotalWorldTime()) {
			event.setCanceled(true);
			event.entityLiving.fallDistance = 0F;
			return;
		}

		// Landing right on a portal (you shot the floor to save yourself) must not hurt either:
		// the impact packet is processed before the portal tick, so the teleport would come one
		// tick too late.
		for(Object o : event.entityLiving.worldObj.loadedEntityList) {
			if(!(o instanceof com.hbm.entity.effect.EntityPortal)) continue;

			com.hbm.entity.effect.EntityPortal portal = (com.hbm.entity.effect.EntityPortal) o;
			if(portal.isDead || portal.getDistanceSqToEntity(event.entityLiving) > 9.0D) continue;
			if(!portal.insideOpening(event.entityLiving)) continue;

			event.setCanceled(true);
			event.entityLiving.fallDistance = 0F;
			return;
		}
	}
}
