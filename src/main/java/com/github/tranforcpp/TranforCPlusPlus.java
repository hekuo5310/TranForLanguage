package com.github.tranforcpp;

import com.github.tranforcpp.command.TranforCommand;
import com.github.tranforcpp.command.TranforTabCompleter;
import com.github.tranforcpp.listener.PluginListListener;
import org.bukkit.plugin.java.JavaPlugin;

/** TranForLanguage entrypoint. The historical class name preserves plugin integrations. */
public class TranforCPlusPlus extends JavaPlugin {
    private static TranforCPlusPlus instance;
    private ProcessManager processManager;
    private PluginListListener pluginListListener;
    @Override public void onEnable() {
        instance = this;
        org.bukkit.command.PluginCommand command = getCommand("tranforlanguage");
        if (command == null) throw new IllegalStateException("Missing tranforlanguage command in plugin.yml");
        command.setExecutor(new TranforCommand());
        command.setTabCompleter(new TranforTabCompleter());
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
    public static TranforCPlusPlus getInstance() { return instance; }
    public ProcessManager getProcessManager() { return processManager; }
    public void reload() { if (processManager != null) processManager.restart(); }
}
