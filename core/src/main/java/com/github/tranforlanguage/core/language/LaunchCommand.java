package com.github.tranforlanguage.core.language;

import java.io.File;
import java.util.*;

/** Immutable argv, working directory and extra environment, independent of compiler and language. */
public final class LaunchCommand {
    private final List<String> arguments;
    private final File directory;
    private final Map<String, String> environment;
    public LaunchCommand(File directory, List<String> arguments) {
        this(directory, arguments, Collections.<String, String>emptyMap());
    }
    public LaunchCommand(File directory, List<String> arguments, Map<String, String> environment) {
        if (arguments.isEmpty() || arguments.get(0).trim().isEmpty()) throw new IllegalArgumentException("Empty program command");
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        this.directory = directory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }
    public List<String> arguments() { return arguments; }
    public File directory() { return directory; }
    /** Variables added to the inherited server environment for this program only. */
    public Map<String, String> environment() { return environment; }
}
