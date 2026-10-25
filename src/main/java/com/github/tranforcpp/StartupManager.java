package com.github.tranforcpp;

public class StartupManager {
    private final TranforCPlusPlus plugin;
    public StartupManager(TranforCPlusPlus plugin) { this.plugin = plugin; }
    public void startAsync() {
        ProcessManager manager = new ProcessManager(plugin);
        plugin.setProcessManager(manager);
        manager.start();
    }
    public void shutdown() {}
}
