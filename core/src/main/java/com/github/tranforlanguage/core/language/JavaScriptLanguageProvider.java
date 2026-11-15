package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/** Node.js CommonJS programs; optional package.json dependencies are installed separately. */
public final class JavaScriptLanguageProvider implements LanguageProvider {
    public String id() { return "javascript"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.js");
        File sdk = ProgramFiles.sdk(directory, "javascript/tranforlanguage.js").getParentFile();
        String nodePath = sdk.getAbsolutePath();
        File manifest = new File(directory, "package.json");
        if (manifest.isFile()) {
            File lock = new File(directory, "package-lock.json");
            File project = new File(directory, ".tranforlanguage/node-project");
            ProgramFiles.cached(project, "npm", () -> {
                Files.copy(manifest.toPath(), new File(project, "package.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (lock.isFile()) Files.copy(lock.toPath(), new File(project, "package-lock.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
                List<String> command = new ArrayList<>(Arrays.asList(
                    System.getProperty("tranforlanguage.npm", "npm"), lock.isFile() ? "ci" : "install",
                    "--ignore-scripts", "--no-audit", "--no-fund"));
                if (!lock.isFile()) command.add("--package-lock=false");
                ProgramFiles.build(directory, project, command, Collections.<String, String>emptyMap(), 300);
            }, manifest, lock);
            nodePath += File.pathSeparator + new File(project, "node_modules").getAbsolutePath();
            logger.info("JavaScript dependencies ready");
        }
        String inherited = System.getenv("NODE_PATH");
        if (inherited != null && !inherited.isEmpty()) nodePath += File.pathSeparator + inherited;
        Map<String, String> environment = new HashMap<>();
        environment.put("NODE_PATH", nodePath);
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.node", "node"),
            source.getAbsolutePath()), environment);
    }
}
