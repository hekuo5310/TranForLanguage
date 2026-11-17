package com.github.tranforlanguage.core.language;

import org.tomlj.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Produces a managed Cargo manifest without modifying the plugin author's TOML. */
final class CargoManifest {
    private CargoManifest() {}

    static String managed(File original, File sdk, File source) throws IOException {
        TomlParseResult doc = read(original);
        MutableTomlTable pkg = doc.getTable("package");
        if (pkg == null) throw new IOException("Rust plugin Cargo.toml requires [package]");
        // All paths in the copied manifest must still resolve relative to their original owner.
        relocate(doc, original.getCanonicalFile().getParentFile());

        MutableTomlTable workspace = doc.getTable("workspace");
        if (workspace == null) {
            File parent = workspaceManifest(original, pkg.getString("workspace"));
            if (parent != null) {
                TomlParseResult root = read(parent);
                MutableTomlTable inherited = root.getTable("workspace");
                if (inherited != null) {
                    relocateTable(inherited.getTable("dependencies"), parent.getCanonicalFile().getParentFile());
                    doc.set("workspace", inherited);
                }
            }
            workspace = doc.getOrCreateTable("workspace");
        }
        // The managed program is a single standalone member. Keep shared package,
        // dependency and resolver settings; do not include unrelated workspace members.
        workspace.remove("members");
        workspace.remove("default-members");
        workspace.remove("exclude");
        pkg.remove("workspace");
        pkg.set("autobins", false);

        MutableTomlTable dependencies = doc.getOrCreateTable("dependencies");
        MutableTomlTable existing = dependencies.getTable(Collections.singletonList("tranforlanguage"));
        if (existing != null) {
            existing.remove("workspace");
            existing.set("path", sdk.getAbsolutePath());
        } else {
            MutableTomlTable value = MutableTomlTable.createInline();
            value.set("path", sdk.getAbsolutePath());
            dependencies.set(Collections.singletonList("tranforlanguage"), value);
        }

        // A plugin has one explicit entrypoint even if its source manifest had other binaries.
        doc.remove("bin");
        String generated = doc.toToml() + "\n[[bin]]\nname=\"tranforlanguage_program\"\npath=\""
            + Toml.tomlEscape(source.getAbsolutePath()) + "\"\n";
        TomlParseResult check = Toml.parse(generated);
        if (check.hasErrors()) throw new IOException("Managed Rust manifest invalid: " + check.errors());
        return generated;
    }

    private static TomlParseResult read(File file) throws IOException {
        TomlParseResult value = Toml.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (value.hasErrors()) throw new IOException("Invalid Cargo.toml (" + file + "): " + value.errors());
        return value;
    }

    private static File workspaceManifest(File manifest, String explicit) throws IOException {
        File originalDir = manifest.getCanonicalFile().getParentFile();
        if (explicit != null) {
            File result = new File(new File(originalDir, explicit), "Cargo.toml").getCanonicalFile();
            if (!result.isFile() || read(result).getTable("workspace") == null)
                throw new IOException("Invalid package.workspace: " + explicit);
            return result;
        }
        for (File parent = originalDir.getParentFile(); parent != null; parent = parent.getParentFile()) {
            File candidate = new File(parent, "Cargo.toml");
            if (candidate.isFile() && read(candidate).getTable("workspace") != null) return candidate;
        }
        return null;
    }

    private static void relocate(MutableTomlTable root, File origin) throws IOException {
        for (String section : Arrays.asList("dependencies", "dev-dependencies", "build-dependencies", "replace"))
            relocateTable(root.getTable(section), origin);
        MutableTomlTable patches = root.getTable("patch");
        if (patches != null) for (String registry : patches.keySet())
            relocateTable(patches.getTable(Collections.singletonList(registry)), origin);
        MutableTomlTable workspace = root.getTable("workspace");
        if (workspace != null) relocateTable(workspace.getTable("dependencies"), origin);
        MutableTomlTable targets = root.getTable("target");
        if (targets != null) for (String targetName : targets.keySet()) {
            MutableTomlTable target = targets.getTable(Collections.singletonList(targetName));
            if (target != null) for (String section : Arrays.asList("dependencies", "dev-dependencies", "build-dependencies"))
                relocateTable(target.getTable(section), origin);
        }
    }

    private static void relocateTable(MutableTomlTable dependencies, File origin) throws IOException {
        if (dependencies == null) return;
        for (String name : dependencies.keySet()) {
            MutableTomlTable dependency = dependencies.getTable(Collections.singletonList(name));
            if (dependency == null || !dependency.isString("path")) continue;
            String location = dependency.getString("path");
            if (location == null || new File(location).isAbsolute()) continue;
            dependency.set("path", new File(origin, location).getCanonicalPath());
        }
    }
}
