package gr1mly4memes.lumance;

import org.bukkit.Bukkit;

import java.util.logging.Level;
import java.util.logging.Logger;

public class LumanceLogger extends Logger {
    public static final LumanceLogger LOGGER = new LumanceLogger();

    private LumanceLogger() {
        super("Lumance", null);
        setParent(Bukkit.getLogger());
        setLevel(Level.ALL);
    }

    public void severe(String msg, Exception exception) {
        this.log(Level.SEVERE, msg, exception);
    }

    public void warning(String msg, Exception exception) {
        this.log(Level.WARNING, msg, exception);
    }

}