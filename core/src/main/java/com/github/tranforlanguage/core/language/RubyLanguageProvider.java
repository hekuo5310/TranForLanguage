package com.github.tranforlanguage.core.language;

import com.github.tranforlanguage.core.jvm.JvmEngines;
import com.github.tranforlanguage.core.jvm.RubyEngine;
import com.github.tranforlanguage.core.jvm.RuntimeLibraries;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
        // Bundler gems may carry native extensions, which JRuby cannot load; those programs stay processes.
        if (!manifest.isFile()) {
            LaunchCommand jvm = InJvm.attempt(configuration, logger, "Ruby", () -> inJvm(directory, configuration, sdk, source, logger));
            if (jvm != null) return jvm;
        }
        if (!manifest.isFile()) return new LaunchCommand(directory, args);
        File lock = new File(directory, "Gemfile.lock");
        File bundlePath = new File(directory, ".tranforlanguage/ruby-gems");
        Map<String, String> env = new HashMap<>();
        env.put("BUNDLE_GEMFILE", manifest.getAbsolutePath());
        env.put("BUNDLE_PATH", bundlePath.getAbsolutePath());
        String fingerprint = ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null);
        File stamp = new File(directory, ".tranforlanguage/ruby-dependencies");
        String bundle = System.getProperty("tranforlanguage.bundle", "bundle");
        boolean usable = ProgramFiles.matches(stamp, fingerprint);
        if (usable) {
            // A stamp alone cannot guarantee that the private gem directory still exists.
            try {
                ProgramFiles.build(directory, directory, Arrays.asList(bundle, "check"), env, 30);
            } catch (IOException missing) {
                usable = false;
            }
        }
        if (!usable) {
            Files.deleteIfExists(stamp.toPath());
            ProgramFiles.build(directory, directory, Arrays.asList(
                bundle, "install", "--quiet"), env, 300);
            // Bundler is allowed to create/update Gemfile.lock during install.
            ProgramFiles.stamp(stamp, ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null));
            logger.info("Ruby dependencies installed for " + directory.getName());
        }
        List<String> bundled = new ArrayList<>();
        bundled.add(bundle);
        bundled.add("exec");
        bundled.addAll(args);
        return new LaunchCommand(directory, bundled, env);
    }

    /** Matches code that depends on its own file location, which changes to the compiled class in the JVM. */
    private static final java.util.regex.Pattern LOCATION = java.util.regex.Pattern.compile("__FILE__|__dir__|\\$0\\b|\\$PROGRAM_NAME");

    /** Compiles every Ruby file of the program and the SDK to JVM classes with JRuby, once per change. */
    private static LaunchCommand inJvm(File directory, JsonObject configuration, File sdk, File source, Logger logger) throws Exception {
        JvmEngines.requireJava(21, "JRuby");
        List<File> sources = new ArrayList<>();
        collect(directory, sources);
        if (JvmEngines.mode(configuration) == JvmEngines.Mode.AUTO) for (File file : sources) {
            String text = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (LOCATION.matcher(text).find())
                throw new UnsupportedOperationException(file.getName() + " uses __FILE__, __dir__ or $0, which would name the compiled class"
                    + " (set \"runtime\": \"jvm\" in program.json to run it in the JVM anyway)");
        }
        RubyEngine engine = JvmEngines.load(RuntimeLibraries.RUBY, "JRubyEngine", RubyEngine.class, logger);
        File output = new File(directory, ".tranforlanguage/ruby-classes");
        File stamp = new File(directory, ".tranforlanguage/ruby-classes.stamp");
        File sdkSource = new File(sdk, "tranforlanguage.rb");
        List<Object> inputs = new ArrayList<>();
        inputs.add(RuntimeLibraries.RUBY.toString());
        inputs.add(sdkSource);
        for (File file : sources) { inputs.add(directory.toPath().relativize(file.toPath()).toString()); inputs.add(file); }
        String fingerprint = ProgramFiles.fingerprint(inputs.toArray());
        if (!ProgramFiles.matches(stamp, fingerprint) || !output.isDirectory()) {
            Files.deleteIfExists(stamp.toPath());
            ProgramFiles.deleteTree(output);
            Files.createDirectories(output.toPath());
            engine.compile(sdk, Collections.singletonList(sdkSource), output);
            engine.compile(directory, sources, output);
            ProgramFiles.stamp(stamp, fingerprint);
            logger.info("Ruby compiled to JVM classes with JRuby: " + sources.size() + " file(s)");
        }
        String relative = directory.toPath().relativize(source.toPath()).toString();
        File main = new File(output, relative.substring(0, relative.length() - ".rb".length()) + ".class");
        if (!main.isFile()) throw new IOException("JRuby did not produce " + main.getName());
        return LaunchCommand.inJvm(directory, engine.program(output, main, directory), Collections.<String, String>emptyMap());
    }

    /** Ruby files of the program, skipping hidden folders such as the managed .tranforlanguage. */
    private static void collect(File folder, List<File> sources) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            if (file.getName().startsWith(".")) continue;
            if (file.isDirectory()) collect(file, sources);
            else if (file.getName().endsWith(".rb")) sources.add(file);
        }
    }
}
