package com.hbm.entity.effect;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import com.hbm.packet.PacketDispatcher;
import com.hbm.packet.toclient.ExplosionKnockbackPacket;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * One half of a portal pair.
 *
 * The portal sits flush on the face of the block it was shot at, so walls get an upright
 * portal while floors and ceilings get a horizontal one. Everything is expressed in the
 * portal's own basis (right, up, normal): an entity is projected into the source basis
 * and rebuilt in the target basis, which handles wall-to-floor and wall-to-wall travel
 * with the same code path.
 *
 * @author lead
 */
public class EntityPortal extends Entity {

	public static final int MAX_LIFE = 20 * 60 * 2;

	/**
	 * Signed distance of the entity to a given portal on the previous tick.
	 * The key is suffixed with the portal entity id: two portals whose detection boxes overlap
	 * (portals placed close together) would otherwise overwrite each other's value and the
	 * crossing test would never see a sign change.
	 */
	private static final String NBT_LAST_DIST_PREFIX = "hbmPortalDist_";
	/** World tick at which the entity was last inside a given portal's opening. */
	private static final String NBT_INSIDE_PREFIX = "hbmPortalInside_";
	/** Ticks the entity has to be away before entering counts as a fresh entry. */
	private static final long REENTRY_TICKS = 2L;
	/** Momentum memory: the speed the entity was last spat out with, and when. */
	private static final String NBT_LAST_EXIT_SPEED = "hbmPortalExitSpeed";
	private static final String NBT_LAST_EXIT_TICK = "hbmPortalExitTick";
	/** How long the remembered speed stays valid without another pass. */
	private static final long EXIT_SPEED_MEMORY = 60L;
	/** Portal the entity most recently came out of; ignored while the entity is still next to it. */
	private static final String NBT_EXIT_ID = "hbmPortalExitId";
	/** When the exit guard was armed, so it can expire even if you never move. */
	private static final String NBT_EXIT_TICK = "hbmPortalExitTick";
	private static final long EXIT_GUARD_TIME = 40L;
	/** Minimum ticks between two teleports of the same entity, so a pair cannot ping-pong. */
	private static final String NBT_COOLDOWN = "hbmPortalCooldown";
	private static final int TELEPORT_COOLDOWN = 3;

	/** Half extents of the opening, in blocks. */
	public static final double HALF_WIDTH = 1.0D;
	public static final double HALF_HEIGHT = 1.2D;

	/** Extra velocity along the exit normal, so you get visibly spat out. */
	private static final double EXIT_BOOST = 0.35D;
	/** Momentum multiplier per pass. 1.0 = exact conservation. */
	private static final double SPEED_GAIN = 1.00D;

	/** Speed below which the gain is not applied, so normal walking is unaffected. */
	private static final double GAIN_THRESHOLD = 0.12D;
	/** No hard cap any more - momentum keeps building as long as you keep looping. */
	private static final double MAX_SPEED = Double.MAX_VALUE;

	private int linkedEntityId = -1;
	/** Set when this portal was restored from a save file; such portals are discarded on the first tick. */
	private boolean loadedFromDisk = false;
	/** Ticks this portal has been without a partner, used to clean up orphans. */
	private int unpairedTicks = 0;
	/** Last sampled position per entity id: {x, y, z, tick}. Players need this because their
	 *  server side posX - prevPosX is always 0 (the position arrives from packets before the tick). */
	private final Map<Integer, double[]> lastSample = new HashMap<>();

	/** How long a portal may exist without a partner before it removes itself. */
	private static final int UNPAIRED_TIMEOUT = 20 * 20;

	public EntityPortal(World world) {
		super(world);
		this.noClip = true;
		this.ignoreFrustumCheck = true;
		this.setSize(2.0F, 2.4F);
	}

	public EntityPortal(World world, double x, double y, double z, int side, int index) {
		this(world);
		this.setPosition(x, y, z);
		// The render manager interpolates from lastTickPos to pos. A freshly spawned entity has
		// lastTickPos at 0/0/0, so without this the portal visibly flies in from the world origin
		// over the first few frames - "it changes place after it appears".
		this.prevPosX = this.lastTickPosX = x;
		this.prevPosY = this.lastTickPosY = y;
		this.prevPosZ = this.lastTickPosZ = z;
		this.setSide(side);
		this.setIndex(index);
	}

	@Override
	protected void entityInit() {
		// 20 = which half of the pair (0/1), 21 = the face it is stuck to (0..5)
		this.dataWatcher.addObject(20, Byte.valueOf((byte) 0));
		this.dataWatcher.addObject(21, Byte.valueOf((byte) 0));
	}

	public int getIndex() {
		return this.dataWatcher.getWatchableObjectByte(20) & 1;
	}

	public void setIndex(int index) {
		this.dataWatcher.updateObject(20, Byte.valueOf((byte) (index & 1)));
	}

	/** ForgeDirection ordinal of the face the portal is attached to. */
	public int getSide() {
		return this.dataWatcher.getWatchableObjectByte(21) & 7;
	}

	public void setSide(int side) {
		this.dataWatcher.updateObject(21, Byte.valueOf((byte) (side & 7)));

		// Mirror the orientation into the vanilla rotation fields. Spawn packets carry yaw/pitch
		// but not the data watcher, so this is what lets a freshly spawned portal render in the
		// right orientation on the first frame instead of flipping a tick later.
		switch(side) {
		case 0: this.rotationPitch = 90F; this.rotationYaw = 0F; break;    // facing down
		case 1: this.rotationPitch = -90F; this.rotationYaw = 0F; break;   // facing up
		case 2: this.rotationPitch = 0F; this.rotationYaw = 180F; break;   // -Z
		case 3: this.rotationPitch = 0F; this.rotationYaw = 0F; break;     // +Z
		case 4: this.rotationPitch = 0F; this.rotationYaw = 270F; break;   // -X
		case 5: this.rotationPitch = 0F; this.rotationYaw = 90F; break;    // +X
		default: this.rotationPitch = 0F; this.rotationYaw = 0F; break;
		}
	}

	/** Basis built from the vanilla rotation fields, so the client can use it before metadata arrives. */
	public void getRenderBasis(double[] out) {
		double nx, ny, nz;
		double ux = 0, uy = 1, uz = 0;

		if(Math.abs(this.rotationPitch) > 45F) {
			ny = this.rotationPitch > 0F ? -1D : 1D;
			nx = 0; nz = 0;
			uy = 0; uz = this.rotationPitch > 0F ? 1D : -1D;
		} else {
			double rad = Math.toRadians(this.rotationYaw);
			nx = Math.sin(rad); ny = 0; nz = Math.cos(rad);
		}

		out[0] = uy * nz - uz * ny;
		out[1] = uz * nx - ux * nz;
		out[2] = ux * ny - uy * nx;
		out[3] = ux;
		out[4] = uy;
		out[5] = uz;
		out[6] = nx;
		out[7] = ny;
		out[8] = nz;
	}

	public void setLinked(int entityId) {
		this.linkedEntityId = entityId;
	}

	public int getLinked() {
		return this.linkedEntityId;
	}

	/** out[0..2] = right, out[3..5] = up, out[6..8] = normal. */
	public void getBasis(double[] out) {

		double nx = 0, ny = 0, nz = 0;
		double ux = 0, uy = 1, uz = 0;

		switch(this.getSide()) {
		case 0: ny = -1; uz = 1; uy = 0; break;   // DOWN
		case 1: ny = 1; uz = -1; uy = 0; break;   // UP
		case 2: nz = -1; break;                   // NORTH
		case 3: nz = 1; break;                    // SOUTH
		case 4: nx = -1; break;                   // WEST
		case 5: nx = 1; break;                    // EAST
		default: nz = 1; break;
		}

		// right = up x normal
		out[0] = uy * nz - uz * ny;
		out[1] = uz * nx - ux * nz;
		out[2] = ux * ny - uy * nx;
		out[3] = ux;
		out[4] = uy;
		out[5] = uz;
		out[6] = nx;
		out[7] = ny;
		out[8] = nz;
	}

	private double normalX() { double[] b = new double[9]; this.getBasis(b); return b[6]; }
	private double normalY() { double[] b = new double[9]; this.getBasis(b); return b[7]; }
	private double normalZ() { double[] b = new double[9]; this.getBasis(b); return b[8]; }

	/** Yaw of the portal normal in degrees; meaningless (0) for floor and ceiling portals. */
	public float getNormalYaw() {
		double[] b = new double[9];
		this.getBasis(b);
		if(Math.abs(b[7]) > 0.5D) return 0F;
		return (float) Math.toDegrees(Math.atan2(b[6], b[8]));
	}

	@Override
	public void onUpdate() {

		// portals are session objects. A world save can contain them if the game was killed
		// mid-session, and those copies have no gun pointing at them any more - drop them.
		if(this.loadedFromDisk) {
			this.closePair();
			this.setDead();
			return;
		}

		super.onUpdate();

		if(this.worldObj.isRemote) {
			if(this.rand.nextInt(3) == 0) {
				double[] b = new double[9];
				this.getBasis(b);
				double lx = (this.rand.nextDouble() - 0.5D) * HALF_WIDTH * 1.7D;
				double ly = (this.rand.nextDouble() - 0.5D) * HALF_HEIGHT * 1.7D;
				this.worldObj.spawnParticle("happyVillager",
						this.posX + b[0] * lx + b[3] * ly + b[6] * 0.05D,
						this.posY + b[1] * lx + b[4] * ly + b[7] * 0.05D,
						this.posZ + b[2] * lx + b[5] * ly + b[8] * 0.05D,
						0, 0.02D, 0);
			}
			return;
		}

		if(this.ticksExisted > MAX_LIFE) {
			this.closePair();
			this.setDead();
			return;
		}

		// a portal whose partner vanished (replaced, killed, chunk lost) would otherwise
		// linger forever as an unusable visual
		if(this.resolvePartner() == null) {
			if(++this.unpairedTicks > UNPAIRED_TIMEOUT) {
				this.setDead();
				return;
			}
		} else {
			this.unpairedTicks = 0;
		}

		// diagnostic: an entity has no business moving on its own, log it if it does
		if(Math.abs(this.posX - this.lastTickPosX) > 1.0E-4D
				|| Math.abs(this.posY - this.lastTickPosY) > 1.0E-4D
				|| Math.abs(this.posZ - this.lastTickPosZ) > 1.0E-4D) {
			LogManager.getLogger("HBM").info("[portal] id=" + this.getEntityId() + " side=" + this.getSide()
					+ " moved " + String.format("%.3f/%.3f/%.3f -> %.3f/%.3f/%.3f",
							this.lastTickPosX, this.lastTickPosY, this.lastTickPosZ,
							this.posX, this.posY, this.posZ));
		}

		this.teleportTick();
	}

	private void closePair() {
		Entity other = this.getOther();
		if(other instanceof EntityPortal) ((EntityPortal) other).setDead();
	}

	private Entity getOther() {
		if(this.linkedEntityId < 0) return null;
		return this.worldObj.getEntityByID(this.linkedEntityId);
	}

	/**
	 * The partner lookup, with a fallback: entity ids in the gun's NBT go stale when a portal
	 * is unloaded and reloaded, so if the stored id does not resolve we simply pair with the
	 * nearest portal of the other colour.
	 */
	private EntityPortal resolvePartner() {

		Entity stored = this.getOther();
		if(stored instanceof EntityPortal && !stored.isDead) return (EntityPortal) stored;

		EntityPortal best = null;
		double bestDistance = 256.0D * 256.0D;

		for(Object o : this.worldObj.loadedEntityList) {
			if(!(o instanceof EntityPortal) || o == this) continue;
			EntityPortal candidate = (EntityPortal) o;
			if(candidate.isDead || candidate.getIndex() == this.getIndex()) continue;
			double distance = candidate.getDistanceSqToEntity(this);
			if(distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}

		if(best != null) {
			this.linkedEntityId = best.getEntityId();
			best.linkedEntityId = this.getEntityId();
			LogManager.getLogger("HBM").info("[portal] re-paired " + this.getEntityId() + " <-> " + best.getEntityId());
		}

		return best;
	}

	@SuppressWarnings("unchecked")
	private void teleportTick() {

		// Fall damage is worked out from the accumulated fallDistance during packet handling,
		// which runs before this tick - so a portal cannot save you after the fact. Keeping the
		// counter at zero for anyone close to the opening means a fall into a portal never builds
		// up damage in the first place. This works without any event registration, so it also
		// applies on a client that was only hot reloaded.
		for(Object o : this.worldObj.playerEntities) {
			net.minecraft.entity.player.EntityPlayer player = (net.minecraft.entity.player.EntityPlayer) o;

			// Only while the player is actually falling into this opening: laterally inside the
			// frame and close to the plane. A blanket radius would make the area around a portal
			// fall-damage proof, which is not what we want.
			if(player.getDistanceSqToEntity(this) > 64.0D) continue;
			if(!this.insideOpening(player)) continue;

			double planeDistance = this.signedDistance(player);
			boolean fallingIn = this.getSide() <= 1
					? planeDistance > -0.5D && planeDistance < 8.0D
					: Math.abs(planeDistance) < 3.0D;

			if(fallingIn) player.fallDistance = 0F;
		}

		EntityPortal other = this.resolvePartner();

		// two portals stacked on the same block would just teleport the entity in place
		if(other != null && other.getDistanceSqToEntity(this) < 0.25D) other = null;

		AxisAlignedBB box = this.boundingBox.expand(0.45D, 0.45D, 0.45D);
		List<Entity> entities = this.worldObj.getEntitiesWithinAABBExcludingEntity(this, box);

		// keep the sample map from growing forever on a busy server
		if(this.lastSample.size() > 32) {
			long limit = this.worldObj.getTotalWorldTime() - 5L;
			Iterator<double[]> it = this.lastSample.values().iterator();
			while(it.hasNext()) {
				if((long) it.next()[3] < limit) it.remove();
			}
		}

		for(Entity entity : entities) {

			if(entity instanceof EntityPortal) continue;
			if(entity.isDead || entity.ridingEntity != null) continue;

			// Sample the velocity ourselves. For players, posX - prevPosX is always zero on the
			// server: packets move the player before the entity tick, so prevPos is refreshed to
			// the new position. Keeping our own per tick sample gives the real speed.
			double vx, vy, vz;
			long now = this.worldObj.getTotalWorldTime();

			// Sample the position of every entity, not just players. An arrow that hits the wall
			// behind the portal has its motion zeroed in the very tick it arrives, so the motion
			// vector is useless by the time we see it - the previous tick's sample still holds the
			// real flight speed.
			double sampleX = 0D, sampleY = 0D, sampleZ = 0D;
			double[] last = this.lastSample.get(entity.getEntityId());
			if(last != null && now - (long) last[3] <= 2L) {
				sampleX = entity.posX - last[0];
				sampleY = entity.posY - last[1];
				sampleZ = entity.posZ - last[2];
			}
			this.lastSample.put(entity.getEntityId(), new double[] { entity.posX, entity.posY, entity.posZ, now });

			if(entity instanceof net.minecraft.entity.player.EntityPlayer) {
				vx = sampleX;
				vy = sampleY;
				vz = sampleZ;
			} else {
				vx = entity.motionX;
				vy = entity.motionY;
				vz = entity.motionZ;
				if(Math.abs(vx) + Math.abs(vy) + Math.abs(vz) < 0.01D) {
					vx = sampleX;
					vy = sampleY;
					vz = sampleZ;
				}
			}

			// drop the one-tick spike caused by the teleport itself
			double rawSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);
			if(rawSpeed > 128.0D) {
				double scale = 128.0D / rawSpeed;
				vx *= scale; vy *= scale; vz *= scale;
				rawSpeed = 128.0D;
			}

			// No cooldown: teleport the moment the entity crosses the plane. Standing still on
			// the exit side keeps the sign unchanged, so it does not oscillate. The exit portal
			// stays ignored while the entity is still within arm's reach of it.
			double distance = this.signedDistance(entity);
			NBTTagCompound data = entity.getEntityData();

			// Horizontal portals have to fire *before* the entity lands: the floor block is solid,
			// so a fast faller hits it, the client zeroes the velocity in the collision, and we
			// would only see the leftover speed. Predict the crossing instead.
			double vNormalComponent = vx * this.normalX() + vy * this.normalY() + vz * this.normalZ();
			boolean willLand = this.getSide() <= 1 && distance > 0D && vNormalComponent < -0.01D
					&& distance <= -vNormalComponent * 1.5D + 1.0D;
			boolean withinFrame = this.insideOpening(entity);

			// Swept test: a fast faller can jump from above the opening to below it within one
			// tick, never being "inside" at either sample. Walk the movement segment instead and
			// check whether it passes through the plane while inside the frame.
			boolean swept = false;
			if(last != null && now - (long) last[3] <= 2L) {
				double[] nb = new double[9];
				this.getBasis(nb);
				double prevDistance = (last[0] - this.posX) * nb[6] + (last[1] - this.posY) * nb[7] + (last[2] - this.posZ) * nb[8];

				if((prevDistance > 0D && distance <= 0D) || (prevDistance < 0D && distance >= 0D)) {
					double denom = prevDistance - distance;
					double t = denom == 0D ? 0D : prevDistance / denom;
					double cx = last[0] + (entity.posX - last[0]) * t - this.posX;
					double cy = last[1] + (entity.posY - last[1]) * t + entity.height * 0.5D - this.posY;
					double cz = last[2] + (entity.posZ - last[2]) * t - this.posZ;

					double right = cx * nb[0] + cy * nb[1] + cz * nb[2];
					double up = cx * nb[3] + cy * nb[4] + cz * nb[5];
					double halfUp = this.getSide() <= 1 ? HALF_HEIGHT + 0.5D : HALF_HEIGHT + entity.height * 0.5D;

					swept = Math.abs(right) <= HALF_WIDTH + 1.0D && Math.abs(up) <= halfUp + 0.5D;
				}
			}

			boolean inside = swept || (withinFrame && (this.getSide() > 1 || Math.abs(distance) < 0.15D || willLand));

			// The "you just came out of here" guard has to be cleared by *lateral* distance.
			// It used to be cleared by the distance to the portal plane, which for a floor portal
			// stays ~0 while you walk around on top of it - the portal then ignored you forever
			// unless you jumped off the plane. Walking out of the opening clears it now.
			boolean justExited = data.getInteger(NBT_EXIT_ID) == this.getEntityId();
			if(justExited && (!withinFrame || Math.abs(distance) > 1.0D
					|| this.worldObj.getTotalWorldTime() - data.getLong(NBT_EXIT_TICK) > EXIT_GUARD_TIME)) {
				// Walking around on the exit portal never leaves the frame and never changes the
				// distance to the plane, so without the timer the only way out was jumping.
				data.removeTag(NBT_EXIT_ID);
				justExited = false;
			}

			if(!inside) continue;

			// Fresh entry: the entity has not been inside this opening for a few ticks. Using a
			// timestamp instead of a boolean matters because the flag would stay "inside" forever
			// once the entity leaves the scanning box without us ever seeing it again.
			String insideKey = NBT_INSIDE_PREFIX + this.getEntityId();
			long now2 = this.worldObj.getTotalWorldTime();
			boolean reentered = now2 - data.getLong(insideKey) >= REENTRY_TICKS;
			data.setLong(insideKey, now2);

			if(this.worldObj.getTotalWorldTime() < data.getLong(NBT_COOLDOWN)) continue;

			if(justExited) continue;

			String distKey = NBT_LAST_DIST_PREFIX + this.getEntityId();
			double previous = distance;
			if(data.hasKey(distKey)) previous = data.getDouble(distKey);
			data.setDouble(distKey, distance);

			boolean crossed = (previous > 0D && distance <= 0D) || (previous < 0D && distance >= 0D);

			// A wall portal can never actually be "crossed": the wall block stops the entity
			// ~0.3 blocks short of the plane, so the sign never flips. Walking towards it has to
			// count as entering, exactly like the floor portals do.
			// Walking into a wall means the client stops you: the per tick delta, and therefore the
			// sampled velocity, is zero. A velocity condition can never be met while pressed
			// against the block, so plain proximity is what "walk into the portal" has to use.
			boolean approaching = distance > 0D && distance < 0.55D;

			// A horizontal portal is a hole in the floor: there is no plane to cross while
			// walking on top of it, so it fires when the entity *enters* the opening. Landing
			// on it counts as entering too, but standing on it does not re-trigger.
			// Projectiles can be inside the opening with their motion already zeroed: an arrow
			// that hit the wall behind the portal has motionX/Y/Z cleared in the same tick, so a
			// velocity based test never fires. Being close to the plane inside the frame is enough.
			boolean touching = Math.abs(distance) < 0.4D;
			// Horizontal portals also fire on a plain plane crossing: with a hole underneath the
			// portal you fall straight through the plane, that is the cleanest signal there is.
			boolean trigger = this.getSide() <= 1 ? (reentered || swept || crossed)
					: (crossed || approaching || (reentered && touching));
			if(!trigger) continue;

			if(other == null) {
				if(this.ticksExisted % 20 == 0) {
					LogManager.getLogger("HBM").info("[portal] id=" + this.getEntityId() + " idx=" + this.getIndex()
							+ " has " + entity.getClass().getSimpleName() + " inside but no partner");
				}
				continue;
			}

			this.sendThrough(entity, other, vx, vy, vz);
		}
	}

	/** Signed distance of the entity's feet from the portal plane, along the portal normal. */
	private double signedDistance(Entity entity) {
		double[] b = new double[9];
		this.getBasis(b);
		return (entity.posX - this.posX) * b[6]
				+ (entity.posY - this.posY) * b[7]
				+ (entity.posZ - this.posZ) * b[8];
	}

	/** Lateral extent check: is the entity horizontally within the opening frame? */
	public boolean insideOpening(Entity entity) {

		double[] b = new double[9];
		this.getBasis(b);

		double cx = entity.posX - this.posX;
		double cy = entity.posY + entity.height * 0.5D - this.posY;
		double cz = entity.posZ - this.posZ;

		double alongRight = cx * b[0] + cy * b[1] + cz * b[2];
		if(Math.abs(alongRight) > HALF_WIDTH + entity.width * 0.5D) return false;

		double alongUp = cx * b[3] + cy * b[4] + cz * b[5];

		// on a horizontal portal "up" lies in the floor plane, so the body height is irrelevant
		if(this.getSide() <= 1) return Math.abs(alongUp) < HALF_HEIGHT + 0.5D;

		return Math.abs(alongUp) < HALF_HEIGHT + entity.height * 0.5D;
	}

	/** Enough room for a standing entity at this position? Checks the whole body height. */
	private boolean isSpaceFree(net.minecraft.world.World world, double x, double y, double z) {
		int bx = (int) Math.floor(x);
		int by = (int) Math.floor(y);
		int bz = (int) Math.floor(z);

		for(int i = 0; i < 3; i++) {
			net.minecraft.block.Block block = world.getBlock(bx, by + i, bz);
			if(block.getMaterial().blocksMovement()) return false;
			if(block.getCollisionBoundingBoxFromPool(world, bx, by + i, bz) != null) return false;
		}
		return true;
	}

	private void sendThrough(Entity entity, EntityPortal target, double vx, double vy, double vz) {

		double[] from = new double[9];
		double[] to = new double[9];
		this.getBasis(from);
		target.getBasis(to);

		double dx = entity.posX - this.posX;
		double dy = entity.posY - this.posY;
		double dz = entity.posZ - this.posZ;

		double localRight = dx * from[0] + dy * from[1] + dz * from[2];
		double localUp = dx * from[3] + dy * from[4] + dz * from[5];

		// walls need a bit of clearance so the entity does not end up inside the block;
		// floors and ceilings only need to clear the surface, otherwise you spawn in mid air
		double clearance = target.getSide() <= 1 ? 0.02D : 0.6D;

		double destX = target.posX + to[0] * localRight + to[3] * localUp + to[6] * clearance;
		double destY = target.posY + to[1] * localRight + to[4] * localUp + to[7] * clearance;
		double destZ = target.posZ + to[2] * localRight + to[5] * localUp + to[8] * clearance;

		// The exit can end up inside a block: portal in a corner, offset pushed sideways into a
		// wall, or the portal itself embedded. Step out along the exit normal until there is
		// room for the entity's body.
		double push = 0D;
		boolean found = false;
		while(push <= 4.0D) {
			if(this.isSpaceFree(entity.worldObj,
					destX + to[6] * push, destY + to[7] * push, destZ + to[8] * push)) {
				found = true;
				break;
			}
			push += 0.25D;
		}

		if(!found) {
			// Nothing free straight out of the portal: try a small neighbourhood before giving up.
			// A portal shot through a 1 block gap often has room one block up or to the side.
			int bx = (int) Math.floor(destX);
			int by = (int) Math.floor(destY);
			int bz = (int) Math.floor(destZ);
			int[][] offsets = {
					{0, 1, 0}, {0, 2, 0}, {0, 3, 0},
					{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
					{1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
					{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1}
			};

			for(int[] o : offsets) {
				double cx = bx + o[0] + 0.5D;
				double cy = by + o[1];
				double cz = bz + o[2] + 0.5D;
				if(this.isSpaceFree(entity.worldObj, cx, cy, cz)) {
					destX = cx;
					destY = cy;
					destZ = cz;
					found = true;
					break;
				}
			}
		}

		if(!found) {
			// Last resort: drop the lateral/vertical offset and come out dead centre. Entering at
			// the edge of a tall portal can map the feet into the floor block in front of it.
			double cx = target.posX + to[6] * clearance;
			double cy = target.posY + to[7] * clearance;
			double cz = target.posZ + to[8] * clearance;
			if(this.isSpaceFree(entity.worldObj, cx, cy, cz)) {
				destX = cx;
				destY = cy;
				destZ = cz;
				found = true;
			}
		}

		if(!found) {
			// Still nowhere to stand - putting the entity there would suffocate it.
			// Refuse the teleport instead of killing the player.
			StringBuilder probe = new StringBuilder();
			for(int i = 0; i <= 4; i++) {
				double px2 = target.posX + to[6] * (clearance + i * 0.5D);
				double py2 = target.posY + to[7] * (clearance + i * 0.5D);
				double pz2 = target.posZ + to[8] * (clearance + i * 0.5D);
				int bx2 = (int) Math.floor(px2);
				int by2 = (int) Math.floor(py2);
				int bz2 = (int) Math.floor(pz2);
				probe.append(" [").append(bx2).append(',').append(by2).append(',').append(bz2).append("]=")
						.append(entity.worldObj.getBlock(bx2, by2, bz2).getUnlocalizedName());
			}

			LogManager.getLogger("HBM").info("[portal] refused teleport for " + entity.getClass().getSimpleName()
					+ ": no free space at " + (int) destX + "/" + (int) destY + "/" + (int) destZ
					+ " | target=" + target.getEntityId() + " side=" + target.getSide()
					+ " at " + String.format("%.2f/%.2f/%.2f", target.posX, target.posY, target.posZ)
					+ " | probe:" + probe);
			return;
		}

		destX += to[6] * push;
		destY += to[7] * push;
		destZ += to[8] * push;

		double rawSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);

		double vRight = vx * from[0] + vy * from[1] + vz * from[2];
		double vUp = vx * from[3] + vy * from[4] + vz * from[5];
		double vNormal = vx * from[6] + vy * from[7] + vz * from[8];

		// Come out of the target moving *away* from its surface: the normal component is
		// mirrored, the tangential components are kept (that is what makes a fall loop
		// conserve speed instead of pushing you back into the wall).
		double nvx = to[0] * vRight + to[3] * vUp - to[6] * vNormal;
		double nvy = to[1] * vRight + to[4] * vUp - to[7] * vNormal;
		double nvz = to[2] * vRight + to[5] * vUp - to[8] * vNormal;

		// No artificial push: walking through keeps your walking speed, falling through
		// keeps the speed you picked up falling, only rotated into the exit portal's frame.

		// Momentum is conserved by default: the velocity vector is only rotated into the exit
		// portal's frame. Config option 1.36_portalSpeedGain can add a per-pass multiplier for
		// people who want portal loops to keep accelerating like an endless fall.
		double speed = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz);

		// Hardcoded: pushed out of the exit portal along its normal.
		nvx += to[6] * EXIT_BOOST;
		nvy += to[7] * EXIT_BOOST;
		nvz += to[8] * EXIT_BOOST;
		speed = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz);

		double gain = SPEED_GAIN;
		if(gain > 1.0D && speed > 0.05D) {
			double scale = gain;
			nvx *= scale;
			nvy *= scale;
			nvz *= scale;
			speed *= scale;
		}

		LogManager.getLogger("HBM").info("[portal/v4] " + entity.getClass().getSimpleName() + " "
				+ this.getEntityId() + " -> " + target.getEntityId()
				+ " from side " + this.getSide() + " to side " + target.getSide()
				+ String.format(" speed %.3f -> %.3f", rawSpeed, Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz)));

		entity.fallDistance = 0F;
		// fall damage is computed from the position packet, before this tick - a grace window
		// is the only reliable way to stop a fast fall from killing you as you enter
		entity.getEntityData().setLong(com.hbm.handler.PortalFallProtection.NBT_GRACE, this.worldObj.getTotalWorldTime() + 40L);
		entity.getEntityData().setLong(NBT_COOLDOWN, this.worldObj.getTotalWorldTime() + TELEPORT_COOLDOWN);

		if(entity instanceof EntityPlayerMP) {
			EntityPlayerMP player = (EntityPlayerMP) entity;

			// Turn the view with the portal so forward stays "out of the portal". Only rotationYaw
			// is touched: changing prevRotationYaw / rotationYawHead as well fights the client's
			// own interpolation for the same tick and makes the camera jitter.
			float deltaYaw = net.minecraft.util.MathHelper.wrapAngleTo180_float(
					target.getNormalYaw() - this.getNormalYaw());
			if(deltaYaw != 0F) {
				player.rotationYaw += deltaYaw;
				player.rotationYawHead += deltaYaw;
			}

			player.setPositionAndUpdate(destX, destY + 0.001D, destZ);
		} else {
			entity.setPosition(destX, destY, destZ);
		}

		// remember where we came out: standing there must not count as a crossing
		entity.getEntityData().setDouble(NBT_LAST_DIST_PREFIX + target.getEntityId(), target.signedDistance(entity));
		entity.getEntityData().setLong(NBT_INSIDE_PREFIX + target.getEntityId(), this.worldObj.getTotalWorldTime());
		entity.getEntityData().setInteger(NBT_EXIT_ID, target.getEntityId());
		entity.getEntityData().setLong(NBT_EXIT_TICK, this.worldObj.getTotalWorldTime());

		entity.motionX = nvx;
		entity.motionY = nvy;
		entity.motionZ = nvz;

		// A horizontal exit right at ground level would be eaten by ground friction (x0.546 per
		// tick) before the entity reaches the other portal. A small upward component keeps it
		// airborne, which is what makes a wall-to-wall loop actually travel.
		if(Math.abs(nvy) < 0.1D && Math.abs(nvx) + Math.abs(nvz) > 0.25D) {
			nvy = 0.1D;
		}

		if(entity instanceof EntityPlayerMP) {
			// The S08PacketPlayerPosLook sent by setPositionAndUpdate zeroes the client's motion,
			// and S12 velocity packets are not reliably applied to the local player. Push the exit
			// velocity through our own packet instead - the client adds it to thePlayer.motion.
			PacketDispatcher.wrapper.sendTo(
					new ExplosionKnockbackPacket(Vec3.createVectorHelper(nvx, nvy, nvz)),
					(EntityPlayerMP) entity);
		}

		this.worldObj.playSoundEffect(destX, destY, destZ, "mob.endermen.portal", 1.0F, 1.2F);

		for(int i = 0; i < 6; i++) {
			this.worldObj.spawnParticle("happyVillager",
					destX + (this.rand.nextDouble() - 0.5D) * 1.6D,
					destY + (this.rand.nextDouble() - 0.5D) * 1.6D,
					destZ + (this.rand.nextDouble() - 0.5D) * 1.6D,
					0, 0.05D, 0);
		}
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		this.loadedFromDisk = true;
		this.linkedEntityId = nbt.getInteger("linked");
		this.setIndex(nbt.getInteger("index"));
		this.setSide(nbt.getInteger("side"));
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		nbt.setInteger("linked", this.linkedEntityId);
		nbt.setInteger("index", this.getIndex());
		nbt.setInteger("side", this.getSide());
	}

	@Override
	public boolean canBeCollidedWith() {
		return false;
	}

	@Override
	public boolean isInRangeToRenderDist(double distance) {
		return distance < 4096D;
	}
}
