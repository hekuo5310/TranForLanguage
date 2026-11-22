package com.github.tranforlanguage.core.language;

import com.github.tranforlanguage.core.jvm.InJvmProcess;
import com.github.tranforlanguage.core.jvm.JvmProgram;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * How to start a program: an argv for a separate process, or a program already translated to JVM
 * classes that runs inside the server. Either way it speaks the same protocol over stdin/stdout.
 */
public final class LaunchCommand {
    private final List<String> arguments;
    private final File directory;
    private final Map<String, String> environment;
    private final JvmProgram jvm;
    public LaunchCommand(File directory, List<String> arguments) {
        this(directory, arguments, Collections.<String, String>emptyMap());
    }
    public LaunchCommand(File directory, List<String> arguments, Map<String, String> environment) {
        if (arguments.isEmpty() || arguments.get(0).trim().isEmpty()) throw new IllegalArgumentException("Empty program command");
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        this.directory = directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        this.jvm = null;
    }
    private LaunchCommand(File directory, JvmProgram jvm, Map<String, String> environment) {
        this.arguments = Collections.emptyList();
        this.directory = directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        this.jvm = Objects.requireNonNull(jvm);
    }
    /** A translated program that runs on server threads instead of in its own process. */
    public static LaunchCommand inJvm(File directory, JvmProgram program, Map<String, String> environment) {
        return new LaunchCommand(directory, program, environment);
    }
    /** The process argv; empty for an in-JVM program. */
    public List<String> arguments() { return arguments; }
    public File directory() { return directory; }
    /** Variables added to the inherited server environment for this program only. */
    public Map<String, String> environment() { return environment; }
    /** True when the program runs inside the server JVM. */
    public boolean inJvm() { return jvm != null; }

    /** Starts the program with the server environment plus {@link #environment()} and {@code extra}. */
    public Process start(Map<String, String> extra) throws IOException {
        if (jvm == null) {
            ProcessBuilder builder = new ProcessBuilder(arguments).directory(directory);
            builder.environment().putAll(environment);
            builder.environment().putAll(extra);
            return builder.start();
        }
        Map<String, String> variables = new LinkedHashMap<>(System.getenv());
        variables.putAll(environment);
        variables.putAll(extra);
        return new InJvmProcess(directory.getName(), jvm, Collections.unmodifiableMap(variables)).start();
    }
}
