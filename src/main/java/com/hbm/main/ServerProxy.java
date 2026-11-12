package com.hbm.main;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.saveddata.TomSaveData;
import com.hbm.sound.AudioWrapper;
import com.hbm.util.i18n.I18nServer;
import com.hbm.util.i18n.ITranslate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class ServerProxy {
	
	private static final I18nServer I18N = new I18nServer();

	/** 客户端专用：本地玩家。专用服务端返回 null，调用方据此跳过客户端预测。 */
	public EntityPlayer getLocalPlayer() { return null; }

	/** 客户端专用：冲刺键是否按下（筋斗云的 Ctrl 加速）。服务端返回 false。 */
	public boolean isBoostKeyDown() { return false; }

	/** 客户端专用：潜行键的**原始按键**状态。服务端返回 false。 */
	public boolean inputSneak() { return false; }

	/** 客户端专用：F3 那行调试信息（含 fps）。服务端返回空串。 */
	public String debugInfo() { return ""; }

	/* ---------- 客户端专用：原始移动输入。服务端一律返回 0 ---------- */
	/** 骑乘时 player.moveForward 会被原版清零，所以本地预测必须直接读 movementInput */
	public float inputForward() { return 0.0F; }
	public float inputStrafe() { return 0.0F; }
	public boolean inputJump() { return false; }
	public float keyForward() { return 0.0F; }
	public float keyStrafe() { return 0.0F; }
	public float rawMoveForward() { return 0.0F; }
	public void notify(String msg) { }

	//sort by estimated time of display. longer lasting ones should be sorted at the top.
	public static final int ID_DUCK = 0;
	public static final int ID_FILTER = 1;
	public static final int ID_COMPASS = 2;
	public static final int ID_CABLE = 3;
	public static final int ID_DRONE = 4;
	public static final int ID_JETPACK = 5;
	public static final int ID_MAGNET = 6;
	public static final int ID_HUD = 7;
	public static final int ID_DETONATOR = 8;
	public static final int ID_FLUID_ID = 9;
	public static final int ID_FAN_MODE = 10;
	public static final int ID_TOOLABILITY = 11;
	public static final int ID_GAS_HAZARD = 12;
	public static final int ID_WRENCH = 13;
	public static final int ID_PAGER_DYN = 1000;
	
	public ITranslate getI18n() { return I18N; }

	public void registerPreRenderInfo() { }
	public void registerRenderInfo() { }
	public void registerTileEntitySpecialRenderer() { }
	public void registerItemRenderer() { }
	public void registerEntityRenderer() { }
	public void registerBlockRenderer() { }
	public void registerGunCfg() { }
	public void handleNHNEICompat() { }

	public void spawnParticle(double x, double y, double z, String type, float[] args) { }

	public void effectNT(NBTTagCompound data) { }

	public void registerMissileItems() { }

	/** Retired in favor of the version that uses keepAlive */
	@Deprecated public AudioWrapper getLoopedSound(String sound, float x, float y, float z, float volume, float range, float pitch) { return null; }
	public AudioWrapper getLoopedSound(String sound, float x, float y, float z, float volume, float range, float pitch, int keepAlive) { return null; }

	public void playSound(String sound, Object data) { }

	public void displayTooltip(String msg, int id) {
		displayTooltip(msg, 1000, id);
	}
	
	public void displayTooltip(String msg, int time, int id) { }

	public boolean getIsKeyPressed(EnumKeybind key) {
		return false;
	}
	
	public EntityPlayer me() {
		return null;
	}
	
	public boolean advancedTooltips() { return false; }

	public boolean isVanished(Entity e) {
		return false;
	}

	public void openLink(String url) { }

	public List<ItemStack> getSubItems(ItemStack stack) {

		List<ItemStack> list = new ArrayList();
		list.add(stack);
		return list;
	}

	public float getImpactDust(World world) {
		return TomSaveData.forWorld(world).dust;
	}

	public float getImpactFire(World world) {
		return TomSaveData.forWorld(world).fire;
	}

	public boolean getImpact(World world) {
		return TomSaveData.forWorld(world).impact;
	}

	public void playSoundClient(double x, double y, double z, String sound, float volume, float pitch) { }

	public String getLanguageCode() { return "en_US"; }

	public int getStackColor(ItemStack stack, boolean amplify) { return 0x000000; }
}
