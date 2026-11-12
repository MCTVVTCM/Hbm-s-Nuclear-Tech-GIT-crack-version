package com.hbm.core;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * HBM 的 coremod 入口。
 *
 * 目前两件事：
 *   1. 把区块包（S21PacketChunkData）的 zlib 压缩级别从 6 降到 1（纯网络层，解压结果不变）；
 *   2. 把 WorldServer 每 tick 的光照填充改成带预算的排队（见 LightBudget），
 *      避免一次加载几百个区块时同一 tick 里补完光照造成秒级卡顿。
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
public class HbmCorePlugin implements IFMLLoadingPlugin {

	@Override
	public String[] getASMTransformerClass() {
		return new String[] {
				"com.hbm.core.ChunkPacketTransformer",
				"com.hbm.core.LightBudgetTransformer",
				"com.hbm.core.ChunkCostTransformer"
		};
	}

	@Override
	public String getModContainerClass() {
		return null;
	}

	@Override
	public String getSetupClass() {
		return null;
	}

	@Override
	public void injectData(Map<String, Object> data) { }

	@Override
	public String getAccessTransformerClass() {
		// HBM 的访问转换器走 MANIFEST 里的 FMLAT（HBM_at.cfg），这里不需要再返回类名
		return null;
	}
}
