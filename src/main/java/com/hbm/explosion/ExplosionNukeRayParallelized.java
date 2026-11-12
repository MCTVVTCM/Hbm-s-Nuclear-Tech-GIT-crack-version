package com.hbm.explosion;

import com.hbm.config.BombConfig;
import com.hbm.interfaces.IExplosionRay;
import com.hbm.main.MainRegistry;
import com.hbm.util.ConcurrentBitSet;
import com.hbm.util.SubChunkKey;
import com.hbm.util.SubChunkSnapshot;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.apache.logging.log4j.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

/**
 * Threaded DDA raytracer for mk5 explosion.
 *
 * @author mlbv
 */
public class ExplosionNukeRayParallelized implements IExplosionRay {

	private static final int WORLD_HEIGHT = 256;
	private static final int BITSET_SIZE = 16 * WORLD_HEIGHT * 16;
	private static final int SUBCHUNK_PER_CHUNK = WORLD_HEIGHT >> 4;
	private static final float NUKE_RESISTANCE_CUTOFF = 2_000_000F;
	private static final float INITIAL_ENERGY_FACTOR = 0.3F; // Scales crater, no impact on performance
	private static final double RESOLUTION_FACTOR = 1.0; // Scales ray density, no impact on crater radius
	/** 工作线程上限，可用 -Dhbm.blastThreads=N 覆盖。留一个核给服务端主线程，避免 tick 被自己饿死。 */
	private static final int BLAST_THREADS = Math.max(1, Integer.getInteger("hbm.blastThreads", 8));
	/** 空 sub-chunk 跳跃；出问题可以用 -Dhbm.blastAirSkip=false 关掉。 */
	private static final boolean AIR_SKIP = Boolean.parseBoolean(System.getProperty("hbm.blastAirSkip", "true"));
	// Damage accumulation (algorithm 2) uses one float per voxel: 256 KiB per affected chunk, no
	// per-voxel objects, no quantization and no value ceiling. Fixed point shorts would halve that
	// memory but need a scale, rounding and a saturation cap - not worth the complexity or the
	// "leaves are immune to nukes" class of bugs that come with quantizing tiny hits.

	protected final World world;
	private final double explosionX, explosionY, explosionZ;
	private final int originX, originY, originZ;
	private final int strength;
	private final int radius;

	private final CompletableFuture<List<Vec3>> directionsFuture;
	private final ConcurrentMap<ChunkCoordIntPair, ConcurrentBitSet> destructionMap;
	/** Quantized accumulated damage per chunk, indexed by the same bit layout as the destruction bitset. */
	private final ConcurrentMap<ChunkCoordIntPair, float[]> damageMap;
	/** Block 的抗性是常量，但 getExplosionResistance 在模组方块里可能是虚调用/查配置，按 Block 缓存一次。 */
	private final ConcurrentMap<Block, Float> resistanceCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<SubChunkKey, SubChunkSnapshot> snapshots;
	private final ConcurrentMap<SubChunkKey, ConcurrentLinkedQueue<RayTask>> waitingRoom;
	private final BlockingQueue<RayTask> rayQueue;
	private final ExecutorService pool;
	private final CountDownLatch latch;
	private final Thread latchWatcherThread;
	private final List<ChunkCoordIntPair> orderedChunks;
	private final BlockingQueue<SubChunkKey> highPriorityReactiveQueue; // cache queue for rays
	private final Iterator<SubChunkKey> lowPriorityProactiveIterator;
	private volatile List<Vec3> directions;
	private volatile boolean collectFinished = false;
	private volatile boolean consolidationFinished = false;
	private volatile boolean destroyFinished = false;

	public ExplosionNukeRayParallelized(World world, double x, double y, double z, int strength, int speed, int radius) {
		this.world = world;
		this.explosionX = x;
		this.explosionY = y;
		this.explosionZ = z;

		this.originX = (int) Math.floor(x);
		this.originY = (int) Math.floor(y);
		this.originZ = (int) Math.floor(z);

		this.strength = strength;
		this.radius = radius;

		int rayCount = Math.max(0, (int) (2.5 * Math.PI * strength * strength * RESOLUTION_FACTOR));
		this.latch = new CountDownLatch(rayCount);
		List<SubChunkKey> sortedSubChunks = getAllSubChunks();
		this.lowPriorityProactiveIterator = sortedSubChunks.iterator();
		this.highPriorityReactiveQueue = new LinkedBlockingQueue<>();

		int initialChunkCapacity = (int) sortedSubChunks.stream().map(SubChunkKey::getPos).distinct().count();

		this.destructionMap = new ConcurrentHashMap<>(initialChunkCapacity);
		this.damageMap = new ConcurrentHashMap<>(initialChunkCapacity);

		int subChunkCount = sortedSubChunks.size();
		this.snapshots = new ConcurrentHashMap<>(subChunkCount);
		this.waitingRoom = new ConcurrentHashMap<>(subChunkCount);
		this.orderedChunks = new ArrayList<>();

		List<RayTask> initialRayTasks = new ArrayList<>(rayCount);
		for (int i = 0; i < rayCount; i++) initialRayTasks.add(new RayTask(i));
		this.rayQueue = new LinkedBlockingQueue<>(initialRayTasks);

		int workers = Math.max(1, Math.min(BLAST_THREADS, Runtime.getRuntime().availableProcessors() - 1));
		this.pool = Executors.newWorkStealingPool(workers);
		this.directionsFuture = CompletableFuture.supplyAsync(() -> generateSphereRays(rayCount));

		for (int i = 0; i < workers; i++) pool.submit(new Worker());

		this.latchWatcherThread = new Thread(() -> {
			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				collectFinished = true;
				if (BombConfig.explosionAlgorithm == 2) pool.submit(this::runConsolidation);
				else consolidationFinished = true;
			}
		}, "ExplosionNuke-LatchWatcher-" + System.nanoTime());
		this.latchWatcherThread.setDaemon(true);
		this.latchWatcherThread.start();
	}

	private float getNukeResistance(Block b) {
		Float cached = resistanceCache.get(b);
		if (cached != null) return cached;

		float resistance;
		if (b.getMaterial().isLiquid()) resistance = 0.1F;
		else if (b == Blocks.sandstone) resistance = Blocks.stone.getExplosionResistance(null);
		else if (b == Blocks.obsidian) resistance = Blocks.stone.getExplosionResistance(null) * 3.0F;
		else resistance = b.getExplosionResistance(null);

		resistanceCache.put(b, resistance);
		return resistance;
	}

	private List<SubChunkKey> getAllSubChunks() {
		List<SubChunkKey> keys = new ArrayList<>();
		int cr = (radius + 15) >> 4;
		int minCX = (originX >> 4) - cr;
		int maxCX = (originX >> 4) + cr;
		int minCZ = (originZ >> 4) - cr;
		int maxCZ = (originZ >> 4) + cr;
		int minSubY = Math.max(0, (originY - radius) >> 4);
		int maxSubY = Math.min(SUBCHUNK_PER_CHUNK - 1, (originY + radius) >> 4);
		int originSubY = originY >> 4;

		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				for (int subY = minSubY; subY <= maxSubY; subY++) {
					int chunkCenterX = (cx << 4) + 8;
					int chunkCenterY = (subY << 4) + 8;
					int chunkCenterZ = (cz << 4) + 8;
					double dx = chunkCenterX - explosionX;
					double dy = chunkCenterY - explosionY;
					double dz = chunkCenterZ - explosionZ;
					if (dx * dx + dy * dy + dz * dz <= (radius + 14) * (radius + 14)) { // +14 for margin of error
						keys.add(new SubChunkKey(cx, cz, subY));
					}
				}
			}
		}
		keys.sort(Comparator.comparingInt(key -> {
			int distCX = key.getPos().chunkXPos - (originX >> 4);
			int distCZ = key.getPos().chunkZPos - (originZ >> 4);
			int distSubY = key.getSubY() - originSubY;
			return distCX * distCX + distCZ * distCZ + distSubY * distSubY;
		}));
		return keys;
	}

	@Override
	public void cacheChunksTick(int timeBudgetMs) {
		if (collectFinished) return;
		final long deadline = System.nanoTime() + (timeBudgetMs * 1_000_000L);
		while (System.nanoTime() < deadline) {
			SubChunkKey ck = highPriorityReactiveQueue.poll();
			if (ck == null) break;
			processCacheKey(ck);
		}
		while (System.nanoTime() < deadline && lowPriorityProactiveIterator.hasNext()) {
			SubChunkKey ck = lowPriorityProactiveIterator.next();
			processCacheKey(ck);
		}
	}

	private void processCacheKey(SubChunkKey ck) {
		if (snapshots.containsKey(ck)) return;
		// 先放快照再原子地摘走等待队列：park() 里插入等待者之后会复查快照，
		// 两边靠 ConcurrentHashMap 的按 key 原子性序列化，谁先谁后都不会丢任务。
		snapshots.put(ck, SubChunkSnapshot.getSnapshot(world, ck, BombConfig.chunkloading));
		waitingRoom.computeIfPresent(ck, (k, waiters) -> {
			rayQueue.addAll(waiters);
			return null;
		});
	}

	@Override
	public void destructionTick(int timeBudgetMs) {
		if (!collectFinished || !consolidationFinished || destroyFinished) return;

		final long deadline = System.nanoTime() + timeBudgetMs * 1_000_000L;

		if (orderedChunks.isEmpty() && !destructionMap.isEmpty()) {
			orderedChunks.addAll(destructionMap.keySet());
			orderedChunks.sort(Comparator.comparingInt(c -> Math.abs((originX >> 4) - c.chunkXPos) + Math.abs((originZ >> 4) - c.chunkZPos)));
		}

		Iterator<ChunkCoordIntPair> it = orderedChunks.iterator();
		while (it.hasNext() && System.nanoTime() < deadline) {
			ChunkCoordIntPair cp = it.next();
			ConcurrentBitSet bs = destructionMap.get(cp);
			if (bs == null) {
				it.remove();
				continue;
			}

			Chunk chunk = world.getChunkFromChunkCoords(cp.chunkXPos, cp.chunkZPos);
			ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
			boolean chunkModified = false;

			for (int subY = 0; subY < storages.length; subY++) {
				ExtendedBlockStorage storage = storages[subY];

				int startBit = (WORLD_HEIGHT - 1 - ((subY << 4) + 15)) << 8;
				int endBit = ((WORLD_HEIGHT - 1 - (subY << 4)) << 8) | 0xFF;

				// 没有 storage 等于这一段全是空气：把位清掉，否则 bs.isEmpty() 永远不为真、
				// destructionMap 永远清不空，爆炸实体就不死了。
				if (storage == null) {
					clearRange(bs, startBit, endBit);
					continue;
				}

				int bit = bs.nextSetBit(startBit);

				while (bit >= 0 && bit <= endBit && System.nanoTime() < deadline) {
					int yGlobal = WORLD_HEIGHT - 1 - (bit >>> 8);
					int xGlobal = (cp.chunkXPos << 4) | ((bit >>> 4) & 0xF);
					int zGlobal = (cp.chunkZPos << 4) | (bit & 0xF);
					int xLocal = xGlobal & 0xF;
					int yLocal = yGlobal & 0xF;
					int zLocal = zGlobal & 0xF;
					Block current = storage.getBlockByExtId(xLocal, yLocal, zLocal);
					if (current != Blocks.air) {
						// world.getTileEntity() builds a ChunkPosition and hashes it for every single block;
						// most blocks can never own a tile entity, so ask the block first.
						if (current.hasTileEntity(storage.getExtBlockMetadata(xLocal, yLocal, zLocal))
								&& world.getTileEntity(xGlobal, yGlobal, zGlobal) != null) {
							world.removeTileEntity(xGlobal, yGlobal, zGlobal);
						}

						// 必须走 Chunk.func_150807_a（World.setBlock 内部用的就是这个）：
						// 它除了写方块，还会更新 heightMap、重新扩散天空光/方块光。
						// 之前直接写 ExtendedBlockStorage，光照数组和 heightMap 全是旧的 ——
						// 结果弹坑一片漆黑、还夹着一条条竖直的亮缝（就是截图里那些蓝条）。
						chunk.func_150807_a(xLocal, yGlobal, zLocal, Blocks.air, 0);
						chunkModified = true;

						world.notifyBlocksOfNeighborChange(xGlobal, yGlobal, zGlobal, Blocks.air);
						world.markBlockForUpdate(xGlobal, yGlobal, zGlobal);

						// heightMap 由 func_150807_a 修好了，这里再把光往邻居扩散一次
						world.updateLightByType(EnumSkyBlock.Sky, xGlobal, yGlobal, zGlobal);
						world.updateLightByType(EnumSkyBlock.Block, xGlobal, yGlobal, zGlobal);
					}
					bs.clear(bit);
					bit = bs.nextSetBit(bit + 1);
				}
			}

			if (chunkModified) {
				chunk.setChunkModified();
				world.markBlockRangeForRenderUpdate(cp.chunkXPos << 4, 0, cp.chunkZPos << 4, (cp.chunkXPos << 4) | 15, WORLD_HEIGHT - 1, (cp.chunkZPos << 4) | 15);
			}
			if (bs.isEmpty()) {
				destructionMap.remove(cp);
				for (int subY = 0; subY < SUBCHUNK_PER_CHUNK; subY++) snapshots.remove(new SubChunkKey(cp, subY));
				it.remove();
			}
		}

		if (orderedChunks.isEmpty() && destructionMap.isEmpty()) {
			destroyFinished = true;
			if (pool != null) pool.shutdown();
		}
	}

	private static void clearRange(ConcurrentBitSet bs, int startBit, int endBit) {
		for (int bit = bs.nextSetBit(startBit); bit >= 0 && bit <= endBit; bit = bs.nextSetBit(bit + 1)) {
			bs.clear(bit);
		}
	}

	@Override
	public boolean isComplete() {
		return collectFinished && consolidationFinished && destroyFinished;
	}

	@Override
	public void cancel() {
		this.collectFinished = true;
		this.consolidationFinished = true;
		this.destroyFinished = true;

		if (this.rayQueue != null) this.rayQueue.clear();
		if (this.waitingRoom != null) this.waitingRoom.clear();

		if (this.latch != null) while (this.latch.getCount() > 0) this.latch.countDown();
		if (this.latchWatcherThread != null && this.latchWatcherThread.isAlive()) this.latchWatcherThread.interrupt();

		if (this.pool != null && !this.pool.isShutdown()) {
			this.pool.shutdownNow();
			try {
				if (!this.pool.awaitTermination(100, TimeUnit.MILLISECONDS)) MainRegistry.logger.log(Level.ERROR, "ExplosionNukeRayParallelized thread pool did not terminate promptly on cancel.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				if (!this.pool.isShutdown()) this.pool.shutdownNow();
			}
		}
		if (this.destructionMap != null) this.destructionMap.clear();
		if (this.damageMap != null) this.damageMap.clear();
		if (this.snapshots != null) this.snapshots.clear();
		if (this.orderedChunks != null) this.orderedChunks.clear();
	}

	private List<Vec3> generateSphereRays(int count) {
		List<Vec3> list = new ArrayList<>(count);
		if (count == 0) return list;
		if (count == 1) {
			list.add(Vec3.createVectorHelper(1, 0, 0));
			return list;
		}
		double phi = Math.PI * (3.0 - Math.sqrt(5.0));
		for (int i = 0; i < count; i++) {
			double y = 1.0 - (i / (double) (count - 1)) * 2.0;
			double r = Math.sqrt(1.0 - y * y);
			double t = phi * i;
			list.add(Vec3.createVectorHelper(Math.cos(t) * r, y, Math.sin(t) * r));
		}
		return list;
	}

	private void runConsolidation() {
		damageMap.forEach((cp, damage) -> {
			if (damage == null) return;

			ConcurrentBitSet chunkDestructionBitSet = null;
			int cachedSubY = Integer.MIN_VALUE;
			SubChunkSnapshot cachedSnap = null;

			for (int bitIndex = 0; bitIndex < BITSET_SIZE; bitIndex++) {
				float accumulatedDamage = damage[bitIndex];
				if (accumulatedDamage <= 0F) continue;

				int yGlobal = WORLD_HEIGHT - 1 - (bitIndex >>> 8);
				int subY = yGlobal >> 4;
				if (subY < 0) continue;

				if (subY != cachedSubY) {
					cachedSubY = subY;
					cachedSnap = snapshots.get(new SubChunkKey(cp, subY));
				}
				if (cachedSnap == null || cachedSnap == SubChunkSnapshot.EMPTY) continue;

				Block originalBlock = cachedSnap.getBlock((bitIndex >>> 4) & 0xF, yGlobal & 0xF, bitIndex & 0xF);
				if (originalBlock == Blocks.air) continue;

				float resistance = getNukeResistance(originalBlock) * (float) RESOLUTION_FACTOR;
				if (accumulatedDamage >= resistance) {
					if (chunkDestructionBitSet == null) {
						chunkDestructionBitSet = destructionMap.computeIfAbsent(cp, k -> new ConcurrentBitSet(BITSET_SIZE));
					}
					chunkDestructionBitSet.set(bitIndex);
				}
			}

			damageMap.remove(cp);
		});
		damageMap.clear();
		consolidationFinished = true;
	}

	private class Worker implements Runnable {
		@Override
		public void run() {
			try {
				while (!collectFinished && !Thread.currentThread().isInterrupted()) {
					RayTask task = rayQueue.poll(100, TimeUnit.MILLISECONDS);
					if (task != null) task.trace();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private class RayTask {
		private static final double RAY_DIRECTION_EPSILON = 1e-6;
		private static final double PROCESSING_EPSILON = 1e-9;
		private static final float MIN_EFFECTIVE_DIST_FOR_ENERGY_CALC = 0.01f;
		private static final double JUMP_EPSILON = 1e-6; // 跳过后落在盒外一点点，避免 tMax 算出 0 而原地打转

		final int dirIndex;
		double dirX, dirY, dirZ;
		double px, py, pz;
		int x, y, z;
		float energy;
		double tMaxX, tMaxY, tMaxZ, tDeltaX, tDeltaY, tDeltaZ;
		int stepX, stepY, stepZ;
		boolean initialised = false;
		boolean finished = false;
		double currentRayPosition;

		private int lastCX = Integer.MIN_VALUE, lastCZ = Integer.MIN_VALUE, lastSubY = Integer.MIN_VALUE;
		/** Reused for snapshots.get() only - never store it in a map, map keys must not mutate. */
		private final SubChunkKey lookupKey = new SubChunkKey(0, 0, 0);
		private SubChunkSnapshot currentSnap;
		private ChunkCoordIntPair currentChunkPos;
		private ChunkCoordIntPair cachedBitsetChunk, cachedDamageChunk;
		private ConcurrentBitSet cachedBitset;
		private float[] cachedDamage;

		RayTask(int dirIdx) {
			this.dirIndex = dirIdx;
		}

		void init() {
			if (directions == null) directions = directionsFuture.join();
			Vec3 dir = directions.get(this.dirIndex);
			this.energy = strength * INITIAL_ENERGY_FACTOR;
			this.px = explosionX;
			this.py = explosionY;
			this.pz = explosionZ;
			this.x = originX;
			this.y = originY;
			this.z = originZ;
			this.currentRayPosition = 0.0;

			double dirX = this.dirX = dir.xCoord;
			double dirY = this.dirY = dir.yCoord;
			double dirZ = this.dirZ = dir.zCoord;

			double absDirX = Math.abs(dirX);
			this.stepX = (absDirX < RAY_DIRECTION_EPSILON) ? 0 : (dirX > 0 ? 1 : -1);
			this.tDeltaX = (stepX == 0) ? Double.POSITIVE_INFINITY : 1.0 / absDirX;
			this.tMaxX = (stepX == 0) ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (this.x + 1 - this.px) : (this.px - this.x)) * this.tDeltaX);

			double absDirY = Math.abs(dirY);
			this.stepY = (absDirY < RAY_DIRECTION_EPSILON) ? 0 : (dirY > 0 ? 1 : -1);
			this.tDeltaY = (stepY == 0) ? Double.POSITIVE_INFINITY : 1.0 / absDirY;
			this.tMaxY = (stepY == 0) ? Double.POSITIVE_INFINITY : ((stepY > 0 ? (this.y + 1 - this.py) : (this.py - this.y)) * this.tDeltaY);

			double absDirZ = Math.abs(dirZ);
			this.stepZ = (absDirZ < RAY_DIRECTION_EPSILON) ? 0 : (dirZ > 0 ? 1 : -1);
			this.tDeltaZ = (stepZ == 0) ? Double.POSITIVE_INFINITY : 1.0 / absDirZ;
			this.tMaxZ = (stepZ == 0) ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (this.z + 1 - this.pz) : (this.pz - this.z)) * this.tDeltaZ);

			this.initialised = true;
		}

		void trace() {
			if (finished) return;
			if (!initialised) init();
			if (energy <= 0) {
				finish();
				return;
			}

			while (energy > 0) {
				if (y < 0 || y >= WORLD_HEIGHT || Thread.currentThread().isInterrupted()) break;
				if (currentRayPosition >= radius - PROCESSING_EPSILON) break;

				int cx = x >> 4;
				int cz = z >> 4;
				int subY = y >> 4;
				// A ConcurrentHashMap lookup per voxel was the single most expensive step of the
				// raytrace; the sub-chunk only changes when the ray crosses a 16-block boundary.
				if (cx != lastCX || cz != lastCZ || subY != lastSubY) {
					lookupKey.update(cx, cz, subY);
					currentSnap = snapshots.get(lookupKey);
					currentChunkPos = lookupKey.getPos();
					lastCX = cx;
					lastCZ = cz;
					lastSubY = subY;
					cachedBitsetChunk = null;
					cachedDamageChunk = null;
				}

				SubChunkSnapshot snap = currentSnap;
				if (snap == null) {
					// Park with a fresh key: this one is stored in the waiting room, so it must not be the reused instance.
					park(new SubChunkKey(cx, cz, subY));
					lastCX = Integer.MIN_VALUE;
					return;
				}

				// A sub-chunk of pure air cannot take damage, so walking through it voxel by voxel
				// (16 steps per axis at minimum) is wasted work. Solve the ray/box intersection and
				// jump straight to where the ray leaves the box instead.
				if (AIR_SKIP && snap == SubChunkSnapshot.EMPTY) {
					double tExit = subChunkExitT(cx, cz, subY);
					if (tExit == tExit && tExit > currentRayPosition + JUMP_EPSILON) {
						double target = tExit + JUMP_EPSILON;
						if (target >= radius - PROCESSING_EPSILON) {
							currentRayPosition = radius;
							break;
						}
						resetDDA(target);
						lastCX = Integer.MIN_VALUE;
						continue;
					}
				}
				double t_exit_voxel = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
				double segmentLenInVoxel = t_exit_voxel - this.currentRayPosition;
				double segmentLenForProcessing;
				boolean stopAfterThisSegment = false;

				if (this.currentRayPosition + segmentLenInVoxel > radius - PROCESSING_EPSILON) {
					segmentLenForProcessing = Math.max(0.0, radius - this.currentRayPosition);
					stopAfterThisSegment = true;
				} else segmentLenForProcessing = segmentLenInVoxel;

				if (snap != SubChunkSnapshot.EMPTY && segmentLenForProcessing > PROCESSING_EPSILON) {
					Block block = snap.getBlock(x & 0xF, y & 0xF, z & 0xF);
					if (block != Blocks.air) {
						float resistance = getNukeResistance(block);
						if (resistance >= NUKE_RESISTANCE_CUTOFF) {
							energy = 0;
						} else {
							double energyLossFactor = getEnergyLossFactor(resistance);
							float damageDealt = (float) (energyLossFactor * segmentLenForProcessing);
							energy -= damageDealt;
							if (damageDealt > 0) {
								int bitIndex = ((WORLD_HEIGHT - 1 - y) << 8) | ((x & 0xF) << 4) | (z & 0xF);
								ChunkCoordIntPair chunkPos = currentChunkPos;
								if (BombConfig.explosionAlgorithm == 2) {
									if (chunkPos != cachedDamageChunk) {
										cachedDamageChunk = chunkPos;
										cachedDamage = damageMap.computeIfAbsent(chunkPos, cp -> new float[BITSET_SIZE]);
									}
									// Plain read-modify-write: racing rays may lose an increment, which only
									// ever makes the crater slightly smaller, and saves an atomic per voxel.
									cachedDamage[bitIndex] += damageDealt;
								} else if (energy > 0) {
									if (chunkPos != cachedBitsetChunk) {
										cachedBitsetChunk = chunkPos;
										cachedBitset = destructionMap.computeIfAbsent(chunkPos, posKey -> new ConcurrentBitSet(BITSET_SIZE));
									}
									cachedBitset.set(bitIndex);
								}
							}
						}
					}
				}
				this.currentRayPosition = t_exit_voxel;
				if (energy <= 0 || stopAfterThisSegment) break;

				if (tMaxX < tMaxY) {
					if (tMaxX < tMaxZ) {
						x += stepX;
						tMaxX += tDeltaX;
					} else {
						z += stepZ;
						tMaxZ += tDeltaZ;
					}
				} else {
					if (tMaxY < tMaxZ) {
						y += stepY;
						tMaxY += tDeltaY;
					} else {
						z += stepZ;
						tMaxZ += tDeltaZ;
					}
				}
			}
			finish();
		}

		/**
		 * 停等：等主线程给这个 sub-chunk 拍快照。
		 *
		 * 原来的写法会丢任务：主线程可能在我们的 map 查询和 add 之间把等待队列摘走并
		 * 重新入队，我们随后 add 进的就是一个没人再读的孤儿队列 —— latch 永远减不到 0，
		 * 爆炸实体永远不消失。这里用 compute 做原子插入，插完再复查快照，
		 * 如果快照已经就绪就把自己这个队列重新丢回 rayQueue，窗口就关死了。
		 */
		private void park(SubChunkKey key) {
			waitingRoom.compute(key, (k, waiters) -> {
				ConcurrentLinkedQueue<RayTask> queue = waiters;
				if (queue == null) {
					queue = new ConcurrentLinkedQueue<>();
					highPriorityReactiveQueue.add(k);
				}
				queue.add(this);
				return queue;
			});
			if (snapshots.get(key) != null) {
				waitingRoom.computeIfPresent(key, (k, waiters) -> {
					rayQueue.addAll(waiters);
					return null;
				});
			}
		}

		private void finish() {
			if (!finished) {
				finished = true;
				latch.countDown();
			}
		}

		/** Absolute t at which the ray leaves the sub-chunk box it is currently inside. */
		private double subChunkExitT(int cx, int cz, int subY) {
			double tx = axisExit(explosionX, dirX, cx << 4, (cx << 4) + 16);
			double ty = axisExit(explosionY, dirY, subY << 4, (subY << 4) + 16);
			double tz = axisExit(explosionZ, dirZ, cz << 4, (cz << 4) + 16);
			return Math.min(tx, Math.min(ty, tz));
		}

		private double axisExit(double origin, double dir, double lo, double hi) {
			if (dir > RAY_DIRECTION_EPSILON) return (hi - origin) / dir;
			if (dir < -RAY_DIRECTION_EPSILON) return (lo - origin) / dir;
			return Double.POSITIVE_INFINITY;
		}

		/** Re-seeds the DDA state at an arbitrary position along the ray (used after a jump). */
		private void resetDDA(double t) {
			this.currentRayPosition = t;
			double posX = explosionX + dirX * t;
			double posY = explosionY + dirY * t;
			double posZ = explosionZ + dirZ * t;
			this.x = MathHelper.floor_double(posX);
			this.y = MathHelper.floor_double(posY);
			this.z = MathHelper.floor_double(posZ);
			// tMax is an absolute t along the ray, so the offset to the next boundary has to be added to t;
			// init() gets away without it only because it starts at t = 0.
			this.tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : t + ((stepX > 0 ? (this.x + 1 - posX) : (posX - this.x)) * this.tDeltaX);
			this.tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : t + ((stepY > 0 ? (this.y + 1 - posY) : (posY - this.y)) * this.tDeltaY);
			this.tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : t + ((stepZ > 0 ? (this.z + 1 - posZ) : (posZ - this.z)) * this.tDeltaZ);
		}

		private double getEnergyLossFactor(float resistance) {
			double effectiveDist = Math.max(this.currentRayPosition, MIN_EFFECTIVE_DIST_FOR_ENERGY_CALC);
			return (Math.pow(resistance + 1.0, 3.0 * (effectiveDist / radius)) - 1.0);
		}
	}
}
