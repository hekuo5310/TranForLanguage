package com.github.tranforcpp.core.language;

import java.io.File;
import java.util.*;

/** Immutable argv + working directory, independent of compiler and language. */
public final class LaunchCommand {
    private final List<String> arguments;
    private final File directory;
    public LaunchCommand(File directory, List<String> arguments) {
        if (arguments.isEmpty() || arguments.get(0).trim().isEmpty()) throw new IllegalArgumentException("Empty program command");
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        this.directory = directory;
    }
    public List<String> arguments() { return arguments; }
    public File directory() { return directory; }
}
