package com.github.tranforcpp.listener;

import com.github.tranforcpp.compiler.CppCompiler;
import com.github.tranforcpp.utils.Messages;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 插件列表监听器
 * <p>
 * 监听服务器命令事件，处理与插件列表相关的命令。
 * 主要用于显示已安装的C++插件信息。
 * <p>
 * 监听的命令：
 * - /plugins: 显示所有插件列表，包括C++插件
 */
public class PluginListListener implements Listener {
    

    private final ScheduledExecutorService delayedExecutor;
    
    public PluginListListener() {
        this.delayedExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PluginList-DelayedExecutor");
            t.setDaemon(true);
            return t;
        });
    }
    
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase().trim();
        if (message.equals("/pl") || message.equals("/plugins")) {
            delayedExecutor.schedule(() -> {
                try {
                    org.bukkit.Bukkit.getScheduler().runTask(com.github.tranforcpp.TranforCPlusPlus.getInstance(), () -> showTranforCPlugins(event.getPlayer()));
                } catch (Exception ignored) {
                }
            }, 50, TimeUnit.MILLISECONDS);
        }
    }
    
    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand().toLowerCase().trim();
        if (command.equals("pl") || command.equals("plugins")) {
            delayedExecutor.schedule(() -> {
                try {
                    org.bukkit.Bukkit.getScheduler().runTask(com.github.tranforcpp.TranforCPlusPlus.getInstance(), () -> showTranforCPlugins(event.getSender()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 50, TimeUnit.MILLISECONDS);
        }
    }
    
    private void showTranforCPlugins(org.bukkit.command.CommandSender sender) {
        CppCompiler compiler = new CppCompiler();
        File cppDir = compiler.getCppDirectory();
        
        if (!cppDir.exists()) {
            return;
        }
        
        List<File> cppFiles = compiler.getPluginFiles();
        
        if (cppFiles.isEmpty()) {
            sender.sendMessage(Messages.format("<white>[<aqua>TranForLanguage<white>]<red>未发现 C++ 源文件；其他语言程序请查看 program.json 配置。"));
        } else {
            sender.sendMessage(Messages.format("<aqua>□ <white>C++ 源文件 (<white>" + cppFiles.size() + "<white>):"));
            sender.sendMessage(Messages.format("<aqua>TranForLanguage C++ 源文件 (<aqua>" + cppFiles.size() + "<aqua>):"));
            
            StringBuilder pluginNames = new StringBuilder();
            for (int i = 0; i < cppFiles.size(); i++) {
                File file = cppFiles.get(i);
                String fileName = file.getName().replace(".cpp", "");
                pluginNames.append("<dark_gray>- <green>").append(fileName);
                if (i < cppFiles.size() - 1) {
                    pluginNames.append("<gray>, ");
                }
            }
            sender.sendMessage(Messages.format(pluginNames.toString()));
        }
    }
    public void shutdown() {
        if (delayedExecutor != null && !delayedExecutor.isShutdown()) {
            delayedExecutor.shutdown();
            try {
                if (!delayedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    delayedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                delayedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}