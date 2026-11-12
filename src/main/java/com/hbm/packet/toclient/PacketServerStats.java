package com.hbm.packet.toclient;

import com.hbm.main.ModEventHandlerClient;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Periodic snapshot of the server tick time, so the client can show it in the tab list overlay
 * (hold Tab). Values are derived from MinecraftServer.tickTimeArray, i.e. what the vanilla
 * debug screen reports as avg tick time.
 */
public class PacketServerStats implements IMessage {

	private float mspt;
	private float tps;

	public PacketServerStats() { }

	public PacketServerStats(float mspt, float tps) {
		this.mspt = mspt;
		this.tps = tps;
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		this.mspt = buf.readFloat();
		this.tps = buf.readFloat();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeFloat(this.mspt);
		buf.writeFloat(this.tps);
	}

	public static class Handler implements IMessageHandler<PacketServerStats, IMessage> {

		@Override
		public IMessage onMessage(PacketServerStats message, MessageContext ctx) {
			ModEventHandlerClient.mspt = message.mspt;
			ModEventHandlerClient.tps = message.tps;
			return null;
		}
	}
}
