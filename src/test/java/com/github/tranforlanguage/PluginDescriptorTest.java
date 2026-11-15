package com.github.tranforlanguage;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** The baseline CI target uses the real Spigot 1.8 description parser. */
public class PluginDescriptorTest {
    @Test public void spigot18ParserAcceptsDescriptor() throws Exception {
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
        assertEquals("com.github.tranforlanguage.TranForLanguage", description.getMain());
        assertTrue(description.getCommands().containsKey("tranforlanguage"));
        assertNull("No legacy command aliases", description.getCommands().get("tranforlanguage").get("aliases"));
        // Bukkit 1.8 lazily loads permissions through its global server logger.
        // Inspect the YAML mapping without requiring a running Bukkit server.
        java.util.Map<?, ?> yaml = (java.util.Map<?, ?>) new org.yaml.snakeyaml.Yaml()
            .load(new String(metadata, StandardCharsets.UTF_8));
        java.util.Map<?, ?> permissions = (java.util.Map<?, ?>) yaml.get("permissions");
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("tranforlanguage.use", "tranforlanguage.reload", "tranforlanguage.version")),
            permissions.keySet());
        assertFalse("No legacy names", new String(metadata, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT).contains("tranforcpp"));
    }
}
