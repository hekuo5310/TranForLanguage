package com.github.tranforlanguage.core;

import java.util.List;

/** Argument access for {@code @server} methods with consistent error messages on every platform. */
public final class ServerArgs {
    private final String method;
    private final List<String> args;
    public ServerArgs(String method, List<String> args) { this.method = method; this.args = args; }
    public int size() { return args.size(); }
    public boolean has(int index) { return index < args.size() && !args.get(index).isEmpty(); }
    public String text(int index, String name) {
        if (index >= args.size()) throw new IllegalArgumentException(method + ": missing argument " + name);
        return args.get(index);
    }
    public String text(int index, String name, String fallback) { return has(index) ? text(index, name) : fallback; }
    public double decimal(int index, String name) {
        String value = text(index, name).trim();
        try {
            double number = Double.parseDouble(value);
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(method + ": " + name + " must be a number: " + value); }
    }
    public int integer(int index, String name) {
        String value = text(index, name).trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(method + ": " + name + " must be an integer: " + value); }
    }
    public int integer(int index, String name, int fallback) { return has(index) ? integer(index, name) : fallback; }
    /** Block coordinates accept decimals ("10.7" means block 10), like vanilla commands. */
    public int block(int index, String name) { return (int) Math.floor(decimal(index, name)); }
    /** {@code minecraft:stone} → {@code stone}; other namespaces are kept for the platform to reject or resolve. */
    public static String vanillaPath(String id) {
        String trimmed = id.trim();
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }
    /** {@code ResourceKey[minecraft:dimension / minecraft:the_nether]} (as reported in events) → {@code minecraft:the_nether}. */
    public static String dimensionId(String world) {
        String value = world.trim();
        int slash = value.lastIndexOf(" / ");
        if (value.startsWith("ResourceKey[") && value.endsWith("]") && slash > 0) return value.substring(slash + 3, value.length() - 1);
        return value.contains(":") ? value : "minecraft:" + value;
    }
}
