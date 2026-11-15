package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Ruby programs use Bundler and a private install path when a Gemfile exists. */
public final class RubyLanguageProvider implements LanguageProvider {
    public String id() { return "ruby"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.rb");
        File sdk = ProgramFiles.sdk(directory, "ruby/tranforlanguage.rb").getParentFile();
        String ruby = System.getProperty("tranforlanguage.ruby", "ruby");
        List<String> args = Arrays.asList(ruby, "-E", "UTF-8:UTF-8", "-I", sdk.getAbsolutePath(), source.getAbsolutePath());
        File manifest = new File(directory, "Gemfile");
        if (!manifest.isFile()) return new LaunchCommand(directory, args);
        File lock = new File(directory, "Gemfile.lock");
        File bundlePath = new File(directory, ".tranforlanguage/ruby-gems");
        Map<String, String> env = new HashMap<>();
        env.put("BUNDLE_GEMFILE", manifest.getAbsolutePath());
        env.put("BUNDLE_PATH", bundlePath.getAbsolutePath());
        String fingerprint = ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null);
        File stamp = new File(directory, ".tranforlanguage/ruby-dependencies");
        if (!ProgramFiles.matches(stamp, fingerprint)) {
            ProgramFiles.build(directory, directory, Arrays.asList(
                System.getProperty("tranforlanguage.bundle", "bundle"), "install", "--quiet"), env, 300);
            ProgramFiles.stamp(stamp, fingerprint);
            logger.info("Ruby dependencies installed for " + directory.getName());
        }
        List<String> bundled = new ArrayList<>();
        bundled.add(System.getProperty("tranforlanguage.bundle", "bundle"));
        bundled.add("exec");
        bundled.addAll(args);
        return new LaunchCommand(directory, bundled, env);
    }
}
