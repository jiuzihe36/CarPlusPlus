import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * 从闭源 SDK 的真 jar 生成**签名存根 jar**（compileOnly 用，不进产物）。
 *
 * 存根 = 保留类名 / 字段（含常量）/ 方法签名与继承关系，把方法体换成最小合法实现
 * （void → return；数值 → 0；对象 → null）。这样：
 *   · 宿主模块能正常编译（javac 需要的是签名，不是实现）；
 *   · 仓库里不分发闭源字节码；
 *   · 真 jar 升级后可以一条命令重新生成，不用手工补类。
 *
 * 用法：java -cp asm.jar:. Stubber <真 jar> <输出 jar> [保留包前缀,逗号分隔]
 */
public final class Stubber {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: Stubber <in.jar> <out.jar> [keepPrefixes,逗号分隔]");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String[] keep = args.length > 2 && !args[2].isEmpty() ? args[2].split(",") : new String[]{"com/ucar/", "com/share/", "com/ucarsink/"};

        int classes = 0, skipped = 0;
        try (JarFile jar = new JarFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".class")) continue;
                if (!keep(name)) { skipped++; continue; }
                byte[] raw = jar.getInputStream(e).readAllBytes();
                byte[] stub = stub(raw);
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.write(stub);
                zos.closeEntry();
                classes++;
            }
        }
        System.out.println("生成 " + classes + " 个存根类（跳过 " + skipped + " 个非 SDK 包）→ " + out);
    }

    private static boolean keep(String name) {
        return name.startsWith("com/ucar/") || name.startsWith("com/share/") || name.startsWith("com/ucarsink/");
    }

    private static byte[] stub(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor real = super.visitMethod(access, name, desc, sig, ex);
                if (real == null) return null;
                boolean needsBody = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                // 不挂 delegate：原始指令、注解、调试信息全部丢弃，只在 visitEnd 写最小实现。
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnd() {
                        if (needsBody) {
                            real.visitCode();
                            Type ret = Type.getReturnType(desc);
                            switch (ret.getSort()) {
                                case Type.VOID -> real.visitInsn(Opcodes.RETURN);
                                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                                    real.visitInsn(Opcodes.ICONST_0);
                                    real.visitInsn(Opcodes.IRETURN);
                                }
                                case Type.LONG -> {
                                    real.visitInsn(Opcodes.LCONST_0);
                                    real.visitInsn(Opcodes.LRETURN);
                                }
                                case Type.FLOAT -> {
                                    real.visitInsn(Opcodes.FCONST_0);
                                    real.visitInsn(Opcodes.FRETURN);
                                }
                                case Type.DOUBLE -> {
                                    real.visitInsn(Opcodes.DCONST_0);
                                    real.visitInsn(Opcodes.DRETURN);
                                }
                                default -> {
                                    real.visitInsn(Opcodes.ACONST_NULL);
                                    real.visitInsn(Opcodes.ARETURN);
                                }
                            }
                            real.visitMaxs(1, 1);
                        }
                        real.visitEnd();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private Stubber() {}
}
