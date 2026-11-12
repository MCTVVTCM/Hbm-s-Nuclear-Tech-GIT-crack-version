package com.hbm.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * 把 S21PacketChunkData 里 {@code new Deflater(-1)} 的压缩级别换成 {@link ChunkCompress#LEVEL}。
 *
 * 原因：每个区块包都要现场压缩约 196KB 的原始数据。实测（满区块载荷）：
 *   级别 -1/6 → 16.0 ms/区块，压缩后 19%
 *   级别  1   →  1.5 ms/区块，压缩后 25%
 *   级别  0   →  0.13 ms/区块，压缩后 100%
 * 视距 10 时每前进一列要发 21 个包，单连接的所有写又绑在同一个 Netty 线程上串行执行，
 * 也就是每 tick 约 340ms 的纯压缩工作 —— 这才是"飞起来前方一片虚空"的主因。
 *
 * 这里只改网络上传输的字节，解压结果是完全相同的，世界/随机数/光照/实体顺序都不受影响。
 * 按字节码特征匹配（NEW java/util/zip/Deflater 之后的第一个整型常量），不依赖方法名，
 * 所以混淆名（srg）与开发名（mcp）都能命中。
 *
 * 被替换成 GETSTATIC com/hbm/core/ChunkCompress.LEVEL : I，所以级别可以在运行时热替换，
 * 不用重新打包。
 */
public class ChunkPacketTransformer implements IClassTransformer {

	private static final String TARGET = "net.minecraft.network.play.server.S21PacketChunkData";
	private static final String DEFLATER = "java/util/zip/Deflater";
	private static final String LEVEL_OWNER = "com/hbm/core/ChunkCompress";
	private static final String LEVEL_NAME = "LEVEL";
	private static final String LEVEL_DESC = "I";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {

		if(basicClass == null || !TARGET.equals(transformedName)) return basicClass;

		try {
			ClassReader reader = new ClassReader(basicClass);
			ClassWriter writer = new ClassWriter(reader, 0);
			final boolean[] patched = new boolean[1];

			reader.accept(new ClassVisitor(Opcodes.ASM4, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] exceptions) {
					MethodVisitor parent = super.visitMethod(access, mName, desc, sig, exceptions);
					if(parent == null) return null;

					return new MethodVisitor(Opcodes.ASM4, parent) {

						/** 刚看到 NEW Deflater，还没读到它的构造参数 */
						private boolean awaitingLevel = false;

						private void pushLevel() {
							super.visitFieldInsn(Opcodes.GETSTATIC, LEVEL_OWNER, LEVEL_NAME, LEVEL_DESC);
							patched[0] = true;
						}

						@Override
						public void visitTypeInsn(int opcode, String type) {
							super.visitTypeInsn(opcode, type);
							if(opcode == Opcodes.NEW && DEFLATER.equals(type)) {
								awaitingLevel = true;
							}
						}

						@Override
						public void visitInsn(int opcode) {
							// new 和构造调用之间只有一个 DUP，跳过它继续等
							if(awaitingLevel && opcode == Opcodes.DUP) {
								super.visitInsn(opcode);
								return;
							}
							if(awaitingLevel) {
								awaitingLevel = false;
								// ICONST_M1 == -1 == Deflater.DEFAULT_COMPRESSION
								if(opcode == Opcodes.ICONST_M1) {
									pushLevel();
									return;
								}
							}
							super.visitInsn(opcode);
						}

						@Override
						public void visitIntInsn(int opcode, int operand) {
							if(awaitingLevel) {
								awaitingLevel = false;
								if(operand == -1) {
									pushLevel();
									return;
								}
							}
							super.visitIntInsn(opcode, operand);
						}

						@Override
						public void visitLdcInsn(Object cst) {
							if(awaitingLevel) {
								awaitingLevel = false;
								if(cst instanceof Integer && ((Integer) cst).intValue() == -1) {
									pushLevel();
									return;
								}
							}
							super.visitLdcInsn(cst);
						}
					};
				}
			}, 0);

			if(!patched[0]) {
				System.err.println("[HBM-core] " + TARGET + ": no Deflater(-1) found, keeping original class");
				return basicClass;
			}

			System.out.println("[HBM-core] patched " + TARGET + " (deflate level -> ChunkCompress.LEVEL, now " + ChunkCompress.LEVEL + ")");
			return writer.toByteArray();

		} catch(Throwable t) {
			// 绝不能让一个字节码补丁把游戏启动搞崩
			System.err.println("[HBM-core] failed to patch " + TARGET + ", keeping original class");
			t.printStackTrace();
			return basicClass;
		}
	}
}
