package com.hbm.saveddata.satellites;

import com.hbm.items.ModItems;
import com.hbm.items.special.ItemSatellite.EnumSatType;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * Orbital chunk excavator.
 *
 * Designate a block and the satellite strips the whole 16x16 column of that chunk, starting
 * one block above the designated Y and working down to bedrock. Every block is removed the way
 * a player would remove it: normal block drops (and container contents) are spawned as items
 * instead of being deleted like an explosion would.
 *
 * Work is spread over ticks with a time budget and pauses when too many drops are lying around,
 * so a full chunk does not turn into a server-side freeze.
 */
public class SatelliteExcavator extends SatelliteBase {

	/** Time budget for excavation per tick, in milliseconds. */
	public static final long BUDGET_MS = 5L;
	/** If more than this many item entities are loaded, the satellite waits for the player to clean up. */
	public static final int MAX_LINGERING_ITEMS = 1500;

	public boolean active;
	public int chunkX, chunkZ;
	public int currentY;
	public int mined;
	public int pauseTicks;

	@Override public String getType() { return "CHUNK_MINER"; }

	@Override
	public IChatComponent[] getInfo(World world) {
		return new IChatComponent[] {
				new ChatComponentTranslation(ModItems.satellite.getUnlocalizedName(new ItemStack(ModItems.satellite, 1, EnumSatType.CHUNK_MINER.ordinal())) + ".name"),
				active ? new ChatComponentTranslation("satellite.excavating", currentY, mined) : new ChatComponentTranslation("satellite.ready")
		};
	}

	@Override
	public void onCoordAction(World world, EntityPlayer player, int x, int y, int z) {
		// one job at a time, re-designating while active would silently drop the current one
		if(world.isRemote || active) return;

		this.chunkX = x >> 4;
		this.chunkZ = z >> 4;
		int startY = (y < 0 || y >= 256) ? world.getHeightValue(x, z) : y;
		this.currentY = Math.min(255, startY + 1);
		this.mined = 0;
		this.pauseTicks = 0;
		this.active = true;

		// load before digging, otherwise getBlock() reports air for the whole column and the drops are simply lost
		world.getChunkProvider().provideChunk(chunkX, chunkZ);
		this.markDirty();
	}

	@Override
	public void onUpdateTick(World world) {
		if(world.isRemote || !active) return;

		if(pauseTicks > 0) {
			pauseTicks--;
			return;
		}

		if(world.getTotalWorldTime() % 20 == 0 && countLingeringItems(world) > MAX_LINGERING_ITEMS) {
			pauseTicks = 20;
			return;
		}

		long deadline = System.nanoTime() + BUDGET_MS * 1_000_000L;

		while(active && System.nanoTime() < deadline) {
			if(currentY < 0) {
				this.active = false;
				this.markDirty();
				return;
			}
			if(!excavateLayer(world)) return; // chunk not loaded (yet), try again next tick
		}
	}

	/** @return false if the layer could not be processed because the chunk is not loaded */
	private boolean excavateLayer(World world) {
		if(!world.getChunkProvider().chunkExists(chunkX, chunkZ)) {
			world.getChunkProvider().provideChunk(chunkX, chunkZ);
			return false;
		}

		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;

		for(int dx = 0; dx < 16; dx++) {
			for(int dz = 0; dz < 16; dz++) {
				int x = baseX + dx;
				int z = baseZ + dz;

				Block block = world.getBlock(x, currentY, z);
				if(block == Blocks.air) continue;
				if(block.getBlockHardness(world, x, currentY, z) < 0) continue; // bedrock, barrier, ...

				int meta = world.getBlockMetadata(x, currentY, z);

				if(block.hasTileEntity(meta)) {
					TileEntity te = world.getTileEntity(x, currentY, z);
					if(te instanceof IInventory) dropInventory(world, (IInventory) te, x, currentY, z);
					world.removeTileEntity(x, currentY, z);
				} else {
					// same call vanilla uses for a normal, tool-appropriate break: no silk touch, fortune 0
					block.dropBlockAsItemWithChance(world, x, currentY, z, meta, 1.0F, 0);
				}

				// flag 2 = tell clients, skip the neighbour update cascade (a chunk column would fire thousands)
				world.setBlock(x, currentY, z, Blocks.air, 0, 2);
				this.mined++;
			}
		}

		this.currentY--;
		this.markDirty();
		return true;
	}

	private void dropInventory(World world, IInventory inv, int x, int y, int z) {
		for(int i = 0; i < inv.getSizeInventory(); i++) {
			ItemStack stack = inv.getStackInSlot(i);
			if(stack == null || stack.stackSize <= 0) continue;

			inv.setInventorySlotContents(i, null);
			EntityItem drop = new EntityItem(world, x + 0.5, y + 0.5, z + 0.5, stack.copy());
			drop.delayBeforeCanPickup = 10;
			world.spawnEntityInWorld(drop);
		}
	}

	private int countLingeringItems(World world) {
		int count = 0;
		for(Object o : world.loadedEntityList) {
			if(o instanceof EntityItem) count++;
		}
		return count;
	}

	@Override
	public void onCommandImpl(World world, String... cmd) {
		if(cmd.length <= 0) return;

		if("fire".equals(cmd[0])) {
			onCoordAction(world, null, targetX, -1, targetZ);
			return;
		}

		if("stop".equals(cmd[0])) {
			this.active = false;
			this.markDirty();
			return;
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setBoolean("active", active);
		nbt.setInteger("chunkX", chunkX);
		nbt.setInteger("chunkZ", chunkZ);
		nbt.setInteger("currentY", currentY);
		nbt.setInteger("mined", mined);
		nbt.setInteger("pauseTicks", pauseTicks);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		this.active = nbt.getBoolean("active");
		this.chunkX = nbt.getInteger("chunkX");
		this.chunkZ = nbt.getInteger("chunkZ");
		this.currentY = nbt.getInteger("currentY");
		this.mined = nbt.getInteger("mined");
		this.pauseTicks = nbt.getInteger("pauseTicks");
	}
}
