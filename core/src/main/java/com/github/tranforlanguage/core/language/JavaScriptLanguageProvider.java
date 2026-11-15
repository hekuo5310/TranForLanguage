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
            File project = new File(directory, ".tranforlanguage/node-project");
            Files.createDirectories(project.toPath());
            String fingerprint = ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null);
            File stamp = new File(project, ".tfl-dependencies");
            if (!ProgramFiles.matches(stamp, fingerprint)) {
                ProgramFiles.deleteTree(new File(project, "node_modules"));
                Files.copy(manifest.toPath(), new File(project, "package.json").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                File targetLock = new File(project, "package-lock.json");
                if (lock.isFile()) Files.copy(lock.toPath(), targetLock.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(targetLock.toPath());
                ProgramFiles.build(directory, project, Arrays.asList(
                    System.getProperty("tranforlanguage.npm", "npm"),
                    lock.isFile() ? "ci" : "install", "--no-audit", "--no-fund"), Collections.emptyMap(), 300);
                ProgramFiles.stamp(stamp, fingerprint);
                logger.info("JavaScript dependencies installed for " + directory.getName());
            }
            modules = new File(project, "node_modules");
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
