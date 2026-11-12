package com.hbm.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * 区块相关的几个 redirect，全部只改"怎么算"，不改"算出什么"：
 *
 *  1. Chunk.func_150809_p()                → ChunkCost.light      （计时）
 *  2. ChunkProviderServer 里的 loadChunk   → ChunkCost.disk       （计时）
 *  3. ChunkProviderServer 里的 saveChunk   → ChunkCost.save       （计时）
 *  4. ChunkProviderServer 里 unloadQueuedChunks 的循环上界 100 → ChunkCost.UNLOAD_PER_TICK
 *  5. AnvilChunkLoader 里 World.getPendingBlockUpdates(Chunk,boolean) → ChunkSaveIndex.get
 *     （把"每存一个区块就扫一遍全部待处理更新"改成按区块分桶查询）
 *
 * 方法名在不同环境可能是 MCP 或 srg，所以关键位置都按描述符匹配。
 */
public class ChunkCostTransformer implements IClassTransformer {

	private static final String CHUNK = "net.minecraft.world.chunk.Chunk";
	private static final String PROVIDER = "net.minecraft.world.gen.ChunkProviderServer";
	private static final String ANVIL = "net.minecraft.world.chunk.storage.AnvilChunkLoader";
	private static final String BIOME_CACHE = "net.minecraft.world.biome.BiomeCache";
	private static final String CHUNK_MANAGER = "net.minecraft.world.WorldChunkManager";

	/** 工作线程查询 biome 时为 null 的方法：给它们加 synchronized，让并发查询不返回半成品 */
	private static final String[] SYNC_CACHE_METHODS = { "func_76840_a", "getBiomeCacheBlock" };
	private static final String[] SYNC_MANAGER_METHODS = { "func_76935_a", "getBiomeGenForCoords" };

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {

		if(basicClass == null) return basicClass;

		try {
			if(ANVIL.equals(transformedName)) return patchPendingUpdates(basicClass);

			// 并行生成的实验：biome 查询加锁，避免工作线程拿到未初始化的 BiomeCacheBlock
			// 并行生成实验：MapGenBase.rand 改成每线程一份
			if(MapGenRand.ENABLED && transformedName != null
					&& (transformedName.startsWith("net.minecraft.world.gen.MapGen")
						|| transformedName.startsWith("com.hbm.world.gen"))) {
				return patchRand(basicClass);
			}

			// AI 邻居查询计时（只测不改）。方法名在 srg 环境是 func_72872_a，按描述符 + owner 匹配
			if(transformedName != null && transformedName.startsWith("net.minecraft.entity.ai.")) {
				return patchWorldQuery(basicClass);
			}

			if(BIOME_CACHE.equals(transformedName)) return patchSynchronized(basicClass, SYNC_CACHE_METHODS);
			if(CHUNK_MANAGER.equals(transformedName)) return patchSynchronized(basicClass, SYNC_MANAGER_METHODS);

			if(CHUNK.equals(transformedName)) {
				return patchCall(basicClass, "func_150809_p", "()V",
						"com/hbm/core/ChunkCost", "light", "(Lnet/minecraft/world/chunk/Chunk;)V", false);
			}

			if(PROVIDER.equals(transformedName)) {
				byte[] out = patchCall(basicClass, "loadChunk",
						"(Lnet/minecraft/world/World;II)Lnet/minecraft/world/chunk/Chunk;",
						"com/hbm/core/ChunkCost", "disk",
						"(Lnet/minecraft/world/chunk/storage/IChunkLoader;Lnet/minecraft/world/World;II)Lnet/minecraft/world/chunk/Chunk;",
						false);
				out = patchCall(out, "saveChunk",
						"(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/Chunk;)V",
						"com/hbm/core/ChunkCost", "save",
						"(Lnet/minecraft/world/chunk/storage/IChunkLoader;Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/Chunk;)V",
						false);
				out = patchUnloadBudget(out);
				return out;
			}

		} catch(Throwable t) {
			// 转换失败就原样放行，绝不因此让游戏起不来
			return basicClass;
		}

		return basicClass;
	}

	/**
	 * 把 {@code getfield MapGenBase.rand:Ljava/util/Random;} 换成
	 * {@code invokestatic MapGenRand.get(LMapGenBase;)Ljava/util/Random;}。
	 * 写字段（PUTFIELD，只在构造函数里）保持不动。
	 */
	private byte[] patchRand(byte[] basicClass) {

		final String owner = "net/minecraft/world/gen/MapGenBase";
		final String name = "rand";
		final String desc = "Ljava/util/Random;";

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
					MethodVisitor parent = super.visitMethod(access, n, d, sig, ex);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitFieldInsn(int opcode, String fOwner, String fName, String fDesc) {
							// 必须校验 owner：NTMWorldGenerator 之类的类也有自己的 rand 字段，
							// 只按名字匹配会把它们一起改掉 → VerifyError
							if(opcode == Opcodes.GETFIELD && owner.equals(fOwner)
									&& name.equals(fName) && desc.equals(fDesc)) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/hbm/core/MapGenRand",
										"get", "(Lnet/minecraft/world/gen/MapGenBase;)Ljava/util/Random;");
								patched[0]++;
								return;
							}
							super.visitFieldInsn(opcode, fOwner, fName, fDesc);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) return basicClass;
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}

	/**
	 * 把 AI 里对 World.getEntitiesWithinAABB(Class, AABB) 的调用重定向到计时器。
	 * 按 "owner 是 World + 描述符匹配" 识别，兼容 srg(func_72872_a) 与 mcp 两种方法名。
	 */
	private byte[] patchWorldQuery(byte[] basicClass) {

		final String desc = "(Ljava/lang/Class;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;";
		final String hookDesc = "(Lnet/minecraft/world/World;Ljava/lang/Class;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;";

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
					MethodVisitor parent = super.visitMethod(access, n, d, sig, ex);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String mdesc) {
							if(opcode == Opcodes.INVOKEVIRTUAL && desc.equals(mdesc)
									&& owner != null && owner.startsWith("net/minecraft/world/World")) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/hbm/core/ChunkCost",
										"aiScan", hookDesc);
								patched[0]++;
								return;
							}
							super.visitMethodInsn(opcode, owner, name, mdesc);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) return basicClass;
			System.out.println("[hbm-core] ChunkCostTransformer: AI 邻居查询计时已挂 " + patched[0] + " 处");
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}


	/**
	 * 给指定方法加 ACC_SYNCHRONIZED。
	 *
	 * 用途：并行生成实验时让 BiomeCache / WorldChunkManager 的并发查询不返回半成品。
	 * 结论：加锁有效（不再 NPE），但整个并行方案因为 mod 侧共享状态而关闭（见 MapGenRand）。
	 * 这两个类**始终**会被打补丁（与开关无关），因为 synchronized 对单线程行为没有影响。
	 */
	private byte[] patchSynchronized(byte[] basicClass, final String[] names) {

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
					for(String name : names) {
						if(name.equals(n)) {
							access |= Opcodes.ACC_SYNCHRONIZED;
							patched[0]++;
							break;
						}
					}
					return super.visitMethod(access, n, d, sig, ex);
				}
			}, 0);

			if(patched[0] == 0) return basicClass;
			System.out.println("[hbm-core] ChunkCostTransformer: " + new String(names[0]) + " 等 "
					+ patched[0] + " 个方法已加 synchronized");
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}

	/** 把某个方法调用重定向到静态钩子。virtualOnly=true 时只认 INVOKEVIRTUAL（避免误伤接口调用）。 */
	private byte[] patchCall(byte[] basicClass, final String method, final String desc,
			final String hookOwner, final String hookName, final String hookDesc, final boolean virtualOnly) {

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
					MethodVisitor parent = super.visitMethod(access, n, d, sig, ex);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String mdesc) {

							boolean opcodeOk = virtualOnly ? opcode == Opcodes.INVOKEVIRTUAL
									: (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE);

							if(opcodeOk && method.equals(name) && desc.equals(mdesc)) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc);
								patched[0]++;
								return;
							}
							super.visitMethodInsn(opcode, owner, name, mdesc);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) return basicClass;
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}

	/** 把 unloadQueuedChunks 里的常量 100 换成 ChunkCost.UNLOAD_PER_TICK */
	private byte[] patchUnloadBudget(byte[] basicClass) {

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {

					MethodVisitor parent = super.visitMethod(access, n, d, sig, ex);
					if(parent == null) return null;

					// 方法名可能是 unloadQueuedChunks(mcp) 或 func_73156_b(srg)
					if(!"unloadQueuedChunks".equals(n) && !"func_73156_b".equals(n)) return parent;
					if(!"()Z".equals(d)) return parent;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitIntInsn(int opcode, int operand) {
							if((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && operand == 100) {
								super.visitFieldInsn(Opcodes.GETSTATIC, "com/hbm/core/ChunkCost",
										"UNLOAD_PER_TICK", "I");
								patched[0] = 1;
								return;
							}
							super.visitIntInsn(opcode, operand);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) {
				System.out.println("[hbm-core] ChunkCostTransformer: 卸载预算没找到常量 100，保持原样");
				return basicClass;
			}

			System.out.println("[hbm-core] ChunkCostTransformer: 卸载预算已生效（每 tick 最多 "
					+ ChunkCost.UNLOAD_PER_TICK + " 个）");
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}

	/**
	 * 重定向 AnvilChunkLoader 里对 World.getPendingBlockUpdates(Chunk, boolean) 的调用。
	 * 按 "owner 是 net/minecraft/world/World* + 描述符完全匹配" 来认，兼容 srg / mcp 两套方法名。
	 */
	private byte[] patchPendingUpdates(byte[] basicClass) {

		final String desc = "(Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;";
		final String hookDesc = "(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;";

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
					MethodVisitor parent = super.visitMethod(access, n, d, sig, ex);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String mdesc) {
							if(opcode == Opcodes.INVOKEVIRTUAL && desc.equals(mdesc)
									&& owner != null && owner.startsWith("net/minecraft/world/World")) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/hbm/core/ChunkSaveIndex",
										"get", hookDesc);
								patched[0]++;
								return;
							}
							super.visitMethodInsn(opcode, owner, name, mdesc);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) {
				System.out.println("[hbm-core] ChunkCostTransformer: 待处理更新查询没有匹配到调用点，保持原样");
				return basicClass;
			}

			System.out.println("[hbm-core] ChunkCostTransformer: 待处理更新查询已改为分桶索引（" + patched[0] + " 处）");
			return writer.toByteArray();

		} catch(Throwable t) {
			return basicClass;
		}
	}
}
