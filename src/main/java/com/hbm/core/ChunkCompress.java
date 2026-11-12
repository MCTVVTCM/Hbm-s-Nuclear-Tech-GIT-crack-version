package com.hbm.core;

/**
 * 区块包压缩级别的运行时开关。
 *
 * {@link ChunkPacketTransformer} 会把 S21PacketChunkData 里的 {@code new Deflater(-1)}
 * 改写成 {@code new Deflater(ChunkCompress.LEVEL)}，所以这个值可以在游戏里热替换，不用重开。
 *
 * 实测（196864 字节的满区块原始载荷）：
 *   LEVEL = 6（默认）  16.0 ms/区块   压缩后 19%
 *   LEVEL = 1          1.5 ms/区块    压缩后 25%
 *   LEVEL = 0（不压缩） 0.13 ms/区块   压缩后 100%
 *
 * 注意 LEVEL = 0 只在"外层不再压缩一遍"时才有意义：
 * 1.7.10 的 Netty 压缩器对超过阈值（默认 256 字节）的包还会再压一次。
 * 如果外层压缩是开的，level 0 会把这 196KB 原样丢给外层，由外层按默认级别压，
 * 反而比现在的 level 1 更慢。所以默认保持 1。
 */
public final class ChunkCompress {

	/**
	 * Deflater 压缩级别。0 = 不压缩，1 = 最快，6 = 默认。
	 * 启动参数 {@code -Dhbm.chunk.deflate=0} 可以直接改，不用重新打包。
	 */
	public static int LEVEL = Integer.getInteger("hbm.chunk.deflate", 1).intValue();

	private ChunkCompress() { }
}
