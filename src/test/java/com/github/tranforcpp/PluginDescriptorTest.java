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
        assertEquals("TranforCPlusPlus", description.getName());
        String projectVersion = System.getProperty("project.version");
        assertNotNull("Surefire must supply project.version", projectVersion);
        assertEquals(projectVersion, description.getVersion());
        assertEquals("com.github.tranforcpp.TranforCPlusPlus", description.getMain());
        assertTrue(description.getCommands().containsKey("tranforcpp"));
    }
}
