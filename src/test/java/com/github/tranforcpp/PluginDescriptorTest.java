package com.github.tranforcpp;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** The baseline CI target uses the real Spigot 1.8 description parser. */
public class PluginDescriptorTest {
    @Test public void legacyParserAcceptsTheModernApiMarker() throws Exception {
        byte[] metadata;
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[1024]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            metadata = output.toByteArray();
        }
        assertTrue(new String(metadata, StandardCharsets.UTF_8).contains("api-version: '1.13'"));
        PluginDescriptionFile description = new PluginDescriptionFile(new ByteArrayInputStream(metadata));
        assertEquals("TranForLanguage", description.getName());
        String projectVersion = System.getProperty("project.version");
        assertNotNull("Surefire must supply project.version", projectVersion);
        assertEquals(projectVersion, description.getVersion());
        assertEquals("com.github.tranforcpp.TranforCPlusPlus", description.getMain());
        assertTrue(description.getCommands().containsKey("tranforlanguage"));
        java.util.List<?> aliases = (java.util.List<?>) description.getCommands().get("tranforlanguage").get("aliases");
        assertTrue(aliases.contains("tranforcpp"));
        assertTrue(aliases.contains("cpp"));
        // Bukkit 1.8 lazily loads permissions through its global server logger.
        // Inspect the YAML mapping without requiring a running Bukkit server.
        java.util.Map<?, ?> yaml = (java.util.Map<?, ?>) new org.yaml.snakeyaml.Yaml()
            .load(new String(metadata, StandardCharsets.UTF_8));
        java.util.Map<?, ?> permissions = (java.util.Map<?, ?>) yaml.get("permissions");
        for (String suffix : new String[]{"use", "reload", "version"}) {
            java.util.Map<?, ?> renamed = (java.util.Map<?, ?>) permissions.get("tranforlanguage." + suffix);
            java.util.Map<?, ?> children = (java.util.Map<?, ?>) renamed.get("children");
            assertEquals(Boolean.TRUE, children.get("tranforcpp." + suffix));
        }
    }
}
