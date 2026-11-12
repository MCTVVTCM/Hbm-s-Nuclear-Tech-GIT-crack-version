package com.hbm.render.entity.effect;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.entity.effect.EntityPortal;
import com.hbm.lib.RefStrings;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/**
 * Draws the portal flat on whatever surface it was shot at.
 *
 * Layers, back to front:
 *   1. a dark untextured quad so the opening reads as a hole instead of a glowing sticker,
 *   2. the swirl texture with normal alpha blending (additive made it wash out to white
 *      on bright walls),
 *   3. a small additive core for the glow.
 *
 * @author lead
 */
public class RenderPortal extends Render {

	private static final ResourceLocation TEXTURE = new ResourceLocation(RefStrings.MODID + ":textures/entity/portal.png");

	/** Reused every frame: BufferUtils.createFloatBuffer allocates direct memory and churns the heap. */
	private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

	@Override
	public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTicks) {

		EntityPortal portal = (EntityPortal) entity;
		float ticks = entity.ticksExisted + partialTicks;

		double[] basis = new double[9];
		portal.getRenderBasis(basis);

		FloatBuffer matrix = this.matrixBuffer;
		matrix.clear();
		matrix.put((float) basis[0]).put((float) basis[1]).put((float) basis[2]).put(0F);
		matrix.put((float) basis[3]).put((float) basis[4]).put((float) basis[5]).put(0F);
		matrix.put((float) basis[6]).put((float) basis[7]).put((float) basis[8]).put(0F);
		matrix.put(0F).put(0F).put(0F).put(1F);
		matrix.flip();

		float halfW = (float) EntityPortal.HALF_WIDTH;
		float halfH = (float) EntityPortal.HALF_HEIGHT;

		float r = portal.getIndex() == 0 ? 0.16F : 0.10F;
		float g = portal.getIndex() == 0 ? 1.00F : 0.88F;
		float b = portal.getIndex() == 0 ? 0.34F : 1.00F;

		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);
		GL11.glMultMatrix(matrix);

		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glDepthMask(false);
		GL11.glShadeModel(GL11.GL_SMOOTH);

		// 1. dark backdrop
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glColor4f(0.02F, 0.10F, 0.04F, 0.55F);
		this.drawQuad(halfW, halfH, 0.003D);
		GL11.glEnable(GL11.GL_TEXTURE_2D);

		// 2. swirl, normal alpha blending so bright backgrounds do not blow it out
		this.bindEntityTexture(entity);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		this.drawLayer(halfW * 1.06F, halfH * 1.06F, ticks * 1.2F, r, g, b, 1.0F);
		this.drawLayer(halfW * 0.92F, halfH * 0.92F, -ticks * 2.0F, 1.0F, 1.0F, 1.0F, 0.55F);

		// 3. additive core
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(r, g, b, 0.45F);
		this.drawQuad(halfW * 0.35F, halfH * 0.5F, 0.009D);
		GL11.glEnable(GL11.GL_TEXTURE_2D);

		GL11.glShadeModel(GL11.GL_FLAT);
		GL11.glDepthMask(true);
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glEnable(GL11.GL_LIGHTING);
		GL11.glColor4f(1F, 1F, 1F, 1F);

		GL11.glPopMatrix();
	}

	private void drawQuad(float halfW, float halfH, double z) {

		Tessellator tessellator = Tessellator.instance;
		tessellator.startDrawingQuads();
		tessellator.setNormal(0F, 0F, 1F);
		tessellator.addVertex(-halfW, -halfH, z);
		tessellator.addVertex(-halfW, halfH, z);
		tessellator.addVertex(halfW, halfH, z);
		tessellator.addVertex(halfW, -halfH, z);
		tessellator.draw();
	}

	private void drawLayer(float halfW, float halfH, float rotation, float r, float g, float b, float a) {

		// rotate the texture instead of the quad, so the billboard stays inside the portal plane
		GL11.glMatrixMode(GL11.GL_TEXTURE);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		GL11.glTranslatef(0.5F, 0.5F, 0F);
		GL11.glRotatef(rotation, 0F, 0F, 1F);
		GL11.glTranslatef(-0.5F, -0.5F, 0F);

		GL11.glColor4f(r, g, b, a);

		Tessellator tessellator = Tessellator.instance;
		tessellator.startDrawingQuads();
		tessellator.setNormal(0F, 0F, 1F);
		tessellator.addVertexWithUV(-halfW, -halfH, 0.006D, 0D, 0D);
		tessellator.addVertexWithUV(-halfW, halfH, 0.006D, 0D, 1D);
		tessellator.addVertexWithUV(halfW, halfH, 0.006D, 1D, 1D);
		tessellator.addVertexWithUV(halfW, -halfH, 0.006D, 1D, 0D);
		tessellator.draw();

		GL11.glPopMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
	}

	@Override
	protected ResourceLocation getEntityTexture(Entity entity) {
		return TEXTURE;
	}
}
