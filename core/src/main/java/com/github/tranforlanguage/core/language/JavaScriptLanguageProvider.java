package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/** Node CommonJS programs; optional package.json dependencies are isolated in managed node_modules. */
public final class JavaScriptLanguageProvider implements LanguageProvider {
    public String id() { return "javascript"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.js");
        File sdk = ProgramFiles.sdk(directory, "javascript/tranforlanguage.js").getParentFile();
        File manifest = new File(directory, "package.json");
        File modules = null;
        if (manifest.isFile()) {
            File lock = new File(directory, "package-lock.json");
            // Run npm in the ORIGINAL directory: file: dependencies and lockfile links
            // must retain their original relative-path context.
            File stamp = new File(directory, ".tranforlanguage/node-dependencies");
            modules = new File(directory, "node_modules");
            String fingerprint = ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null);
            com.google.gson.JsonObject packageJson = new com.google.gson.Gson().fromJson(
                new String(Files.readAllBytes(manifest.toPath()), java.nio.charset.StandardCharsets.UTF_8),
                com.google.gson.JsonObject.class);
            boolean hasPackages = false;
            for (String key : Arrays.asList("dependencies", "devDependencies", "optionalDependencies")) {
                if (packageJson.has(key) && packageJson.get(key).isJsonObject()
                    && packageJson.getAsJsonObject(key).size() > 0) hasPackages = true;
            }
            if (!ProgramFiles.matches(stamp, fingerprint) || (hasPackages && !modules.isDirectory())) {
                Files.deleteIfExists(stamp.toPath());
                ProgramFiles.deleteTree(modules);
                List<String> command = new ArrayList<>();
                command.add(System.getProperty("tranforlanguage.npm", "npm"));
                command.add(lock.isFile() ? "ci" : "install");
                command.addAll(Arrays.asList("--no-save", "--no-audit", "--no-fund"));
                if (!lock.isFile()) command.add("--no-package-lock");
                ProgramFiles.build(directory, directory, command, Collections.emptyMap(), 300);
                ProgramFiles.stamp(stamp, ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null));
                logger.info("JavaScript dependencies installed for " + directory.getName());
            }
        }
        Map<String, String> environment = new HashMap<>();
        StringBuilder path = new StringBuilder(sdk.getAbsolutePath());
        if (modules != null) path.append(File.pathSeparator).append(modules.getAbsolutePath());
        String inherited = System.getenv("NODE_PATH");
        if (inherited != null && !inherited.isEmpty()) path.append(File.pathSeparator).append(inherited);
        environment.put("NODE_PATH", path.toString());
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.node", "node"),
            source.getAbsolutePath()), environment);
    }
}
