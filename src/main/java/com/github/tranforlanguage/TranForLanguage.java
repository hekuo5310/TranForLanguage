package com.github.tranforlanguage;

import com.github.tranforlanguage.command.TranForLanguageCommand;
import com.github.tranforlanguage.command.TranForLanguageTabCompleter;
import com.github.tranforlanguage.listener.PluginListListener;
import org.bukkit.plugin.java.JavaPlugin;

/** TranForLanguage Spigot plugin entrypoint. */
public class TranForLanguage extends JavaPlugin {
    private static TranForLanguage instance;
    private ProcessManager processManager;
    private PluginListListener pluginListListener;
    @Override public void onEnable() {
        instance = this;
        org.bukkit.command.PluginCommand command = getCommand("tranforlanguage");
        if (command == null) throw new IllegalStateException("Missing tranforlanguage command in plugin.yml");
        command.setExecutor(new TranForLanguageCommand());
        command.setTabCompleter(new TranForLanguageTabCompleter());
        processManager = new ProcessManager(this);
        processManager.start();
        pluginListListener = new PluginListListener(this);
        getServer().getPluginManager().registerEvents(pluginListListener, this);
        getLogger().info("TranForLanguage " + getDescription().getVersion() + " enabled");
    }
    @Override public void onDisable() {
        if (pluginListListener != null) pluginListListener.shutdown();
        if (processManager != null) processManager.stop();
    }
    public static TranForLanguage getInstance() { return instance; }
    public ProcessManager getProcessManager() { return processManager; }
    public void reload() { if (processManager != null) processManager.restart(); }
}
