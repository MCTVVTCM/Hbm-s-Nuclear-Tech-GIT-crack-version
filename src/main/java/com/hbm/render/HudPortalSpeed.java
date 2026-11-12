package com.hbm.render;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/**
 * HUD overlay showing the player's current speed - the number that matters for the portal
 * momentum mechanic.
 *
 * Visible while holding the portal gun, or whenever the player is moving faster than normal
 * walking speed, so it appears by itself while looping through a pair of portals.
 *
 * @author lead
 */
public class HudPortalSpeed {

	/** Rebuild the string only when the displayed value actually changes. */
	private double shownSpeed = -1.0D;
	private String text = "";

	@SubscribeEvent
	public void onRenderOverlay(RenderGameOverlayEvent.Post event) {

		if(event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

		Minecraft mc = Minecraft.getMinecraft();
		EntityClientPlayerMP player = mc.thePlayer;
		if(player == null || mc.gameSettings.showDebugInfo) return;

		// Use the per tick position delta, not motionY. Standing on the ground, vanilla keeps
		// feeding the player the gravity constant (-0.0784 blocks/tick) before the collision
		// snaps them back, which reads as a fake 1.57 m/s while completely stationary.
		// Two sources, take the larger one:
		//  - the per tick position delta (accurate while actually moving)
		//  - the motion vector, which is what our portal packet writes; right after a teleport
		//    the delta is still 0 for one tick and the HUD would flash 0.0
		// The vanilla gravity constant is filtered out while standing on the ground, otherwise
		// a stationary player would read 1.6 m/s.
		double speed;

		if(player.ridingEntity instanceof com.hbm.entity.misc.EntityJindouYun) {
			// 骑云时不能用位置增量：客户端预测和服务端回包会在同一 tick 里各设一次位置，
			// 增量会翻倍甚至抖动。云本身的速度向量才是唯一真相。
			com.hbm.entity.misc.EntityJindouYun cloud =
					(com.hbm.entity.misc.EntityJindouYun) player.ridingEntity;

			speed = Math.sqrt(cloud.motionX * cloud.motionX
					+ cloud.motionY * cloud.motionY
					+ cloud.motionZ * cloud.motionZ);

		} else {
			double vx = player.posX - player.prevPosX;
			double vy = player.posY - player.prevPosY;
			double vz = player.posZ - player.prevPosZ;
			double deltaSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);

			double mx = player.motionX;
			double my = player.motionY;
			double mz = player.motionZ;
			if(player.onGround && Math.abs(my) < 0.09D) my = 0D;
			double motionSpeed = Math.sqrt(mx * mx + my * my + mz * mz);

			speed = Math.max(deltaSpeed, motionSpeed);
		}

		if(Double.isNaN(speed) || Double.isInfinite(speed)) speed = 0.0D;

		// 常驻显示：不再要求手持传送门枪或正在移动

		double metersPerSecond = Math.round(speed * 20.0D * 10.0D) / 10.0D;
		if(metersPerSecond != this.shownSpeed) {
			this.shownSpeed = metersPerSecond;
			this.text = String.format("%.1f m/s", metersPerSecond);
		}

		// 常驻显示后阈值按"格/秒"来：走路 4.3，疾跑 5.6，筋斗云巡航 64，加速 192
		int color;
		if(speed < 6.0D) color = 0x66FF99;
		else if(speed < 12.0D) color = 0xFFE066;
		else if(speed < 40.0D) color = 0xFF8844;
		else color = 0xFF3355;

		ScaledResolution res = event.resolution;
		int x = res.getScaledWidth() - mc.fontRenderer.getStringWidth(this.text) - 8;
		int y = res.getScaledHeight() - 46;

		// 关掉 F3 时让位
		if(mc.gameSettings.showDebugInfo) return;

		mc.fontRenderer.drawStringWithShadow(this.text, x, y, color);
	}
}
