package com.hbm.packet.toserver;

import com.hbm.entity.misc.EntityJindouYun;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端 → 服务端：筋斗云的位置。
 *
 * 回弹的根源是"双头模拟"：客户端预测一份、服务端也算一份，两边只要有一点点不同
 * （服务端 TPS 抖动、输入晚到 1 tick），客户端就会被拉回去。
 *
 * 这里换成客户端权威：客户端算完直接移动，再把结果发给服务端，服务端只做范围校验后照单接收。
 * 不需要纠正，也就不会回弹。
 */
public class PacketJindouYunMove implements IMessage {

	private int entityId;
	private double x, y, z;
	private float yaw, pitch;

	public PacketJindouYunMove() { }

	public PacketJindouYunMove(int entityId, double x, double y, double z, float yaw, float pitch) {
		this.entityId = entityId;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		this.entityId = buf.readInt();
		this.x = buf.readDouble();
		this.y = buf.readDouble();
		this.z = buf.readDouble();
		this.yaw = buf.readFloat();
		this.pitch = buf.readFloat();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeInt(this.entityId);
		buf.writeDouble(this.x);
		buf.writeDouble(this.y);
		buf.writeDouble(this.z);
		buf.writeFloat(this.yaw);
		buf.writeFloat(this.pitch);
	}

	public static class Handler implements IMessageHandler<PacketJindouYunMove, IMessage> {

		@Override
		public IMessage onMessage(PacketJindouYunMove m, MessageContext ctx) {

			EntityPlayerMP player = ctx.getServerHandler().playerEntity;
			if(player == null) return null;

			Entity entity = player.worldObj.getEntityByID(m.entityId);

			if(entity instanceof EntityJindouYun) {
				EntityJindouYun cloud = (EntityJindouYun) entity;
				// 只有骑手本人能推动这朵云
				if(cloud.riddenByEntity == player) {
					cloud.acceptClientMove(m.x, m.y, m.z, m.yaw, m.pitch);
				}
			}

			return null;
		}
	}
}
