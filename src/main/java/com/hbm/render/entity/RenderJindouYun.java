package com.hbm.render.entity;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/**
 * 筋斗云的渲染：三层水平的云片 + 一组交叉竖面，俯视和平视都有体积感。
 *
 * 两套画法：
 * - 第三人称（或看别人的云）：完整的三层云片 + 交叉竖面，关深度写入避免半透明互相 z-fighting。
 * - 第一人称（自己骑着）：只画水平云片，并且关掉深度测试。
 *   镜头就在云的包围盒里，交叉竖面正好穿过镜头，会被近裁剪面切出闪烁的边；
 *   深度测试又会让它和脚下的地形来回抢像素。这两样一起关掉，就没什么可闪的了。
 */
public class RenderJindouYun extends Render {

	private static final ResourceLocation TEX = new ResourceLocation("hbm:textures/entity/jindouyun.png");

	@Override
	public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTicks) {

		Minecraft mc = Minecraft.getMinecraft();
		boolean firstPerson = mc.thePlayer != null && entity.riddenByEntity == mc.thePlayer
				&& mc.gameSettings.thirdPersonView == 0;

		this.bindEntityTexture(entity);

		// 实体朝向在网络上是按 byte（yaw * 256 / 360）同步的，转到 ±180° 就回绕。
		// RenderManager 给的 yaw 是 prev -> current 的线性插值，回绕时它会绕一大圈，
		// 表现为"左转转过 360° 之后云突然倒着转回来"。这里自己算一次最短弧。
		float deltaYaw = entity.rotationYaw - entity.prevRotationYaw;
		while(deltaYaw >= 180.0F) deltaYaw -= 360.0F;
		while(deltaYaw < -180.0F) deltaYaw += 360.0F;
		float renderYaw = entity.prevRotationYaw + deltaYaw * partialTicks;

		GL11.glPushMatrix();
		GL11.glTranslated(x, y + 0.05D, z);
		GL11.glRotatef(-renderYaw, 0.0F, 1.0F, 0.0F);

		float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
		GL11.glRotatef(pitch, 1.0F, 0.0F, 0.0F);

		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDepthMask(false);
		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		if(firstPerson) {
			GL11.glDisable(GL11.GL_DEPTH_TEST);
		}

		this.plane(-0.14F, 1.25F, 0.70F);
		this.plane(0.00F, 1.60F, 0.85F);
		this.plane(0.10F, 0.95F, 0.60F);

		if(!firstPerson) {
			this.cross(1.45F, 0.55F);
		}

		if(firstPerson) {
			GL11.glEnable(GL11.GL_DEPTH_TEST);
		}

		GL11.glDepthMask(true);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_LIGHTING);
		GL11.glPopMatrix();
	}

	private void plane(float y, float size, float alpha) {

		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.setColorRGBA_F(1.0F, 1.0F, 1.0F, alpha);
		t.addVertexWithUV(-size, y, -size, 0.0D, 0.0D);
		t.addVertexWithUV(-size, y,  size, 0.0D, 1.0D);
		t.addVertexWithUV( size, y,  size, 1.0D, 1.0D);
		t.addVertexWithUV( size, y, -size, 1.0D, 0.0D);
		t.draw();
	}

	private void cross(float half, float height) {

		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.setColorRGBA_F(1.0F, 1.0F, 1.0F, 0.9F);

		t.addVertexWithUV(-half, -0.10F, 0.0D, 0.0D, 0.0D);
		t.addVertexWithUV(-half,  height, 0.0D, 0.0D, 1.0D);
		t.addVertexWithUV( half,  height, 0.0D, 1.0D, 1.0D);
		t.addVertexWithUV( half, -0.10F, 0.0D, 1.0D, 0.0D);

		t.addVertexWithUV(0.0D, -0.10F, -half, 0.0D, 0.0D);
		t.addVertexWithUV(0.0D,  height, -half, 0.0D, 1.0D);
		t.addVertexWithUV(0.0D,  height,  half, 1.0D, 1.0D);
		t.addVertexWithUV(0.0D, -0.10F,  half, 1.0D, 0.0D);

		t.draw();
	}

	@Override
	protected ResourceLocation getEntityTexture(Entity entity) {
		return TEX;
	}
}
