package com.github.tranforcpp.command;

import com.github.tranforcpp.TranforCPlusPlus;
import com.github.tranforcpp.core.ProgramManager;
import org.bukkit.command.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class TranforCommand implements CommandExecutor {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("/tranforlanguage <list|version|reload [id]|start id|stop id>"); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        TranforCPlusPlus plugin = TranforCPlusPlus.getInstance();
        ProgramManager programs = plugin.getProcessManager().programs();
        if (action.equals("version") || action.equals("ver")) {
            if (!sender.hasPermission("tranforcpp.version")) { sender.sendMessage("权限不足"); return true; }
            sender.sendMessage("TranForLanguage " + plugin.getDescription().getVersion()); return true;
        }
        if (action.equals("list")) {
            for (ProgramManager.Status status : programs.statuses()) sender.sendMessage(status.toString());
            sender.sendMessage("程序数量: " + programs.statuses().size()); return true;
        }
        if (!Arrays.asList("reload", "start", "stop").contains(action)) { sender.sendMessage("未知子命令"); return true; }
        if (!sender.hasPermission("tranforcpp.reload")) { sender.sendMessage("权限不足"); return true; }
        String id = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
        if (id == null && !action.equals("reload")) { sender.sendMessage("请指定 list 中的程序 ID"); return true; }
        CompletableFuture<Void> operation = action.equals("stop") ? programs.stop(id)
            : id == null ? programs.reload() : programs.reload(id);
        sender.sendMessage("正在处理程序...");
        operation.whenComplete((v, error) -> {
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin,
                () -> sender.sendMessage(error == null ? "操作完成" : "操作失败: " + error.getMessage()));
        });
        return true;
    }
}
