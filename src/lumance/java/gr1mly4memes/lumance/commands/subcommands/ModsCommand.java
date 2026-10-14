package gr1mly4memes.lumance.commands.subcommands;

import gr1mly4memes.lumance.compat.LoadedMods;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * {@code /mods} - lists the Fabric mods the server is running.
 *
 * <p>The list is read through {@link LoadedMods}, which reflects on Fabric Loader rather than naming
 * it. That is not incidental: this code is compiled into the patched server jar, and Fabric Loader
 * is not on its compile classpath, so a direct reference would not build.
 */
public class ModsCommand extends Command {

    private static final int PER_PAGE = 8;

    public ModsCommand() {
        super("mods");
        this.description = "Lists the Fabric mods loaded on this server";
        this.usageMessage = "/mods [page]";
        this.setPermission("lumance.command.mods");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        Map<String, String> mods = LoadedMods.all();
        if (mods == null || mods.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No Fabric mods are loaded.");
            return true;
        }

        // Sorted by id: the loader hands them back in load order, which is not something an operator
        // can diff between two runs of the same mod set.
        List<String> ids = new ArrayList<>(mods.keySet());
        Collections.sort(ids);

        int pages = (ids.size() + PER_PAGE - 1) / PER_PAGE;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException notANumber) {
                sender.sendMessage(ChatColor.RED + "Page must be a number.");
                return true;
            }
        }
        if (page < 1) {
            page = 1;
        }
        if (page > pages) {
            sender.sendMessage(ChatColor.RED + "There " + (pages == 1 ? "is" : "are") + " only "
                    + pages + " page" + (pages == 1 ? "" : "s") + ".");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "Fabric mods " + ChatColor.GRAY + "(" + mods.size()
                + ") " + ChatColor.GOLD + "- page " + page + " of " + pages);

        int from = (page - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, ids.size());
        for (int i = from; i < to; i++) {
            String id = ids.get(i);
            sender.sendMessage(ChatColor.WHITE + " " + id + ChatColor.DARK_GRAY + " v"
                    + mods.get(id));
        }

        return true;
    }
}