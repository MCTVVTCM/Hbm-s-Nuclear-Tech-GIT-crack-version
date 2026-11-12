package com.hbm.explosion.vanillant.standard;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.ConfettiUtil;
import com.hbm.util.EntityDamageUtil;
import com.hbm.util.DamageResistanceHandler.DamageClass;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;

public class EntityProcessorCrossSmooth extends EntityProcessorCross {

	protected float fixedDamage;
	protected float pierceDT = 0;
	protected float pierceDR = 0;
	protected DamageClass clazz = DamageClass.EXPLOSIVE;
	/**
	 * 是否把这次伤害算成"玩家击杀"。
	 *
	 * 原版只会在伤害来源的 entity 是玩家时把 {@code recentlyHit} 置 100，而卫星/爆炸的
	 * 伤害源没有 entity，于是 onDeath 里走的是 dropFewItems(false, 0)：基础掉落还在，
	 * 但"玩家击杀才掉"的战利品和经验球全都没了。打开这个开关就按玩家击杀结算。
	 */
	protected boolean killCredit = false;
	/** 真正打出这一发的人。有它，onDeath 才会按玩家的抢夺等级结算掉落。 */
	protected EntityLivingBase attacker = null;
	/** 爆炸自带的抢夺等级，不依赖击杀者手里的武器。0 = 关闭。 */
	protected int looting = 0;
	/** 击杀不给经验球。 */
	protected boolean noExperience = false;
	
	public EntityProcessorCrossSmooth(double nodeDist, float fixedDamage) {
		super(nodeDist);
		this.fixedDamage = fixedDamage;
		this.setAllowSelfDamage();
	}
	
	public EntityProcessorCrossSmooth setupPiercing(float pierceDT, float pierceDR) {
		this.pierceDT = pierceDT;
		this.pierceDR = pierceDR;
		return this;
	}
	
	public EntityProcessorCrossSmooth setDamageClass(DamageClass clazz) {
		this.clazz = clazz;
		return this;
	}

	/** 让这次爆炸的击杀按玩家击杀结算（掉落 + 经验）。 */
	public EntityProcessorCrossSmooth withKillCredit() {
		this.killCredit = true;
		return this;
	}

	/** 击杀不掉经验球（掉落不受影响）。 */
	public EntityProcessorCrossSmooth withNoExperience() {
		this.noExperience = true;
		return this;
	}

	/**
	 * 指定伤害归属者。伤害源的 getEntity() 一旦返回玩家，
	 * EntityLivingBase.onDeath 就会用 EnchantmentHelper.getLootingModifier(attacker)
	 * 去算掉落加成，也就是"击杀者手上的抢夺等级"。
	 */
	public EntityProcessorCrossSmooth withAttacker(EntityLivingBase attacker) {
		this.attacker = attacker;
		return this;
	}

	/**
	 * 爆炸自带的抢夺等级。原版只认"击杀者手上那件武器"的抢夺附魔，
	 * 这里给伤害本身挂一个等级，由 LivingDropsEvent 去补差额。
	 */
	public EntityProcessorCrossSmooth withLooting(int level) {
		this.looting = Math.max(0, level);
		return this;
	}
	
	@Override
	public void attackEntity(Entity entity, ExplosionVNT source, float amount) {
		if(!entity.isEntityAlive()) return;

		// 必须在扣血之前设置：onDeath 里就是靠这个字段决定掉落和经验
		if(this.killCredit && entity instanceof EntityLivingBase) {
			((EntityLivingBase) entity).recentlyHit = 100;
		}

		// 标记这次死亡该补多少抢夺、以及掉落物要不要烤熟；LivingDropsEvent 里会消费掉
		if(this.looting > 0) {
			entity.getEntityData().setInteger("ntmLaserLooting", this.looting);
			entity.getEntityData().setBoolean("ntmLaserBurn", true);
		}
		if(source.exploder == entity) amount *= 0.5F;
		EntityLivingBase shooter = this.attacker != null ? this.attacker
				: (source.exploder instanceof EntityLivingBase ? (EntityLivingBase) source.exploder : null);
		DamageSource dmg = BulletConfig.getDamage(null, shooter, clazz);
		if(!(entity instanceof EntityLivingBase)) {
			entity.attackEntityFrom(dmg, amount);
		} else {
			EntityDamageUtil.attackEntityFromNT((EntityLivingBase) entity, dmg, amount, true, false, 0F, pierceDT, pierceDR);
			if(entity.isEntityAlive()) entity.getEntityData().removeTag("ntmLaserLooting");

			// 伤害源带了玩家，attackEntityFrom 里会把 recentlyHit 置 100，
			// 20 tick 后 onDeathUpdate 就会生成经验球。这里打死后立刻抹掉。
			if(this.noExperience) {
				EntityLivingBase living = (EntityLivingBase) entity;
				living.recentlyHit = 0;
				living.attackingPlayer = null;
			}
			if(!entity.isEntityAlive()) ConfettiUtil.decideConfetti((EntityLivingBase) entity, dmg);
		}
	}

	@Override
	public float calculateDamage(double distanceScaled, double density, double knockback, float size) {
		if(density < 0.125) return 0; //shitty hack
		return (float) (fixedDamage * (1 - distanceScaled));
	}
}
