package com.github.tranforlanguage.command;

import com.github.tranforlanguage.TranForLanguage;
import com.github.tranforlanguage.core.ProgramManager;
import org.bukkit.command.*;
import java.util.*;

public class TranForLanguageTabCompleter implements TabCompleter {
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("list");
            if (sender.hasPermission("tranforlanguage.version")) choices.addAll(Arrays.asList("version", "ver"));
            if (sender.hasPermission("tranforlanguage.reload")) choices.addAll(Arrays.asList("reload", "start", "stop"));
        } else if (args.length == 2 && sender.hasPermission("tranforlanguage.reload")
            && Arrays.asList("reload", "start", "stop").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (ProgramManager.Status status : TranForLanguage.getInstance().getProcessManager().programs().statuses()) choices.add(status.id);
        }
        if (args.length == 0) return Collections.emptyList();
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        choices.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(input));
        return choices;
    }
}
