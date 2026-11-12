package com.hbm.packet.toserver;

import com.hbm.entity.misc.EntityJindouYun;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 客户端 → 服务端：筋斗云加速键（Ctrl）状态。
 *
 * 骑乘时客户端不会走 sendMotionUpdates()，冲刺状态根本同步不到服务端，
 * 所以加速键得自己发一个包，只在状态变化时发。
 */
public class PacketJindouYunBoost implements IMessage {

	boolean boost;

	public PacketJindouYunBoost() { }

	public PacketJindouYunBoost(boolean boost) {
		this.boost = boost;
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		boost = buf.readBoolean();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeBoolean(boost);
	}

	public static class Handler implements IMessageHandler<PacketJindouYunBoost, IMessage> {

		@Override
		public IMessage onMessage(PacketJindouYunBoost m, MessageContext ctx) {

			EntityPlayer player = ctx.getServerHandler().playerEntity;

			if(player != null && player.ridingEntity instanceof EntityJindouYun) {
				((EntityJindouYun) player.ridingEntity).boost = m.boost;
			}

			return null;
		}
	}
}
