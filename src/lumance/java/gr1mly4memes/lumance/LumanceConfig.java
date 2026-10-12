package gr1mly4memes.lumance;

import gr1mly4memes.lumance.commands.GlobalConfigManager;
import gr1mly4memes.lumance.commands.LumanceCommand;
import gr1mly4memes.lumance.config.ConfigVerify;
import gr1mly4memes.lumance.config.GlobalConfig;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class LumanceConfig {
    public static final List<String> CONFIG_HEADER = List.of(
            "This is the main configuration file for Lumance.",
            "",
            "Created by Gr1mly4Memes"
    );
    public static final int CURRENT_CONFIG_VERSION = 1;

    private static File configFile;
    public static YamlConfiguration config;
    private static int configVersion;
    public static boolean createWorldSections = true;

    public static void init(final File file) {
        LumanceConfig.configFile = file;
        config = new YamlConfiguration();
        config.options().setHeader(CONFIG_HEADER);
        config.options().copyDefaults(true);

        if (!file.exists()) {
            try {
                boolean is = file.createNewFile();
                if (!is) {
                    throw new IOException("Can't create file");
                }
            } catch (final Exception ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Failure to create lumance config", ex);
            }
        } else {
            try {
                config.load(file);
            } catch (final Exception ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Failure to load lumance config", ex);
                throw new RuntimeException(ex);
            }
        }

        LumanceConfig.configVersion = LumanceConfig.config.getInt("config-version", CURRENT_CONFIG_VERSION);
        LumanceConfig.config.set("config-version", CURRENT_CONFIG_VERSION);

        GlobalConfigManager.init();

        registerCommand("lumance", new LumanceCommand("lumance"));
    }

    public static void save() {
        try {
            config.save(LumanceConfig.configFile);
        } catch (final Exception ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Unable to save lumance config", ex);
        }
    }

    public static void registerCommand(String name, Command command) {
        MinecraftServer.getServer().server.getCommandMap().register(name, "lumance", command);
        MinecraftServer.getServer().server.syncCommands();
    }

    public static void unregisterCommand(String name) {
        name = name.toLowerCase(Locale.ENGLISH).trim();
        try {
            java.lang.reflect.Field knownCommandsField = MinecraftServer.getServer().server.getCommandMap().getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Command> knownCommands = (java.util.Map<String, Command>) knownCommandsField.get(MinecraftServer.getServer().server.getCommandMap());
            knownCommands.remove(name);
            knownCommands.remove("lumance:" + name);
            MinecraftServer.getServer().server.syncCommands();
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to unregister command: " + name, e);
        }
    }

    // Lumance start - JUST AN EXAMPLE
    @GlobalConfig(name = "disable-packet-limit", category = {"features"})
    public static boolean disablePacketLimit = false;
    // Lumance end
}
