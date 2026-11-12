package com.hbm.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * 把 WorldServer 里每个活跃区块的 {@code Chunk.func_150804_b(Z)V} 调用
 * 重定向到 {@link LightBudget#tick}，由它决定这一 tick 放行几个未点亮区块。
 *
 * 匹配方式：
 *   1. 优先按方法名 func_150804_b + 描述符 (Z)V 匹配（开发环境）；
 *   2. 名字对不上时退回"owner 是 Chunk 且描述符是 (Z)V"（混淆环境）。
 *
 * 被替换成 INVOKESTATIC 且描述符多了 Chunk 参数，栈上原有的 [chunk, int] 正好对上，不需要额外的 POP。
 */
public class LightBudgetTransformer implements IClassTransformer {

	private static final String TARGET = "net.minecraft.world.WorldServer";
	private static final String CHUNK_OWNER_HINT = "world/chunk/Chunk";
	private static final String METHOD = "func_150804_b";
	private static final String DESC = "(Z)V";

	private static final String HOOK_OWNER = "com/hbm/core/LightBudget";
	private static final String HOOK_NAME = "tick";
	private static final String HOOK_DESC = "(Lnet/minecraft/world/chunk/Chunk;Z)V";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {

		if(basicClass == null || !TARGET.equals(transformedName)) return basicClass;

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final int[] patched = new int[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] exceptions) {

					MethodVisitor parent = super.visitMethod(access, mName, desc, sig, exceptions);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String mName2, String mDesc) {

							boolean byName = METHOD.equals(mName2) && DESC.equals(mDesc);
							boolean byShape = owner != null && owner.contains(CHUNK_OWNER_HINT) && DESC.equals(mDesc);

							if(opcode == Opcodes.INVOKEVIRTUAL && (byName || byShape)) {
								super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC);
								patched[0]++;
								return;
							}

							super.visitMethodInsn(opcode, owner, mName2, mDesc);
						}
					};
				}
			}, 0);

			if(patched[0] == 0) {
				System.out.println("[hbm-core] LightBudgetTransformer: 没有匹配到调用点，保持原样");
				return basicClass;
			}

			System.out.println("[hbm-core] LightBudgetTransformer: 已重定向 " + patched[0] + " 处光照调用（预算 "
					+ com.hbm.core.LightBudget.PER_TICK + "/tick）");

			return writer.toByteArray();

		} catch(Throwable t) {
			// 转换失败就原样放行，绝不能因此让游戏起不来
			return basicClass;
		}
	}
}
