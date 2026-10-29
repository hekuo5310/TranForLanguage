package com.github.tranforcpp.command;

import com.github.tranforcpp.TranforCPlusPlus;
import com.github.tranforcpp.core.ProgramManager;
import org.bukkit.command.*;
import java.util.*;

public class TranforTabCompleter implements TabCompleter {
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("list");
            if (sender.hasPermission("tranforcpp.version")) choices.addAll(Arrays.asList("version", "ver"));
            if (sender.hasPermission("tranforcpp.reload")) choices.addAll(Arrays.asList("reload", "start", "stop"));
        } else if (args.length == 2 && sender.hasPermission("tranforcpp.reload")
            && Arrays.asList("reload", "start", "stop").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (ProgramManager.Status status : TranforCPlusPlus.getInstance().getProcessManager().programs().statuses()) choices.add(status.id);
        }
        if (args.length == 0) return Collections.emptyList();
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        choices.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(input));
        return choices;
    }
}
