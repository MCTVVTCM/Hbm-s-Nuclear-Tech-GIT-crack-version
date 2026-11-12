package com.hbm.entity.misc;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

import com.hbm.main.MainRegistry;

/**
 * 筋斗云。
 *
 * 骑着就能飞：方向完全跟着骑手的视线走（视线是每 tick 同步的，不需要额外的按键包），
 * 潜行键往下压。没人骑的时候会在原地停 5 秒然后自己散掉。
 *
 * @author qwq
 */
public class EntityJindouYun extends Entity {

	/* ===================== 可调参数：全部写死在代码里，不走 config ===================== */

	/** 巡航速度，格/tick（20 tick = 1 秒）。16 ≈ 320 格/秒，48 ≈ 960 格/秒 */
	public static double SPEED = 2.5D;
	/** 单 tick 位移上限，0 = 不限制 */
	public static double MAX_STEP = 0.0D;
	/** true = 撞墙停（每 tick 一次射线）；false = 穿墙直飞，成本恒定 O(1) */
	public static boolean COLLIDE = true;
	/** 客户端预测：true = 本地立刻响应（手感好）；false = 退回纯服务端权威（慢 1~2 tick，但绝不和服务端打架） */
	public static boolean CLIENT_PREDICTION = true;
	/** 客户端权威：客户端算完直接同步给服务端，服务端只做范围校验。关掉就退回"双头模拟"，会有回弹 */
	public static boolean CLIENT_AUTHORITATIVE = true;
	/** 加速倍率（按住 Ctrl）。别调太高：一 tick 跨几个区块，区块加载永远追不上，掉帧的是客户端 */
	public static double BOOST_MULT = 30.0D;
	/** 减速倍率（按住 Shift） */
	public static double BRAKE_MULT = 0.3D;
	/** 加速/减速的靠拢速度：1 = 立刻满速，0.35 ≈ 6 tick 到 90%（起步/收油有惯性） */
	public static double ACCELERATION = 0.35D;
	/** 松手后的滑行衰减：0.94 ≈ 11 tick 掉一半，0.88 ≈ 5 tick。越小越"刹车" */
	public static double RELEASE_DAMP = 0.94D;
	/** 预取深度（沿飞行方向的列数）。2.1 格/tick 只需提前 3~4 列，8 列纯属浪费 */
	public static int PREFETCH_DEPTH = 4;
	/** 预取宽度（垂直飞行方向，左右各多少列）。13 列宽等于每 tick 扫一大片，改成 7 列 */
	public static int PREFETCH_HALF_WIDTH = 3;
	/**
	 * 每 tick 最多检查/排队多少个区块。**默认 0 = 关闭**。
	 *
	 * 为什么关：1.7.10 的 ChunkProviderServer 只卸载 PlayerManager 登记过的区块
	 * （登记时调用 unloadChunk → chunksToUnload）。预取的区块在玩家视野之外，
	 * 从没被 PlayerManager 登记，于是**永远不会进入卸载队列** —— 飞一趟就永久留下几百上千个。
	 * 实测 chunks 从 1400 涨到 3320、tick 从 28ms 涨到 2300ms，就是它。
	 * 想重新启用，必须先实现"预取区块到期自动 unload"，否则纯属泄漏。
	 */
	public static int PREFETCH_BUDGET = 0;
	/** 低于这个速度不预取：站着不动也在预取纯属白烧 IO */
	public static double PREFETCH_MIN_SPEED = 1.5D;
	/** 主世界已加载区块超过这个数就不再预取，避免内存无限涨（视距 12 时正常是 600 左右） */
	public static int PREFETCH_MAX_LOADED = 1500;
	/** 同时挂起的预取上限，超过就停手（背压） */
	public static int PREFETCH_MAX_OUTSTANDING = 32;

	/* ---------------- 曲速模式 ---------------- */

	/** 超过这个速度（格/tick）进入曲速：不预取、不预生成，视距压到最小 */
	public static double WARP_SPEED = 24.0D;
	public static boolean WARP_ENABLED = true;
	/** 曲速时的服务端视距（1.7.10 的 PlayerManager 硬性下限是 3） */
	public static int WARP_VIEW_RADIUS = 3;

	/** 当前是否处于曲速 */
	private boolean warping;

	public boolean isWarping() { return this.warping; }
	/** 退出曲速后还要做几次"落地安全检查" */
	private int safeTicks;
	/** 没人骑多久后消失 */
	public static int IDLE_LIFETIME = 200;   // 空载 10 秒才散掉：区块卸载/挂载包丢失时别把云删了

	private int idleTicks;
	/** 上一 tick 的骑手，用来在潜行被原版规则甩下云时立刻拉回来 */
	private EntityPlayer lastRider;
	/** 被甩下云后的宽限期（tick），窗口内自动拉回 */
	private int dismountGrace;
	/** 客户端插值用的目标位置：高速时服务端发的是瞬移包，直接 setPosition 会一帧一跳 */
	private double lerpTargetX, lerpTargetY, lerpTargetZ;
	private int lerpSteps;
	/** 服务端是否已经发过一次位置，决定纠偏能不能用 */
	private boolean hasServerPos;
	/** 临时诊断：每 20 tick 打一行。定位完改回 false */
	public static boolean DIAG = true;
	/** 逐 tick 追踪（只在骑乘时的客户端跑，打完 60 tick 自动停）——用来抓"上下弹"的波形 */
	public static boolean TRACE = true;
	private int traceTicks = 60;
	/** 一上云就重新计满 200 tick，保证抓到的是骑乘中的波形，而不是召唤那一刻的 */
	private boolean traceArmed;
	/** 本 world tick 是否已经被 PlayerTickEvent 驱动过（避免云自己的 onUpdate 再走一遍） */
	private long drivenTick = Long.MIN_VALUE;
	/** 上一次记录的骑手 id，用来只在"骑乘关系变化"时打日志 */
	private int lastLoggedRider = -1;
	/** 强制加载云脚下区块用的 ticket。没有它，飞得快时区块会被卸载，实体被踢出世界 → 客户端掉骑乘 → 掉虚空 */
	private ForgeChunkManager.Ticket cloudTicket;
	/** 骑乘期间临时给玩家开的飞行能力（防止任何原因脱离骑乘后直接坠入虚空） */
	private boolean grantedFly;
	private boolean flyWasAllowed;
	/**
	 * 玩家 -> 骑乘前 allowFlying 的原值。
	 * 放在静态表里而不是实体字段：云可能被区块卸载/直接删除，实体字段跟着没了，能力就永远收不回来。
	 * 服务端每 tick 都会检查"还骑着吗"，没骑就立刻还原。
	 */
	private static final java.util.Map<EntityPlayer, Boolean> FLY_GRANTED =
			new java.util.WeakHashMap<EntityPlayer, Boolean>();
	private int ticketCX = Integer.MIN_VALUE;
	private int ticketCZ = Integer.MIN_VALUE;
	/** 玩家 -> 最近骑过的云。WeakHashMap 不会拖住玩家对象；用来在玩家掉进虚空时把他捞回来 */
	private static final java.util.Map<EntityPlayer, EntityJindouYun> LAST_CLOUD =
			new java.util.WeakHashMap<EntityPlayer, EntityJindouYun>();

	public static EntityJindouYun getLastCloud(EntityPlayer player) {
		return LAST_CLOUD.get(player);
	}
	/** 聊天框读数，调参时开，平时关 */
	public static boolean CHAT_DIAG = true;
	private static int snapCount;
	/** 最近一次收到客户端位置包的 tick，用来判断这一 tick 要不要服务端自己算 */
	private int lastClientMoveTick = -100000;   // 不用 MIN_VALUE：ticksExisted - MIN_VALUE 会溢出成正数
	/** 平滑后的飞行朝向（度）：鼠标每 tick 只采样一次，直接用会飞出折线 */
	private float moveHeading = Float.NaN;
	/** 每 tick 向鼠标朝向靠拢的比例。1 = 完全跟手（手感优先），0.6 ≈ 3 tick 收敛，轨迹更圆但转向发飘 */
	public static float HEADING_RATE = 1.0F;
	/** 平滑后的速度，避免一按键就满速、一松手就急停 */
	private double velX, velY, velZ;
	/** 当前水平速度大小，只对这一项做平滑 */
	private double velMag;
	/** 由 ModEventHandler 在实体 tick 之前写入：潜行键被用来刹车，而不是下马 */
	public boolean wantsBrake;
	/** Ctrl 加速，由 PacketJindouYunBoost 写入 */
	public boolean boost;
	/** 客户端只在状态变化时发包 */
	public boolean boostSent;
	/** 生成后由云自己第一个 tick 挂载，避免同 tick spawn+mount 丢 attach 包 */
	public EntityPlayer pendingRider;
	/** 诊断开关，默认关：每 tick 写日志在高速飞行时本身就是开销 */
	private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("hbm.jindouyun.debug", "false"));

	public EntityJindouYun(World world) {
		super(world);
		this.setSize(0.8F, 0.6F);
		this.noClip = false;
		this.preventEntitySpawning = false;
		// 骑在第一人称时镜头就在云的包围盒里，RenderGlobal 用包围盒做视锥剔除，
		// 会一帧在视锥内、一帧在视锥外，表现就是云整朵一闪一闪。关掉剔除即可。
		this.ignoreFrustumCheck = true;

		// 每次召唤打印一行，用来确认"改的到底是不是正在跑的这份代码"。
		// 单位换算：SPEED 是 格/tick，乘以 20 才是 格/秒。
		if(!world.isRemote) {
			MainRegistry.logger.info("[jindouyun] SPEED=" + SPEED + " blocks/tick (" + (SPEED * 20.0D)
					+ " blocks/s), BOOST_MULT=" + BOOST_MULT + ", BRAKE_MULT=" + BRAKE_MULT
					+ ", MAX_STEP=" + MAX_STEP + ", COLLIDE=" + COLLIDE);
		}
	}

	@Override
	protected void entityInit() { }

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) { }

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) { }

	/** 云本身不吃任何伤害（火、岩浆、爆炸都一样）。 */
	@Override
	public boolean isEntityInvulnerable() {
		return true;
	}

	/** 云是实的，否则玩家右键点不到它。 */
	@Override
	public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int increments) {
		// 原版这里直接 setPosition，高速飞行（每 tick > 4 格）时会走 S18 瞬移包，
		// 云就会一帧一个位置地跳。改成记录目标、在 onUpdate 里分几 tick 逼近。
		this.lerpTargetX = x;
		this.lerpTargetY = y;
		this.lerpTargetZ = z;
		this.hasServerPos = true;

		// 自己骑的这朵云由客户端预测全权接管位置和朝向。这里再去 setRotation 就是和服务端的
		// 旧数据打架：每 tick 先把朝向改成包里的值、onUpdate 再改回本地值，模型就会原地抽搐。
		EntityPlayer local = this.worldObj.isRemote ? MainRegistry.proxy.getLocalPlayer() : null;
		if(local != null && local.ridingEntity == this) return;

		// 高速时直接贴合：插值 10 tick 只会让云拖在玩家身后，看起来像"卡"
		double far = Math.abs(x - this.posX) + Math.abs(y - this.posY) + Math.abs(z - this.posZ);
		this.lerpSteps = far > 6.0D ? 1 : Math.max(1, Math.min(3, increments));
		this.setRotation(yaw, pitch);
	}

	/** 载人时不参与实体碰撞，否则高速掠过时会把别人顶进墙里；空载时保持可右键 */
	@Override
	public boolean canBeCollidedWith() {
		return this.riddenByEntity == null;
	}

	@Override
	public boolean canBePushed() {
		return false;
	}

	@Override
	public boolean interactFirst(EntityPlayer player) {

		if(!this.worldObj.isRemote && this.riddenByEntity == null) {
			player.mountEntity(this);
			return true;
		}

		return false;
	}

	@Override
	public double getMountedYOffset() {
		return 1F; // 站在云面上，而不是陷在云里
	}

	@Override
	public void onUpdate() {

		super.onUpdate();

		// 骑乘关系变化时打一行：掉虚空时靠这一行判断到底是哪一端断了
		int nowRider = this.riddenByEntity == null ? -1 : this.riddenByEntity.getEntityId();
		if(nowRider != this.lastLoggedRider) {
			MainRegistry.logger.info("[jy-detach] side=" + (this.worldObj.isRemote ? "C" : "S")
					+ " tick=" + this.ticksExisted + " rider=" + nowRider + " was=" + this.lastLoggedRider
					+ " dead=" + this.isDead + " added=" + this.addedToChunk
					+ " chunk=" + (this.worldObj.getChunkProvider().chunkExists(
							MathHelper.floor_double(this.posX) >> 4, MathHelper.floor_double(this.posZ) >> 4))
					+ " pos=" + (float) this.posX + "," + (float) this.posY + "," + (float) this.posZ);
			this.lastLoggedRider = nowRider;
		}

		if(this.worldObj.isRemote) {

			com.hbm.main.ClientProxy.syncJindouYunBoost(this);

			// 自己骑的这朵云：本地用本地输入直接积分并移动。
			// 服务端那份照常在算，但客户端不再等它的回包，输入延迟从 2~3 tick 降到 0。
			EntityPlayer local = MainRegistry.proxy.getLocalPlayer();

			if(CLIENT_PREDICTION && local != null && local.ridingEntity == this && this.riddenByEntity == local) {

				// 正常情况这一 tick 已经在 PlayerTickEvent(START) 里驱动过了（见 clientDrive）：
				// 那时还没记录 prevPos，所以骑手每帧正好位移一 tick，渲染插值是连续的。
				// 这里只兜底（比如事件没挂上）。
				if(this.drivenTick != this.worldObj.getTotalWorldTime()) {
					this.clientDrive(local);
				}

				this.rotationYaw += MathHelper.wrapAngleTo180_float(local.rotationYaw - this.rotationYaw);
				this.rotationPitch = local.rotationPitch * 0.4F;
				return;
			}

			// 别人的云：按服务端给的目标位置插值（见 setPositionAndRotation2）。
			// 注意：不能反过来把云吸到骑手身上——骑手的位置本来就是由云推出来的，
			// 那样会形成"玩家在原地自由落体、云跟着一起掉"的死循环。
			if(this.lerpSteps > 0) {
				this.posX += (this.lerpTargetX - this.posX) / this.lerpSteps;
				this.posY += (this.lerpTargetY - this.posY) / this.lerpSteps;
				this.posZ += (this.lerpTargetZ - this.posZ) / this.lerpSteps;

				if(--this.lerpSteps == 0) {
					this.posX = this.lerpTargetX;
					this.posY = this.lerpTargetY;
					this.posZ = this.lerpTargetZ;
				}
			}

			return;
		}

		/*
		 * 一个玩家只能骑一朵云。
		 *
		 * 连续右键两次会召唤出两朵：第一朵的 pendingRider 要到下一 tick 才挂上，
		 * 第二次右键时 player.ridingEntity 还是 null，于是又召唤一朵。两朵云每 tick 都执行
		 * riddenByEntity.setPosition(自己)，玩家位置被来回拉扯，客户端和服务端偏差累积到阈值就是"回弹"。
		 * 所以：玩家已经骑上别的云时，这一朵立刻放手并自毁。
		 */
		if(this.riddenByEntity != null && this.riddenByEntity.ridingEntity != this) {
			this.riddenByEntity = null;
			this.lastRider = null;
			this.dismountGrace = 0;
			this.motionX = this.motionY = this.motionZ = 0.0D;
		}

		if(this.pendingRider != null) {
			if(this.pendingRider.ridingEntity == null) {
				this.pendingRider.mountEntity(this);
			} else if(this.pendingRider.ridingEntity != this) {
				// 玩家已经被另一朵云抢走了，这朵自毁
				this.pendingRider = null;
				this.setDead();
				return;
			}
			this.pendingRider = null;
		}

		boolean clientDriven = false;

		if(this.riddenByEntity instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) this.riddenByEntity;
			this.lastRider = player;
			this.dismountGrace = 0;

			if(DEBUG && this.ticksExisted % 10 == 0) {
				MainRegistry.logger.info("[jindouyun] fwd=" + player.moveForward + " str=" + player.moveStrafing
						+ " jump=" + player.isJumping + " sneak=" + player.isSneaking()
						+ " yaw=" + player.rotationYaw + " pitch=" + player.rotationPitch
						+ " cloudY=" + this.posY + " vel=" + this.velX + "/" + this.velY + "/" + this.velZ);
			}

			// 客户端权威：只要骑手是玩家，服务端就一律不自行积分。
			//
			// 原来这里是 "age <= 1" 的容差，看着合理，实际上在单机里也会周期性失效：
			// 客户端和服务端的 tick 不同相，位置包有时会晚一个 tick 才被处理，那一 tick
			// age 就是 2 → 服务端用 moveForward=0 自己积分一次，把速度吃掉一截；
			// 下一 tick 客户端的包又把它拉回原位 —— 一 tick 掉、一 tick 拉，就是"一直上下弹"。
			// 客户端权威模式下服务端不该有第二份积分，等包就好。
			clientDriven = CLIENT_AUTHORITATIVE && this.riddenByEntity instanceof EntityPlayer;

			if(!clientDriven) {
				// Shift = 减速，Ctrl = 加速
				boolean brake = this.wantsBrake || player.isSneaking();
				this.wantsBrake = false;

				this.updateVelocity(player.moveForward, player.moveStrafing, player.isJumping,
					player.rotationYaw, player.rotationPitch, this.boost, brake);
			}

			// 不能直接 this.rotationYaw = player.rotationYaw：MC 的 yaw 在 ±180 处会回绕
			// （179.9 → -179.9），直接赋值会让 prevRotationYaw 和 rotationYaw 差出将近 360°，
			// 渲染插值就会"整圈转回去"。按最短角差累加，yaw 就能一直连续下去。
			if(!clientDriven) {
				this.rotationYaw += MathHelper.wrapAngleTo180_float(player.rotationYaw - this.rotationYaw);
				// 云随俯仰轻微点头（只是视觉，不影响飞行方向）
				this.rotationPitch = player.rotationPitch * 0.4F;
			}
			this.idleTicks = 0;

			if(DIAG && this.ticksExisted % 20 == 0) {
				int clouds = 0;
				for(Object o : this.worldObj.loadedEntityList) if(o instanceof EntityJindouYun) clouds++;
				MainRegistry.logger.info("[jy-srv] clouds=" + clouds + " pos=" + (float) this.posX + "," + (float) this.posY + "," + (float) this.posZ
					+ " clientDriven=" + clientDriven + " age=" + (this.ticksExisted - this.lastClientMoveTick)
					+ " mot=" + (float) this.motionX + "," + (float) this.motionY + "," + (float) this.motionZ);
			}

		} else {

			// 原版 EntityPlayer.updateRidden() 写死了「潜行 = 下马」，而且下马之后立刻
			// setSneaking(false)——所以不能靠 isSneaking() 判断，那个标记在真正被甩下来时
			// 已经是 false 了。改成宽限期：只要人是刚刚被甩下来的（2 秒内）、还活着、还在
			// 附近，就无条件拉回去。真正想下云只有右键收云，那时云已经 setDead，走不到这里。
			if(!this.isDead && this.lastRider != null && this.lastRider.ridingEntity == null
					&& this.lastRider.isEntityAlive() && this.lastRider.getDistanceSqToEntity(this) < 4096.0D
					&& this.dismountGrace < 40) {
				this.dismountGrace++;
				this.lastRider.mountEntity(this);
				this.lastRider.setSneaking(false);
				this.idleTicks = 0;
				return;
			}

			this.dismountGrace = 0;
			if(this.lastRider != null) {
				this.lastRider.noClip = false;      // 真的下云了，把穿墙还回去
				this.revokeFlight(this.lastRider);  // 以及临时飞行
			}
			this.lastRider = null;
			this.moveHeading = Float.NaN;
			this.boost = false;
			this.motionX *= 0.85D;
			this.motionY *= 0.85D;
			this.motionZ *= 0.85D;

			// 只有服务端能决定云什么时候消失。
			// 客户端在卡顿/丢包时可能有一拍收不到 attach 包，riddenByEntity 短暂为 null；
			// IDLE_LIFETIME = 0 会让它在客户端当场 setDead，表现为"云凭空消失但还能飞"，
			// 而且客户端的 ridingEntity 随后被清空，右键会误召唤第二朵云。
			if(!this.worldObj.isRemote && ++this.idleTicks > IDLE_LIFETIME) {
				this.setDead();
				return;
			}
		}

		if(!clientDriven) this.tickMovement();
		this.syncRiderPosition();
	}

	/**
	 * 客户端权威的入口：服务端收到骑手发来的位置后直接采用，并标记这一 tick 已经有人算过了。
	 * 范围校验只是为了挡住明显不合法的包（瞬移几千格那种），正常飞行不会触发。
	 */
	/**
	 * 每个服务端 tick 由 ModEventHandler 调用：保证云脚下和前方一小段的区块一定在内存里。
	 * 这是"掉虚空"的最终保险 —— 只要云所在区块存在，实体就会被 tick，骑乘关系就不会断。
	 */
	public void ensureSelfChunk() {
		this.pinChunk();
		this.ensureChunk(MathHelper.floor_double(this.posX) >> 4, MathHelper.floor_double(this.posZ) >> 4);
		this.ensureChunk(MathHelper.floor_double(this.posX + this.motionX * 2.0D) >> 4,
				MathHelper.floor_double(this.posZ + this.motionZ * 2.0D) >> 4);
	}

	/**
	 * 用 ForgeChunkManager 把云脚下的区块钉住。
	 *
	 * 为什么必须钉：ChunkProviderServer 只保证"玩家周围的区块"不被卸载，而 1.7.10 的实体在区块被卸载时
	 * 会被移出世界（addedToChunk=false），entity tracker 随即给客户端发销毁包 —— 客户端把云删掉、
	 * ridingEntity 变 null，玩家就自由落体了。钉住这一列，云永远不会"凭空消失"。
	 */
	private void pinChunk() {

		if(this.worldObj.isRemote) return;

		int cx = MathHelper.floor_double(this.posX) >> 4;
		int cz = MathHelper.floor_double(this.posZ) >> 4;

		if(this.cloudTicket == null) {
			this.cloudTicket = ForgeChunkManager.requestTicket(MainRegistry.instance, this.worldObj, ForgeChunkManager.Type.ENTITY);
			if(this.cloudTicket == null) return;   // 配置不允许申请 ticket：退回 ensureChunk 的同步加载
			// Type.ENTITY 的 ticket 必须绑定实体，否则 forceChunk 会抛
			// "Attempted to use an entity ticket to force a chunk, without an entity"
			this.cloudTicket.bindEntity(this);
		}

		if(cx == this.ticketCX && cz == this.ticketCZ) return;

		if(this.ticketCX != Integer.MIN_VALUE) {
			ForgeChunkManager.unforceChunk(this.cloudTicket, new ChunkCoordIntPair(this.ticketCX, this.ticketCZ));
		}
		ForgeChunkManager.forceChunk(this.cloudTicket, new ChunkCoordIntPair(cx, cz));
		this.ticketCX = cx;
		this.ticketCZ = cz;
	}

	/**
	 * 骑乘期间给玩家临时开飞行。
	 *
	 * 这是"掉虚空"的最后一道保险：不管骑乘关系因为什么断掉（区块卸载把实体踢出世界、
	 * 挂载包丢失、云被销毁），玩家只要会飞就不会自由落体。断掉之后他会悬停，右键再召唤一朵即可。
	 * 创造模式本来就能飞，不做任何改动。
	 */
	private void grantFlight(EntityPlayer player) {

		if(player.capabilities.isCreativeMode) return;

		if(!FLY_GRANTED.containsKey(player)) {
			FLY_GRANTED.put(player, player.capabilities.allowFlying);
		}
		if(!this.grantedFly) {
			this.grantedFly = true;
			this.flyWasAllowed = FLY_GRANTED.get(player);
		}

		if(player.capabilities.allowFlying && player.capabilities.isFlying) return;

		player.capabilities.allowFlying = true;
		player.capabilities.isFlying = true;

		if(!this.worldObj.isRemote && player instanceof EntityPlayerMP) {
			((EntityPlayerMP) player).sendPlayerAbilities();
		}
	}

	/** 下云时把飞行能力还原（创造模式不动） */
	private void revokeFlight(EntityPlayer player) {
		revokeStaleFlight(player);
		this.grantedFly = false;
	}

	/**
	 * 没在骑任何一朵云时，把临时开出来的飞行能力还原。
	 * 服务端每 tick 对所有玩家调一次；不在骑乘状态就立刻收回，云被卸载/删除也不会漏。
	 */
	public static void revokeStaleFlight(EntityPlayer player) {

		Boolean was = FLY_GRANTED.get(player);
		if(was == null) return;
		if(player.ridingEntity instanceof EntityJindouYun) return;

		FLY_GRANTED.remove(player);

		// 创造模式本来就能飞，只把 allowFlying 还原成原值，别打断他的飞行
		if(player.capabilities.isCreativeMode) {
			player.capabilities.allowFlying = true;
			return;
		}

		player.capabilities.allowFlying = was.booleanValue();
		if(!was.booleanValue()) player.capabilities.isFlying = false;

		if(player instanceof EntityPlayerMP) {
			((EntityPlayerMP) player).sendPlayerAbilities();
		}
	}

	/** 确保某个区块在服务端已加载；没有就同步生成。飞行时宁可卡一帧，也不能让云落进未加载区块。 */
	private void ensureChunk(int cx, int cz) {

		if(this.worldObj.isRemote) return;
		if(!(this.worldObj.getChunkProvider() instanceof ChunkProviderServer)) return;

		ChunkProviderServer cps = (ChunkProviderServer) this.worldObj.getChunkProvider();
		if(cps.chunkExists(cx, cz)) return;

		cps.loadChunk(cx, cz);
	}

	public void acceptClientMove(double x, double y, double z, float yaw, float pitch) {

		double dx = x - this.posX;
		double dy = y - this.posY;
		double dz = z - this.posZ;

		double speed = (SPEED > 0 ? SPEED : 16.0D) * Math.max(1.0D, BOOST_MULT);
		double max = Math.max(8.0D, speed * 4.0D);   // 允许最多 4 tick 的位移，扛得住突发丢包

		if(Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > max) return;

		// 目标区块在服务端还没加载：**不能拒绝这一包**。
		// 拒了就等于让服务端停在原地、客户端继续往前飞，两边永久失联 —— 客户端飞进没有区块的地方后
		// 实体被塞进 unloadedEntityList，骑乘关系断掉，玩家就掉进虚空了。
		// 正确做法是把区块要过来（同步生成，宁可卡一下），保证服务端始终跟得上客户端。
		int cx = MathHelper.floor_double(x) >> 4;
		int cz = MathHelper.floor_double(z) >> 4;
		this.ensureChunk(cx, cz);

		this.motionX = dx;
		this.motionY = dy;
		this.motionZ = dz;
		this.velX = dx;
		this.velY = dy;
		this.velZ = dz;

		this.setPositionAndRotation(x, y, z, yaw, pitch);
		this.lastClientMoveTick = this.ticksExisted;
	}

	/** 把本地算出来的位置发给服务端（见 PacketJindouYunMove） */
	private void sendMoveToServer() {

		com.hbm.packet.PacketDispatcher.wrapper.sendToServer(
				new com.hbm.packet.toserver.PacketJindouYunMove(this.getEntityId(),
						this.posX, this.posY, this.posZ, this.rotationYaw, this.rotationPitch));
	}

	/**
	 * 骑手落点。**故意不用 riddenByEntity.yOffset**：玩家的 yOffset 是 1.62（EntityPlayer 构造函数设的），
	 * 而 EntityLivingBase.setPositionAndRotation2 收到位置包时又把它清成 0。
	 * 原版 updateRiderPosition 的公式是 posY + getMountedYOffset() + getYOffset()，
	 * 于是同一个骑手会在 "云上 1 格" 和 "云上 2.62 格" 之间来回跳 —— 这就是上下弹的来源。
	 * 这里统一用固定值，与服务端/客户端、以及有没有收到过位置包都无关。
	 */
	@Override
	public void updateRiderPosition() {
		if(this.riddenByEntity != null) {
			// 骑乘期间不参与方块碰撞：否则玩家和方块重叠时会被原版"顶出方块"逻辑每 tick 往上推，
			// 再被这里按回云上，一推一按就是抖动。
			this.riddenByEntity.noClip = true;
			if(this.riddenByEntity instanceof EntityPlayer) {
				EntityPlayer p = (EntityPlayer) this.riddenByEntity;
				LAST_CLOUD.put(p, this);
				this.grantFlight(p);
			}
			this.riddenByEntity.setPosition(this.posX, this.posY + this.getMountedYOffset(), this.posZ);
		}
	}

	/**
	 * 客户端驱动：把云推进一步。
	 *
	 * 由 PlayerTickEvent(START) 调用 —— 也就是在玩家这一 tick 记录 prevPos 之前、
	 * 以及 updateRiderPosition() 之前。这样每 tick 骑手只位移一 tick 的量，
	 * 60fps 渲染时 prevPos -> pos 是连续插值的，不会出现"这帧不动、下帧跳两格"的 tick 步进感。
	 */
	public void clientDrive(EntityPlayer player) {

		if(!this.worldObj.isRemote || !CLIENT_PREDICTION) return;
		if(this.riddenByEntity != player || player.ridingEntity != this) return;

		// 每 tick 只驱动一次。clientDrive 有两个调用点：
		//   ModEventHandlerClient 的 PlayerTickEvent(START) —— 为了让 prevPos 和 pos 错开一 tick
		//   本实体的 onUpdate —— 兜底
		// 两个都跑就是每 tick 推两步：云的实际速度翻倍（日志里 cy 一行掉 motionY、一行再掉一次），
		// 骑手的 pos 也在同一 tick 里被设两次，渲染插值只覆盖其中一段 → 画面上下抖。
		if(this.drivenTick == this.worldObj.getTotalWorldTime()) return;
		this.drivenTick = this.worldObj.getTotalWorldTime();

		// 直接读原始按键：骑乘时 player.moveForward 会被原版逻辑清零，不能用来做本地预测
		this.updateVelocity(MainRegistry.proxy.inputForward(), MainRegistry.proxy.inputStrafe(),
			MainRegistry.proxy.inputJump(), player.rotationYaw, player.rotationPitch,
			MainRegistry.proxy.isBoostKeyDown(), MainRegistry.proxy.inputSneak());
		this.updateWarp();
		this.tickMovement();

		this.sendMoveToServer();
		this.correctToServer();

		if(DIAG && this.ticksExisted % 20 == 0) {
			double ex = this.lerpTargetX - this.posX;
			double ey = this.lerpTargetY - this.posY;
			double ez = this.lerpTargetZ - this.posZ;
			MainRegistry.logger.info("[jy-cli] pos=" + (float) this.posX + "," + (float) this.posY + "," + (float) this.posZ
				+ " err=" + (float) (Math.abs(ex) + Math.abs(ey) + Math.abs(ez))
				+ " mot=" + (float) this.motionX + "," + (float) this.motionY + "," + (float) this.motionZ
				+ " fwd=" + (float) MainRegistry.proxy.inputForward() + "," + (float) MainRegistry.proxy.inputStrafe()
				+ " jump=" + MainRegistry.proxy.inputJump() + " vm=" + (float) this.velMag);
		}

		if(!this.traceArmed) {
			this.traceArmed = true;
			this.traceTicks = 200;
		}

		if(TRACE && this.traceTicks > 0) {
			this.traceTicks--;
			MainRegistry.logger.info("[jy-t] " + this.ticksExisted
				+ " cy=" + (float) this.posY + " my=" + (float) this.motionY
				+ " ry=" + (float) player.posY + " nc=" + player.noClip
				+ " fwd=" + MainRegistry.proxy.inputForward()
				+ " str=" + MainRegistry.proxy.inputStrafe()
				+ " jump=" + MainRegistry.proxy.inputJump()
				+ " snk=" + player.isSneaking()
				+ " vm=" + (float) this.velMag
				+ " yaw=" + (float) player.rotationYaw
				+ " pit=" + (float) player.rotationPitch);
		}
	}

	/** 骑手位置默认是载具 tick 时再对齐的，这里移动完立刻同步一次，免得云飞出去了人还停在上一 tick。 */
	private void syncRiderPosition() {

		if(this.riddenByEntity == null) return;

		// 骑手的位置永远由云决定，所以骑乘期间不该参与任何方块碰撞。
		// 否则玩家一旦和方块重叠（贴地飞、穿墙、刚传送到方块里），原版 EntityPlayerSP.onLivingUpdate
		// 里的 func_145771_j 会每 tick 把他往上顶，紧接着这里又把他按回云上 —— 一顶一按就是"弹"。
		// 这个和 COLLIDE 无关：云自己穿不穿墙是一回事，骑手会不会被方块推是另一回事。
		this.riddenByEntity.noClip = true;

		this.riddenByEntity.setPosition(this.posX, this.posY + this.getMountedYOffset(), this.posZ);
		this.riddenByEntity.motionX = this.motionX;
		this.riddenByEntity.motionY = this.motionY;
		this.riddenByEntity.motionZ = this.motionZ;
	}

	/** 下云 / 收云时把骑手的 noClip 还回去，否则他会一直穿墙 */
	private void restoreRiderNoClip() {

		if(this.riddenByEntity != null) this.riddenByEntity.noClip = false;
		if(this.lastRider != null) this.lastRider.noClip = false;
	}

	@Override
	public void setDead() {

		// 谁杀了这朵云？把调用栈前几帧打出来，省得再猜
		{
			StackTraceElement[] st = new Throwable().getStackTrace();
			StringBuilder sb = new StringBuilder();
			for(int i = 1; i < Math.min(st.length, 6); i++) sb.append(" <- ").append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
			MainRegistry.logger.info("[jy-dead] side=" + (this.worldObj.isRemote ? "C" : "S") + " tick=" + this.ticksExisted
					+ " rider=" + (this.riddenByEntity == null ? -1 : this.riddenByEntity.getEntityId())
					+ " pos=" + (float) this.posX + "," + (float) this.posY + "," + (float) this.posZ + sb);
		}

		// 云要消失了：先把骑手放到云的位置上，再解除骑乘。
		// 否则他会被留在他自己那一 tick 的位置上自由落体 —— 在未生成区域就是直接掉进虚空。
		if(this.riddenByEntity != null) {
			Entity rider = this.riddenByEntity;
			if(rider instanceof EntityPlayer) this.revokeFlight((EntityPlayer) rider);
			rider.setPosition(this.posX, this.posY + this.getMountedYOffset() + 0.5D, this.posZ);
			rider.motionX = rider.motionY = rider.motionZ = 0.0D;
			rider.fallDistance = 0.0F;
			rider.mountEntity(null);
		}

		this.restoreRiderNoClip();

		if(this.cloudTicket != null) {
			ForgeChunkManager.releaseTicket(this.cloudTicket);
			this.cloudTicket = null;
			this.ticketCX = this.ticketCZ = Integer.MIN_VALUE;
		}

		super.setDead();
	}

	/**
	 * 用骑手的输入和朝向算这一 tick 的速度。
	 * 服务端和客户端预测共用同一份积分，两边才不会各算各的、跑偏到互相拉扯。
	 */
	private void updateVelocity(float forward, float strafe, boolean jump, float yawDeg, float pitchDeg, boolean boost, boolean brake) {

		double baseSpeed = SPEED > 0 ? SPEED : 16.0D;
		double speed = baseSpeed * (boost ? BOOST_MULT : 1.0D) * (brake ? BRAKE_MULT : 1.0D);

		// 朝向按最短角差追过去，避免 yaw 在 ±180 回绕时整圈反转
		if(Float.isNaN(this.moveHeading)) this.moveHeading = yawDeg;
		float headingDelta = MathHelper.wrapAngleTo180_float(yawDeg - this.moveHeading);
		this.moveHeading += headingDelta * HEADING_RATE;
		if(Math.abs(headingDelta) < 0.05F) this.moveHeading = yawDeg;

		double yaw = Math.toRadians(this.moveHeading);
		double pitch = Math.toRadians(pitchDeg);
		double sinYaw = Math.sin(yaw);
		double cosYaw = Math.cos(yaw);
		double cosPitch = Math.cos(pitch);

		// W/S 沿「视线本身」飞：低头就往斜下方冲，抬头就往斜上方冲
		double lookX = -sinYaw * cosPitch;
		double lookY = -Math.sin(pitch);
		double lookZ =  cosYaw * cosPitch;

		// A/D 水平横移，不跟着俯仰走
		double rightX = cosYaw;
		double rightZ = sinYaw;

		double moveX = lookX * forward + rightX * strafe;
		double moveY = lookY * forward;
		double moveZ = lookZ * forward + rightZ * strafe;

		if(jump) moveY += 1.0D;   // 空格单独往上顶

		double moveLength = Math.sqrt(moveX * moveX + moveY * moveY + moveZ * moveZ);

		// 只对「速度大小」做平滑，方向必须瞬间跟随鼠标
		double targetSpeed = speed * Math.min(1.0D, moveLength);

		this.velMag += (targetSpeed - this.velMag) * ACCELERATION;

		if(moveLength > 1.0E-4D) {
			this.velX = moveX / moveLength * this.velMag;
			this.velY = moveY / moveLength * this.velMag;
			this.velZ = moveZ / moveLength * this.velMag;
		} else {
			// 松手：沿最后的方向滑行。速度大小与分量同步衰减，下一次按键才不会跳一下
			this.velMag *= RELEASE_DAMP;
			this.velX *= RELEASE_DAMP;
			this.velY *= RELEASE_DAMP;
			this.velZ *= RELEASE_DAMP;
		}

		this.motionX = this.velX;
		this.motionY = this.velY;
		this.motionZ = this.velZ;
	}

	/**
	 * 客户端预测的纠偏：预测位置本来就比服务端快 1 tick，偏差小是正常的，别去拉它，
	 * 否则等于用回包把预测拽回去，白预测。只有偏差大到明显脱节（撞墙、区块没加载）才硬拉回。
	 */
	private void correctToServer() {

		// 客户端权威时，服务端的位置本来就来自客户端，它只会落后 1 tick。
		// 这时再"向服务端对齐"就等于把自己往回拽 —— 那就是回弹。所以直接不做纠偏。
		if(CLIENT_AUTHORITATIVE) return;

		if(!this.hasServerPos) return;

		double dx = this.lerpTargetX - this.posX;
		double dy = this.lerpTargetY - this.posY;
		double dz = this.lerpTargetZ - this.posZ;
		double err = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);

		// 非权威模式才纠偏，而且不瞬移：每 tick 最多挪 1 格，拉不住也看不出来
		if(err > 8.0D) {
			snapCount++;
			double step = Math.min(1.0D, err * 0.15D) / err;
			this.setPosition(this.posX + dx * step, this.posY + dy * step, this.posZ + dz * step);
			this.lerpSteps = 0;
			if(snapCount % 20 == 1) {
				MainRegistry.logger.info("[jy-fix] err=" + (float) err + " pos=" + (float) this.posX + "," + (float) this.posY + "," + (float) this.posZ
					+ " srv=" + (float) this.lerpTargetX + "," + (float) this.lerpTargetY + "," + (float) this.lerpTargetZ);
			}
		}
	}

	/**
	 * 每 tick 只做一次位移，不再按 0.5 格拆步。
	 *
	 * 拆步的开销是 O(位移/0.5)：16 格/tick 就是 32 次 moveEntity，而每次 moveEntity 内部还要
	 * 扫一遍扫掠体积里的方块、再扫一遍附近实体。更要命的是 (int) Math.ceil(距离/0.5) 在极值处
	 * 会饱和成 Integer.MAX_VALUE，单 tick 里跑 21 亿次循环 —— 服务端线程就是这么被锁死的。
	 */
	/**
	 * 曲速模式：速度超过 WARP_SPEED 时，停掉预取和预生成，并把服务端视距压到最小。
	 *
	 * 为什么不能让方块"完全"不加载：玩家脚下必须有区块，否则服务端不 tick 这个实体、
	 * 客户端也没有碰撞。1.7.10 能做到的是把 PlayerManager 的跟踪半径降到下限 3（7x7），
	 * 跨一列只需要 7 个新区块，而视距 10 时要 21 个 —— 正好 3 倍余量。
	 *
	 * 退出曲速后：恢复视距、恢复预取/预生成，并在接下来几 tick 做落地安全检查，
	 * 防止高速停在方块里被窒息。
	 */
	private void updateWarp() {

		if(!WARP_ENABLED) return;

		double speed = Math.sqrt(this.motionX * this.motionX
				+ this.motionY * this.motionY
				+ this.motionZ * this.motionZ);

		boolean want = this.riddenByEntity != null && speed > WARP_SPEED;

		if(want != this.warping) {
			this.warping = want;
			this.applyWarp(want);

			if(!want) this.safeTicks = 20;
		}

		if(this.safeTicks > 0) {
			this.safeTicks--;
			this.unstuckRider();
		}
	}

	private void applyWarp(boolean on) {

		// 曲速期间挡掉所有主动加载
		com.hbm.world.ChunkPathPreloader.ENABLED = !on;

		if(!(this.worldObj instanceof net.minecraft.world.WorldServer)) return;

		net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) this.worldObj;
		net.minecraft.server.management.PlayerManager pm = ws.getPlayerManager();
		if(pm == null) return;

		int radius;
		if(on) {
			radius = WARP_VIEW_RADIUS;
		} else {
			radius = net.minecraft.server.MinecraftServer.getServer() == null ? 10
					: net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().getViewDistance();
		}

		pm.func_152622_a(radius);
	}

	/** 骑手卡在方块里就往上顶到空位（最多 40 格），只在退出曲速后的 1 秒内调用 */
	private void unstuckRider() {

		if(!(this.riddenByEntity instanceof EntityPlayer)) return;

		EntityPlayer player = (EntityPlayer) this.riddenByEntity;
		if(!player.isEntityInsideOpaqueBlock()) return;

		int x = MathHelper.floor_double(player.posX);
		int z = MathHelper.floor_double(player.posZ);
		int y = MathHelper.floor_double(player.posY);

		for(int i = 0; i < 40; i++) {
			if(!this.worldObj.getBlock(x, y + i, z).isNormalCube()) {
				player.setPosition(player.posX, y + i + 0.1D, player.posZ);
				this.setPosition(player.posX, y + i + 0.1D - this.getMountedYOffset(), player.posZ);
				return;
			}
		}
	}

	/** 预取完成回调：只做计数递减，真正插世界由 ChunkIOExecutor.tick() 在主线程完成 */
	private static final Runnable PREFETCH_DONE = new Runnable() {
		@Override
		public void run() { PREFETCH_OUTSTANDING.decrementAndGet(); }
	};

	private static final java.util.concurrent.atomic.AtomicInteger PREFETCH_OUTSTANDING =
			new java.util.concurrent.atomic.AtomicInteger();

	/** 当前挂起（已排队但还没完成）的预取数量 */
	public static int getPrefetchOutstanding() {
		return PREFETCH_OUTSTANDING.get();
	}

	/**
	 * 前方预取。只对磁盘上已存在、但还没进内存的区块排队，交给 Forge 的 ChunkIOExecutor 异步读，
	 * 不会触发新地形生成（生成永远是主线程同步的，这是 1.7.10 的硬限制）。
	 *
	 * 三条自我约束，缺一条就会把"预取"变成"主线程尖峰"：
	 *   1. 每 tick 的检查/排队数量有预算（PREFETCH_BUDGET）
	 *   2. 挂起数量有上限（PREFETCH_MAX_OUTSTANDING）—— ChunkIOExecutor.finishActive() 是 while
	 *      循环一次性清空完成队列，IO 跑得越快，主线程那一 tick 越容易被压爆
	 *   3. 只预取飞行方向前方，横向按视距铺开，转向时不会预取一堆用不上的
	 */
	private void prefetchAhead() {

		if(this.warping) return;
		if(PREFETCH_BUDGET <= 0 || PREFETCH_DEPTH <= 0) return;
		if(PREFETCH_OUTSTANDING.get() >= PREFETCH_MAX_OUTSTANDING) return;

		// 只对"真的在飞"预取：水平速度不够就别动
		double hspeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
		if(hspeed < PREFETCH_MIN_SPEED) return;

		if(!(this.worldObj.getChunkProvider() instanceof ChunkProviderServer)) return;

		// 已加载区块太多就别再往里塞：预取的区块不会被立刻卸载，堆着只会涨内存和 GC
		if(this.worldObj.getChunkProvider().getLoadedChunkCount() > PREFETCH_MAX_LOADED) return;

		ChunkProviderServer cps = (ChunkProviderServer) this.worldObj.getChunkProvider();
		if(!(cps.currentChunkLoader instanceof AnvilChunkLoader)) return;

		AnvilChunkLoader loader = (AnvilChunkLoader) cps.currentChunkLoader;

		int cx = MathHelper.floor_double(this.posX) >> 4;
		int cz = MathHelper.floor_double(this.posZ) >> 4;
		int dx = (int) Math.signum(this.motionX);
		int dz = (int) Math.signum(this.motionZ);
		if(dx == 0 && dz == 0) return;

		// 侧向单位向量（把 (dx,dz) 转 90 度）
		int sx = dz, sz = dx;

		int checks = 0;

		for(int d = 1; d <= PREFETCH_DEPTH && checks < PREFETCH_BUDGET; d++) {
			for(int w = 0; w <= PREFETCH_HALF_WIDTH && checks < PREFETCH_BUDGET; w++) {

				if(w == 0) {
					checks++;
					queue(cps, loader, cx + dx * d, cz + dz * d);

				} else {
					checks++;
					queue(cps, loader, cx + dx * d - sx * w, cz + dz * d - sz * w);
					if(checks >= PREFETCH_BUDGET) break;
					checks++;
					queue(cps, loader, cx + dx * d + sx * w, cz + dz * d + sz * w);
				}
			}
		}
	}

	private void queue(ChunkProviderServer cps, AnvilChunkLoader loader, int tx, int tz) {

		if(PREFETCH_OUTSTANDING.get() >= PREFETCH_MAX_OUTSTANDING) return;
		if(cps.chunkExists(tx, tz)) return;                       // 已经在内存
		if(!loader.chunkExists(this.worldObj, tx, tz)) return;     // 没生成过，别在这同步生成

		PREFETCH_OUTSTANDING.incrementAndGet();
		long t0 = System.nanoTime();
		cps.loadChunk(tx, tz, PREFETCH_DONE);                      // 走 ChunkIOExecutor 线程池
		com.hbm.world.ChunkGenProfiler.record(System.nanoTime() - t0, false);
	}

	/**
	 * 云的包围盒是否和方块相交。
	 * 不能用 isNormalCube 判断：台阶、栅栏、树叶这些不是 normalCube，但照样挡射线、照样把云卡死。
	 */
	private boolean isInsideBlock() {
		return !this.worldObj.getCollidingBoundingBoxes(this, this.boundingBox).isEmpty();
	}

	/** 把云顶到最近的空位（最多 24 格），返回是否移动过 */
	private boolean liftOutOfBlocks() {

		for(int i = 1; i <= 24; i++) {
			if(this.worldObj.getCollidingBoundingBoxes(this, this.boundingBox.getOffsetBoundingBox(0.0D, i, 0.0D)).isEmpty()) {
				this.setPosition(this.posX, this.posY + i + 0.001D, this.posZ);
				this.motionX = this.motionY = this.motionZ = 0.0D;
				return true;
			}
		}
		return false;
	}

	private void tickMovement() {

		double mx = this.motionX, my = this.motionY, mz = this.motionZ;
		double distance = Math.sqrt(mx * mx + my * my + mz * mz);

		// 只挡 NaN / Infinity：那不是速度上限，是防止坐标被污染成 NaN 后整个区块的实体都跟着坏掉
		if(!Double.isFinite(distance)) {
			this.motionX = this.motionY = this.motionZ = 0.0D;
			this.velX = this.velY = this.velZ = 0.0D;
			this.velMag = 0.0D;
			return;
		}
		if(distance < 1.0E-6D) return;

		this.prefetchAhead();

		// 位移上限默认 0 = 不限制；要保护性上限就改上面的 MAX_STEP
		double maxStep = MAX_STEP;
		if(maxStep > 0 && distance > maxStep) {
			double k = maxStep / distance;
			mx *= k; my *= k; mz *= k;
			distance = maxStep;
		}

		// 目的地所在区块没加载就先别进去。
		// 1.7.10 里实体一旦落在未加载的区块，World.updateEntity 会把 addedToChunk 置 false，
		// 这一 tick 不 tick、客户端也不渲染 —— 表现就是"云凭空消失"，等区块到了才回来。
		// 停在区块边界等一两 tick 比整个实体消失好得多；因为骑手就在云上，玩家自身的加载半径
		// 会立刻把这个区块要过来，所以这个停顿通常是 0~2 tick。
		int destCX = MathHelper.floor_double(this.posX + mx) >> 4;
		int destCZ = MathHelper.floor_double(this.posZ + mz) >> 4;
		if(!this.worldObj.getChunkProvider().chunkExists(destCX, destCZ)) {
			this.motionX = this.motionY = this.motionZ = 0.0D;
			this.velMag *= 0.5D;
			return;
		}

		boolean passThrough = this.riddenByEntity != null && !COLLIDE;
		this.noClip = passThrough;

		if(passThrough || this.riddenByEntity == null) {
			// noClip 为真时 moveEntity 直接 offset 包围盒改坐标，零方块/实体查询，O(1)
			this.moveEntity(mx, my, mz);
			return;
		}

		// 云的中心已经在方块里（贴地停太久、下沉一点点、或者被推到地形内部）：
		// 这时射线从方块内部出发，第一格就命中自己所在的方块，safe≈0 → 每 tick 都被判成"撞墙"，
		// 速度清零、原地不动，表现就是"卡在地面上动不了"。先把云顶出方块再继续。
		if(this.isInsideBlock()) {
			this.liftOutOfBlocks();
		}

		// 撞墙模式：一次体素射线，成本只跟位移格数有关，且不会去扫实体
		Vec3 from = Vec3.createVectorHelper(this.posX, this.posY + this.height * 0.5D, this.posZ);
		MovingObjectPosition hit = this.worldObj.rayTraceBlocks(from, from.addVector(mx, my, mz));

		if(hit == null) {
			this.setPosition(this.posX + mx, this.posY + my, this.posZ + mz);
			return;
		}

		// 撞墙：先走到命中面前，再把速度投影到墙面上滑过去
		double safe = Math.max(0.0D, hit.hitVec.distanceTo(from) - (this.width * 0.5D + 0.1D));
		if(safe > 1.0E-4D) {
			double k = Math.min(1.0D, safe / distance);
			this.setPosition(this.posX + mx * k, this.posY + my * k, this.posZ + mz * k);
		}

		// 整段清零是"贴着地面一磕一磕"的元凶：只清掉撞上的那一面的法线分量，剩下的沿墙滑
		double slideX = mx, slideY = my, slideZ = mz;
		if(hit.sideHit == 0 || hit.sideHit == 1)      slideY = 0.0D;
		else if(hit.sideHit == 2 || hit.sideHit == 3) slideZ = 0.0D;
		else if(hit.sideHit == 4 || hit.sideHit == 5) slideX = 0.0D;

		double slideLen = Math.sqrt(slideX * slideX + slideY * slideY + slideZ * slideZ);
		if(slideLen > 1.0E-4D) {
			Vec3 again = Vec3.createVectorHelper(this.posX, this.posY + this.height * 0.5D, this.posZ);
			MovingObjectPosition hit2 = this.worldObj.rayTraceBlocks(again, again.addVector(slideX, slideY, slideZ));
			double roll = hit2 == null ? 1.0D
					: Math.max(0.0D, hit2.hitVec.distanceTo(again) - (this.width * 0.5D + 0.1D)) / slideLen;
			roll = Math.max(0.0D, Math.min(1.0D, roll));
			if(roll > 0.0D) {
				this.setPosition(this.posX + slideX * roll, this.posY + slideY * roll, this.posZ + slideZ * roll);
			}
		}

		this.motionX = this.motionY = this.motionZ = 0.0D;
		this.velMag *= 0.9D;
	}
}
