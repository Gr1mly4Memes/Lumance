package gr1mly4memes.lumance.commands;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public interface LumanceSubcommand {
    boolean execute(CommandSender sender, String subCommand, String[] args);

    default List<String> tabComplete(final CommandSender sender, final String subCommand, final String[] args) {
        return Collections.emptyList();
    }

    default boolean tabCompletes() {
        return true;
    }
}
