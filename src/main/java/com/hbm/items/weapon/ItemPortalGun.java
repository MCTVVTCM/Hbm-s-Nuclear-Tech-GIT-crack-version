package com.hbm.items.weapon;

import java.util.List;

import com.hbm.entity.effect.EntityPortal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Rick's portal gun, more or less.
 *
 * Right click on a wall to place a portal, right click somewhere else to place the
 * matching one - walk into either and you come out of the other. Sneak + right click
 * closes both. The two portal entities are linked by entity id and kept in the gun's
 * NBT so a pair always belongs to the gun that made it.
 *
 * @author lead
 */
public class ItemPortalGun extends Item {

	public static final int RANGE = 1024;

	public ItemPortalGun() {
		this.maxStackSize = 1;
		this.setMaxDamage(0);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean advanced) {

		NBTTagCompound nbt = stack.getTagCompound();

		if(nbt != null && nbt.getBoolean("linked")) {
			list.add(EnumChatFormatting.GREEN + "Portal pair active");
		} else {
			list.add(EnumChatFormatting.DARK_GREEN + "Right click a wall to open a portal");
			list.add(EnumChatFormatting.DARK_GREEN + "Right click again to open the exit");
		}
		list.add(EnumChatFormatting.DARK_GRAY + "Sneak + right click to close");
	}

	@Override
	public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {

		// all of the logic is serverside, the client only gets told about the entity through tracking
		if(world.isRemote) return stack;

		NBTTagCompound nbt = stack.getTagCompound();
		if(nbt == null) {
			nbt = new NBTTagCompound();
			stack.setTagCompound(nbt);
		}

		if(player.isSneaking()) {
			closePortals(world, nbt);
			// world.playSoundAtEntity(player, "random.fizz", 0.5F, 0.5F);
			return stack;
		}

		Vec3 start = Vec3.createVectorHelper(player.posX, player.posY + (double) player.getEyeHeight(), player.posZ);
		Vec3 look = player.getLookVec();
		Vec3 end = start.addVector(look.xCoord * RANGE, look.yCoord * RANGE, look.zCoord * RANGE);

		// World.rayTraceBlocks stops after 200 loop steps (vanilla `k1 = 200`), so any RANGE
		// above ~200 silently does nothing. Chain the trace in 200 block segments instead, with a
		// sanity cap so a silly RANGE cannot lock the server up.
		double maxRange = Math.min(RANGE, 512);
		MovingObjectPosition mop = null;
		double travelled = 0D;
		Vec3 traceStart = start;

		while(travelled < maxRange) {
			double segment = Math.min(200.0D, maxRange - travelled);
			Vec3 traceEnd = traceStart.addVector(look.xCoord * segment, look.yCoord * segment, look.zCoord * segment);

			mop = world.rayTraceBlocks(traceStart, traceEnd);
			if(mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) break;

			travelled += segment;
			// nudge past the segment end so the next trace does not re-test the same blocks
			traceStart = traceEnd.addVector(look.xCoord * 0.01D, look.yCoord * 0.01D, look.zCoord * 0.01D);
			mop = null;
		}

		if(mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
			// Silence was indistinguishable from "hit but placed far away"
			world.playSoundAtEntity(player, "random.break", 0.6F, 0.6F);
			player.addChatMessage(new net.minecraft.util.ChatComponentText(
					"\u00a7c[Portal]\u00a7r nothing within " + RANGE + " blocks"));
			return stack;
		}

		ForgeDirection side = ForgeDirection.getOrientation(mop.sideHit);

		// the portal sits flush on the face that was hit; for walls keep its centre one block
		// above the block floor so the opening is not half buried
		double px = mop.blockX + 0.5D + side.offsetX * 0.505D;
		double py = mop.blockY + 0.5D + side.offsetY * 0.505D;
		double pz = mop.blockZ + 0.5D + side.offsetZ * 0.505D;

		if(side.offsetY == 0) {
			// 门底与所击方块的底面齐平，打在地面那层墙块上时刚好站得住
			py = mop.blockY + EntityPortal.HALF_HEIGHT;
		}

		if(side == ForgeDirection.UP) {
			// Tall grass, flowers and snow layers are hit by the ray trace but have no collision.
			// Placing the portal on top of them would leave it floating a block above the ground,
			// so walk down to the first block you can actually stand on.
			int by = mop.blockY;
			for(int i = 0; i < 8 && by > 0 && !world.getBlock(mop.blockX, by, mop.blockZ).getMaterial().blocksMovement(); i++) {
				by--;
			}
			py = by + 1.005D;
		}

				// A wall hit places the portal with its bottom on the block's bottom, which can put its
		// centre inside a hill (shooting into a 1 block step, a cave mouth, ...). Try a couple of
		// heights and keep the first one that has open space in front of the face.
		if(side.offsetY == 0) {
			double[] candidates = { py, mop.hitVec.yCoord, mop.blockY + 0.5D };
			boolean placed = false;

			for(int i = 0; i < candidates.length; i++) {
				if(!this.isFaceBlocked(world, px, candidates[i], pz, side)) {
					py = candidates[i];
					placed = true;
					break;
				}
			}

			if(!placed) {
				world.playSoundAtEntity(player, "random.break", 0.6F, 0.6F);
				player.addChatMessage(new net.minecraft.util.ChatComponentText(
						"\u00a7c[Portal]\u00a7r no room in front of that face"));
				return stack;
			}
		}

int index = nbt.getInteger("next") & 1;

		// a gun only ever has one portal per slot, so replace the old one
		killPortal(world, nbt.getInteger("portal" + index));

		EntityPortal portal = new EntityPortal(world, px, py, pz, mop.sideHit, index);
		world.spawnEntityInWorld(portal);

		nbt.setInteger("portal" + index, portal.getEntityId());
		nbt.setInteger("next", 1 - index);

		linkPortals(world, nbt);

		// immediate feedback: which face the gun thinks it hit, so a misplaced portal is
		// obvious without digging through the log
		String[] faces = { "BOTTOM", "TOP", "NORTH", "SOUTH", "WEST", "EAST" };
		player.addChatMessage(new net.minecraft.util.ChatComponentText(
				"\u00a7a[Portal " + (index == 0 ? "A" : "B") + "]\u00a7r "
				+ faces[mop.sideHit & 7] + " face of block "
				+ mop.blockX + " " + mop.blockY + " " + mop.blockZ
				+ " -> " + String.format("%.2f %.2f %.2f", px, py, pz)));

		org.apache.logging.log4j.LogManager.getLogger("HBM").info("[portal] shot idx=" + index
				+ " id=" + portal.getEntityId() + " side=" + mop.sideHit
				+ " block=" + mop.blockX + "/" + mop.blockY + "/" + mop.blockZ
				+ " face=" + String.format("%.3f/%.3f/%.3f", mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord)
				+ " portal=" + String.format("%.3f/%.3f/%.3f", px, py, pz)
				+ " A=" + nbt.getInteger("portal0") + " B=" + nbt.getInteger("portal1")
				+ " linked=" + nbt.getBoolean("linked"));

		// Confirmation the player can hear and see at any range: the portal itself may be a
		// 2x2 speck 60 blocks away. Sounds play at the shooter, the streak marks the ray.
		world.playSoundAtEntity(player, "random.orb", 0.7F, 1.6F);
		world.playSoundEffect(px, py, pz, "mob.endermen.portal", 1.0F, 0.55F);

		Vec3 hit = mop.hitVec;
		for(int i = 1; i <= 40; i++) {
			double t = i / 40.0D;
			world.spawnParticle("happyVillager",
					start.xCoord + (hit.xCoord - start.xCoord) * t,
					start.yCoord + (hit.yCoord - start.yCoord) * t,
					start.zCoord + (hit.zCoord - start.zCoord) * t, 0, 0, 0);
		}

		for(int i = 0; i < 12; i++) {
			world.spawnParticle("happyVillager",
					px + (world.rand.nextDouble() - 0.5D) * 1.5D,
					py + (world.rand.nextDouble() - 0.5D) * 2.5D,
					pz + (world.rand.nextDouble() - 0.5D) * 1.5D,
					0, 0, 0);
		}

		return stack;
	}

	/** Is the space in front of a face solid? Samples the centre, one block up and one down. */
	private boolean isFaceBlocked(World world, double x, double y, double z, ForgeDirection side) {
		double fx = x + side.offsetX * 0.6D;
		double fy = y + side.offsetY * 0.6D;
		double fz = z + side.offsetZ * 0.6D;

		for(int dy = -1; dy <= 1; dy++) {
			int bx = (int) Math.floor(fx);
			int by = (int) Math.floor(fy + dy);
			int bz = (int) Math.floor(fz);
			if(world.getBlock(bx, by, bz).getMaterial().blocksMovement()) return true;
		}

		return false;
	}

	private static void killPortal(World world, int id) {
		if(id == 0) return;
		Entity entity = world.getEntityByID(id);
		if(entity instanceof EntityPortal) entity.setDead();
	}

	private static void closePortals(World world, NBTTagCompound nbt) {
		killPortal(world, nbt.getInteger("portal0"));
		killPortal(world, nbt.getInteger("portal1"));
		nbt.setInteger("portal0", 0);
		nbt.setInteger("portal1", 0);
		nbt.setBoolean("linked", false);
	}

	private static void linkPortals(World world, NBTTagCompound nbt) {

		Entity a = world.getEntityByID(nbt.getInteger("portal0"));
		Entity b = world.getEntityByID(nbt.getInteger("portal1"));

		if(!(a instanceof EntityPortal) || !(b instanceof EntityPortal)) {
			nbt.setBoolean("linked", false);
			return;
		}

		EntityPortal pa = (EntityPortal) a;
		EntityPortal pb = (EntityPortal) b;

		pa.setLinked(pb.getEntityId());
		pb.setLinked(pa.getEntityId());
		nbt.setBoolean("linked", true);
	}
}
