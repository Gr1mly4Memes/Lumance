package gr1mly4memes.lumance.commands;

import gr1mly4memes.lumance.commands.subcommands.ReloadCommand;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.*;
import java.util.stream.Collectors;

public final class LumanceCommand extends Command {
    static final String BASE_PERM = "bukkit.command.lumance.";
    // subcommand label -> subcommand
    private static final Map<String, LumanceSubcommand> SUBCOMMANDS = Util.make(() -> {
        final Map<Set<String>, LumanceSubcommand> commands = new HashMap<>();
        commands.put(Set.of("reload"), new ReloadCommand());

        return commands.entrySet().stream()
                .flatMap(entry -> entry.getKey().stream().map(s -> Map.entry(s, entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    });
    private static final Set<String> COMPLETABLE_SUBCOMMANDS = SUBCOMMANDS.entrySet().stream().filter(entry -> entry.getValue().tabCompletes()).map(Map.Entry::getKey).collect(Collectors.toSet());

    public LumanceCommand(final String name) {
        super(name);
        this.description = "Lumance related commands";
        this.usageMessage = "/lumance [" + String.join(" | ", SUBCOMMANDS.keySet()) + "]";
        final List<String> permissions = new ArrayList<>();
        permissions.add("bukkit.command.lumance");
        permissions.addAll(SUBCOMMANDS.keySet().stream().map(s -> BASE_PERM + s).toList());
        this.setPermission(String.join(";", permissions));
        final PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        for (final String perm : permissions) {
            if (pluginManager.getPermission(perm) == null) {
                pluginManager.addPermission(new Permission(perm, PermissionDefault.OP));
            }
        }
    }

    private static boolean testPermission(final CommandSender sender, final String permission) {
        if (sender.hasPermission(BASE_PERM + permission) || sender.hasPermission("bukkit.command.lumance")) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "I'm sorry, but you do not have permission to perform this command. Please contact the server administrators if you believe that is in error.");
        return false;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location) throws IllegalArgumentException {
        if (args.length <= 1) {
            return tabCompleteSubcommands(args[0]);
        }

        final Pair<String, LumanceSubcommand> subCommand = resolveCommand(args[0]);
        if (subCommand != null) {
            return subCommand.second().tabComplete(sender, subCommand.first(), Arrays.copyOfRange(args, 1, args.length));
        }

        return Collections.emptyList();
    }

    private List<String> tabCompleteSubcommands(String arg) {
        List<String> matches = new ArrayList<>();
        String lowerArg = arg.toLowerCase(Locale.ENGLISH);
        for (String subcommand : COMPLETABLE_SUBCOMMANDS) {
            if (subcommand.toLowerCase(Locale.ENGLISH).startsWith(lowerArg)) {
                matches.add(subcommand);
            }
        }
        return matches;
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: " + this.usageMessage);
            return false;
        }
        final Pair<String, LumanceSubcommand> subCommand = resolveCommand(args[0]);

        if (subCommand == null) {
            sender.sendMessage(ChatColor.RED + "Usage: " + this.usageMessage);
            return false;
        }

        if (!testPermission(sender, subCommand.first())) {
            return true;
        }
        final String[] choppedArgs = Arrays.copyOfRange(args, 1, args.length);
        return subCommand.second().execute(sender, subCommand.first(), choppedArgs);
    }

    private static Pair<String, LumanceSubcommand> resolveCommand(String label) {
        label = label.toLowerCase(Locale.ENGLISH);
        LumanceSubcommand subCommand = SUBCOMMANDS.get(label);

        if (subCommand != null) {
            return Pair.of(label, subCommand);
        }

        return null;
    }
}
