package com.github.tranforlanguage.core.jvm.engine;

import com.github.tranforlanguage.core.jvm.JavaInstrumenter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM rewrite of System/Runtime process-wide calls into {@code tranforlanguage/ProgramSystem}. */
public final class AsmJavaInstrumenter implements JavaInstrumenter {
    static final String BRIDGE = "tranforlanguage/ProgramSystem";

    @Override public void instrument(File input, File output) throws Exception {
        Files.createDirectories(output.toPath());
        if (input.isDirectory()) {
            Path root = input.toPath();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    Path target = output.toPath().resolve(root.relativize(file).toString());
                    try (InputStream in = Files.newInputStream(file)) { write(file.toString(), in, target); }
                }
            }
        } else {
            try (JarFile jar = new JarFile(input)) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    Path target = output.toPath().resolve(entry.getName()).normalize();
                    if (!target.startsWith(output.toPath())) throw new IOException("Unsafe jar entry " + entry.getName() + " in " + input);
                    try (InputStream in = jar.getInputStream(entry)) { write(entry.getName(), in, target); }
                }
            }
        }
    }

    private static void write(String name, InputStream in, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        byte[] original = in.readAllBytes();
        ClassReader reader = new ClassReader(original);
        if (reader.getClassName().equals(BRIDGE)) {
            Files.write(target, original);
            return;
        }
        // Only instructions are replaced, never frames or the stack shape, so no frame recomputation is needed.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new Redirect(super.visitMethod(access, method, descriptor, signature, exceptions));
            }
        }, 0);
        Files.write(target, writer.toByteArray());
    }

    private static final class Redirect extends MethodVisitor {
        Redirect(MethodVisitor next) { super(Opcodes.ASM9, next); }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC && owner.equals("java/lang/System")
                && (name.equals("in") || name.equals("out") || name.equals("err"))) owner = BRIDGE;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")) {
                switch (name + descriptor) {
                    case "exit(I)V":
                    case "getenv(Ljava/lang/String;)Ljava/lang/String;":
                    case "getenv()Ljava/util/Map;":
                    case "setIn(Ljava/io/InputStream;)V":
                    case "setOut(Ljava/io/PrintStream;)V":
                    case "setErr(Ljava/io/PrintStream;)V":
                        owner = BRIDGE;
                        break;
                    default:
                }
            } else if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Runtime")
                && descriptor.equals("(I)V") && (name.equals("exit") || name.equals("halt"))) {
                // runtime.exit(code) -> ProgramSystem.runtimeExit(runtime, code): same stack, now a static call.
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, name.equals("exit") ? "runtimeExit" : "runtimeHalt",
                    "(Ljava/lang/Runtime;I)V", false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
