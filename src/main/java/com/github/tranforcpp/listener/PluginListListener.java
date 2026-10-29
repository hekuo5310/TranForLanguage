package com.github.tranforcpp.listener;

import com.github.tranforcpp.TranforCPlusPlus;
import com.github.tranforcpp.core.ProgramManager;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import java.util.Locale;

/** Adds the process status snapshot to /plugins on the following server tick. */
public final class PluginListListener implements Listener {
    private final TranforCPlusPlus plugin;
    private boolean stopped;
    public PluginListListener(TranforCPlusPlus plugin) { this.plugin = plugin; }
    private void schedule(String command, CommandSender sender) {
        if (!command.equals("pl") && !command.equals("plugins")) return;
        if (stopped || !plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (stopped || !plugin.isEnabled()) return;
            java.util.List<ProgramManager.Status> statuses = plugin.getProcessManager().programs().statuses();
            sender.sendMessage("TranForLanguage programs (" + statuses.size() + "):");
            for (ProgramManager.Status status : statuses) sender.sendMessage("  " + status);
        });
    }
    @EventHandler public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        schedule(event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT), event.getPlayer());
    }
    @EventHandler public void onServerCommand(ServerCommandEvent event) {
        schedule(event.getCommand().trim().toLowerCase(Locale.ROOT), event.getSender());
    }
    public void shutdown() { stopped = true; }
}
