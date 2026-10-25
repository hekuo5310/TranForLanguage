package com.github.tranforcpp.core.language;

import com.google.gson.*;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Runs a trusted interpreter or prebuilt binary using an explicit argv array. */
public final class ProcessLanguageProvider implements LanguageProvider {
    public String id() { return "process"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) {
        JsonArray command = configuration.getAsJsonArray("command");
        if (command == null || command.size() == 0) throw new IllegalArgumentException("process language requires a command array");
        List<String> args = new ArrayList<>();
        for (JsonElement argument : command) {
            if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("command arguments must be strings");
            args.add(argument.getAsString());
        }
        return new LaunchCommand(directory, args);
    }
}
