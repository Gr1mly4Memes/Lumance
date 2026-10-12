package gr1mly4memes.lumance.commands.subcommands;

import gr1mly4memes.lumance.LumanceConfig;
import gr1mly4memes.lumance.commands.LumanceSubcommand;
import net.minecraft.server.MinecraftServer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.File;

public class ReloadCommand implements LumanceSubcommand {
    @Override
    public boolean execute(CommandSender sender, String subCommand, String[] args) {
        MinecraftServer server = MinecraftServer.getServer();
        LumanceConfig.init((File) server.options.valueOf("lumance-settings"));
        Command.broadcastCommandMessage(sender, ChatColor.GREEN + "Lumance config reload complete.");
        return false;
    }
}
