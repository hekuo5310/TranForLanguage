package com.github.tranforcpp.command;

import com.github.tranforcpp.TranforCPlusPlus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import com.github.tranforcpp.utils.Messages;

/**
 * TranforC++主命令处理器
 * <p> 
 * 处理/tranforcpp命令的各种子命令。
 * 支持插件重载、版本查询等功能。
 * <p>
 * 支持的子命令：
 * - reload: 重新加载C++插件
 * - version/ver: 显示插件版本信息
 */
public class TranforCommand implements CommandExecutor {
    

    private static final String USAGE_MESSAGE = "<red>用法: /tranforcpp <reload|version|ver>";
    private static final String PERMISSION_DENIED = "<red>权限不足";
    private static final String RELOAD_START = "<yellow>正在重载...";
    private static final String RELOAD_COMPLETE = "<green>重载完成!";
    private static final String VERSION_PREFIX = "<white>[<aqua>TranforC++<white>] <green>您当前服务器的模块版本为: <green>";
    private static final String UNKNOWN_COMMAND = "<red>未知指令! 用法: /tranforcpp <reload|version|ver>";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Messages.format(USAGE_MESSAGE));
            return true;
        }
        
        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload": return handleReload(sender);
            case "version": case "ver": return handleVersion(sender);
            default: sender.sendMessage(Messages.format(UNKNOWN_COMMAND)); return true;
        }
    }
    
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("tranforcpp.reload")) {
            sender.sendMessage(Messages.format(PERMISSION_DENIED));
            return true;
        }
        sender.sendMessage(Messages.format(RELOAD_START));
        TranforCPlusPlus plugin = TranforCPlusPlus.getInstance();
        plugin.getProcessManager().reloadAsync().whenComplete((v, error) -> {
            if (plugin.isEnabled()) org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                sender.sendMessage(Messages.format(error == null ? RELOAD_COMPLETE : "<red>重载失败，请查看服务端日志: " + error.getMessage())));
        });
        return true;
    }
    
    private boolean handleVersion(CommandSender sender) {
        if (!sender.hasPermission("tranforcpp.version")) {
            sender.sendMessage(Messages.format(PERMISSION_DENIED));
            return true;
        }
        String version = TranforCPlusPlus.getInstance().getDescription().getVersion();
        sender.sendMessage(Messages.format(VERSION_PREFIX + version));
        return true;
    }
}