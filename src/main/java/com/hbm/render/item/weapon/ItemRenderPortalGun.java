package com.hbm.render.item.weapon;

import org.lwjgl.opengl.GL11;

import com.hbm.lib.RefStrings;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.model.AdvancedModelLoader;
import net.minecraftforge.client.model.IModelCustom;

/**
 * Renders the portal gun from an .obj model (block units, muzzle pointing towards +Z,
 * origin at the top of the grip) and draws the energy core as a second, unlit pass.
 *
 * @author lead
 */
public class ItemRenderPortalGun implements IItemRenderer {

	private static final ResourceLocation MODEL_PATH = new ResourceLocation(RefStrings.MODID, "models/portal_gun.obj");
	private static final ResourceLocation TEXTURE = new ResourceLocation(RefStrings.MODID + ":textures/models/portal_gun.png");

	private static IModelCustom model;

	public ItemRenderPortalGun() {
		if(model == null) model = AdvancedModelLoader.loadModel(MODEL_PATH);
	}

	@Override
	public boolean handleRenderType(ItemStack item, ItemRenderType type) {
		switch(type) {
		case EQUIPPED:
		case EQUIPPED_FIRST_PERSON:
		case ENTITY:
			return true;
		default:
			return false;
		}
	}

	@Override
	public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
		return type == ItemRenderType.ENTITY && (helper == ItemRendererHelper.ENTITY_BOBBING || helper == ItemRendererHelper.ENTITY_ROTATION);
	}

	@Override
	public void renderItem(ItemRenderType type, ItemStack item, Object... data) {

		if(model == null) model = AdvancedModelLoader.loadModel(MODEL_PATH);

		switch(type) {

		case EQUIPPED_FIRST_PERSON:
			GL11.glPushMatrix();
			GL11.glTranslatef(0.32F, -0.30F, -0.55F);
			GL11.glRotatef(180F, 0F, 1F, 0F);
			GL11.glRotatef(-6F, 1F, 0F, 0F);
			this.renderModel(0.85F);
			GL11.glPopMatrix();
			break;

		case EQUIPPED:
			GL11.glPushMatrix();
			GL11.glTranslatef(0.10F, 0.05F, 0.10F);
			GL11.glRotatef(180F, 0F, 1F, 0F);
			GL11.glRotatef(-20F, 0F, 0F, 1F);
			this.renderModel(0.75F);
			GL11.glPopMatrix();
			break;

		case ENTITY:
			GL11.glPushMatrix();
			GL11.glTranslatef(0F, 0.1F, 0F);
			GL11.glRotatef(180F, 0F, 1F, 0F);
			GL11.glRotatef(20F, 0F, 0F, 1F);
			this.renderModel(0.7F);
			GL11.glPopMatrix();
			break;

		default:
			break;
		}
	}

	private void renderModel(float scale) {

		Minecraft.getMinecraft().renderEngine.bindTexture(TEXTURE);

		GL11.glPushMatrix();
		GL11.glScalef(scale, scale, scale);
		GL11.glEnable(GL11.GL_NORMALIZE);
		GL11.glDisable(GL11.GL_CULL_FACE);

		model.renderAll();

		// energy core: unlit + additive so it glows in the dark
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		GL11.glDepthMask(false);
		GL11.glColor4f(0.35F, 1.0F, 0.55F, 0.9F);
		model.renderPart("glow");
		GL11.glColor4f(1F, 1F, 1F, 1F);
		GL11.glDepthMask(true);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_LIGHTING);

		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glPopMatrix();
	}
}
