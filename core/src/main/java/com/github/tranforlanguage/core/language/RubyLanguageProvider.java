package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/** Ruby scripts; a Gemfile opts into a private Bundler environment. */
public final class RubyLanguageProvider implements LanguageProvider {
    public String id() { return "ruby"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.rb");
        File sdk = ProgramFiles.sdk(directory, "ruby/tranforlanguage.rb").getParentFile();
        String ruby = System.getProperty("tranforlanguage.ruby", "ruby");
        List<String> command = new ArrayList<>();
        Map<String, String> environment = new HashMap<>();
        File manifest = new File(directory, "Gemfile");
        if (manifest.isFile()) {
            File lock = new File(directory, "Gemfile.lock");
            File project = new File(directory, ".tranforlanguage/ruby-project");
            environment.put("BUNDLE_GEMFILE", new File(project, "Gemfile").getAbsolutePath());
            environment.put("BUNDLE_PATH", new File(project, "gems").getAbsolutePath());
            ProgramFiles.cached(project, "bundler", () -> {
                Files.copy(manifest.toPath(), new File(project, "Gemfile").toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (lock.isFile()) Files.copy(lock.toPath(), new File(project, "Gemfile.lock").toPath(), StandardCopyOption.REPLACE_EXISTING);
                ProgramFiles.build(directory, project, Arrays.asList(
                    System.getProperty("tranforlanguage.bundle", "bundle"), "install"), environment, 300);
            }, manifest, lock);
            command.addAll(Arrays.asList(System.getProperty("tranforlanguage.bundle", "bundle"), "exec"));
            logger.info("Ruby dependencies ready");
        }
        command.addAll(Arrays.asList(ruby, "-E", "UTF-8:UTF-8", "-I", sdk.getAbsolutePath(), source.getAbsolutePath()));
        return new LaunchCommand(directory, command, environment);
    }
}
