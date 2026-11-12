package com.hbm.items.tool;

import java.util.List;

import com.hbm.entity.misc.EntityJindouYun;
import com.hbm.util.i18n.I18nUtil;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

/**
 * 筋斗云召唤器。右键召唤一朵云并直接骑上去，再右键一次收云。
 */
public class ItemJindouYun extends Item {

	@Override
	public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {

		if(!world.isRemote) {

			// 诊断：客户端以为没骑云、但世界里还有一朵"认为自己载着玩家"的云
			if(!(player.ridingEntity instanceof EntityJindouYun)) {
				for(Object o : world.loadedEntityList) {
					if(o instanceof EntityJindouYun && ((EntityJindouYun) o).riddenByEntity == player) {
						com.hbm.main.MainRegistry.logger.info("[jy-two] 玩家 ridingEntity="
								+ (player.ridingEntity == null ? "null" : player.ridingEntity.getClass().getSimpleName())
								+ " 但云 " + ((EntityJindouYun) o).getEntityId() + " 认为自己载着玩家");
					}
				}
			}

			// 再点一次 = 下云 + 收云，云不当场散落在地上
			if(player.ridingEntity instanceof EntityJindouYun) {
				EntityJindouYun cloud = (EntityJindouYun) player.ridingEntity;
				player.mountEntity(null);
				// 骑乘期间可能是 noClip（穿墙模式），下云必须还回去，否则玩家会一直穿墙
				player.noClip = false;
				cloud.setDead();
				return stack;
			}

			// 世界里已经有一朵"认为自己载着这个玩家"的云时，直接骑回去，绝不再召唤第二朵。
			// 否则会出现两朵云互相抢骑手：旧云清空骑手 → 自毁 → S13 把客户端正在骑的那朵也删掉，
			// 表现就是"云凭空消失""要点两次右键"。
			for(Object o : world.loadedEntityList) {
				if(!(o instanceof EntityJindouYun)) continue;
				EntityJindouYun existing = (EntityJindouYun) o;
				if(existing.riddenByEntity == player || existing.pendingRider == player) {
					player.mountEntity(existing);
					com.hbm.main.MainRegistry.logger.info("[jy-reuse] 重新骑上已有的云 " + existing.getEntityId()
							+ "（不再召唤新的）");
					return stack;
				}
			}

			EntityJindouYun cloud = new EntityJindouYun(world);
			cloud.setPosition(player.posX, player.posY + 0.5D, player.posZ);
			cloud.rotationYaw = player.rotationYaw;
			// 同 tick 里 spawn 完立刻 mountEntity，实体还没进 tracker，客户端可能收不到
			// S1BPacketEntityAttach，结果服务端以为在骑、客户端以为在走，输入全都不响应。
			// 交给云自己的第一个 tick 去挂载。
			cloud.pendingRider = player;
			world.spawnEntityInWorld(cloud);
		}

		return stack;
	}

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean bool) {
		list.add(EnumChatFormatting.AQUA + I18nUtil.resolveKey("item.jindouyun.desc"));
	}
}
