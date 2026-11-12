package com.hbm.util;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * A snapshot of a 16×16×16 sub-chunk.
 *
 * The blocks are stored the same way Minecraft itself stores them: the raw 12-bit
 * block ids from {@link ExtendedBlockStorage} (LSB byte array + optional MSB nibble
 * array), plus a shared id → {@link Block} lookup table. Taking a snapshot is then
 * two array copies instead of 4096 palette/hash-map operations, which matters because
 * snapshots are taken on the server thread while the blast map is being generated.
 *
 * @author mlbv
 */
public class SubChunkSnapshot {
	/**
	 * A sub-chunk that contains only air.
	 */
	public static final SubChunkSnapshot EMPTY = new SubChunkSnapshot(null, null);

	private static final int VOLUME = 16 * 16 * 16;

	/** Shared id → Block table, filled on first use (after all mods registered their blocks). */
	private static final Block[] ID_TABLE = new Block[4096];
	private static volatile boolean idTableReady = false;

	private final byte[] lsb;
	private final byte[] msb;

	private SubChunkSnapshot(byte[] lsb, byte[] msb) {
		this.lsb = lsb;
		this.msb = msb;
	}

	/**
	 * Creates a SubChunkSnapshot.
	 *
	 * @param world
	 *        The World instance from which to retrieve the chunk.
	 * @param key
	 *        The SubChunkKey identifying the sub-chunk.
	 * @param allowGeneration
	 *        Whether to generate chunks. If false, attempting to retrieve a snapshot of a chunk that does not exist will return {@link SubChunkSnapshot#EMPTY}.
	 * @return
	 *        A SubChunkSnapshot containing the block data for the sub-chunk,
	 *        or {@link SubChunkSnapshot#EMPTY} if the region contains only air.
	 */
	public static SubChunkSnapshot getSnapshot(World world, SubChunkKey key, boolean allowGeneration) {
		if (!world.getChunkProvider().chunkExists(key.getChunkXPos(), key.getChunkZPos()) && !allowGeneration) {
			return SubChunkSnapshot.EMPTY;
		}
		Chunk chunk = world.getChunkProvider().provideChunk(key.getChunkXPos(), key.getChunkZPos());
		ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[key.getSubY()];
		if (ebs == null || ebs.isEmpty()) return SubChunkSnapshot.EMPTY;

		byte[] source = ebs.getBlockLSBArray();
		byte[] lsbCopy = new byte[VOLUME];
		System.arraycopy(source, 0, lsbCopy, 0, Math.min(source.length, VOLUME));

		NibbleArray msbArray = ebs.getBlockMSBArray();
		byte[] msbCopy = msbArray == null ? null : msbArray.data.clone();

		ensureIdTable();
		return new SubChunkSnapshot(lsbCopy, msbCopy);
	}

	private static void ensureIdTable() {
		if (idTableReady) return;
		synchronized (SubChunkSnapshot.class) {
			if (idTableReady) return;
			Block air = Blocks.air;
			for (int id = 0; id < ID_TABLE.length; id++) {
				Block block = Block.getBlockById(id);
				ID_TABLE[id] = block == null ? air : block;
			}
			idTableReady = true;
		}
	}

	/**
	 * Retrieves the Block at the specified local coordinates within this sub-chunk snapshot.
	 *
	 * @param x
	 *        The local x-coordinate within the sub-chunk (0–15).
	 * @param y
	 *        The local y-coordinate within the sub-chunk (0–15).
	 * @param z
	 *        The local z-coordinate within the sub-chunk (0–15).
	 * @return
	 *        The Block instance at the given position.
	 */
	public Block getBlock(int x, int y, int z) {
		if (lsb == null) return Blocks.air;
		int index = y << 8 | z << 4 | x;
		int id = lsb[index] & 0xFF;
		if (msb != null) {
			id |= ((msb[index >> 1] >> ((index & 1) << 2)) & 15) << 8;
		}
		return ID_TABLE[id];
	}
}
