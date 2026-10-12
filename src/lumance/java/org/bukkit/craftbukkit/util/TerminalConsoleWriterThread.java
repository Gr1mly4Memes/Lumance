package org.bukkit.craftbukkit.util;

import com.mojang.logging.LogQueues;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.craftbukkit.Main;
import org.jline.reader.LineReader;

public class TerminalConsoleWriterThread extends Thread {
    // Lumance start - how long to park when the console queue is empty. 1ms keeps console output
    // prompt to the eye while taking this thread from a full core down to a rounding error.
    private static final long IDLE_PARK_NANOS = 1_000_000L;
    // Lumance end

    private final LineReader reader;
    private final OutputStream output;

    public TerminalConsoleWriterThread(OutputStream output, LineReader reader) {
        super("TerminalConsoleWriter");
        this.output = output;
        this.reader = reader;

        this.setDaemon(true);
    }

    @Override
    public void run() {
        String message;

        // Using name from log4j config in vanilla jar
        while (!Thread.currentThread().isInterrupted()) {
            message = LogQueues.getNextLogEvent("TerminalConsole");

            // Lumance start - getNextLogEvent is a non-blocking poll, so upstream's bare
            // `continue` makes this a spin loop that burns a full core for the whole uptime of
            // the server (the queue is empty almost always). Park briefly instead.
            if (message == null) {
                java.util.concurrent.locks.LockSupport.parkNanos(IDLE_PARK_NANOS);
                continue;
            }
            // Lumance end

            try {
                if (Main.useJline) {
                    reader.printAbove(message);
                } else {
                    output.write(message.getBytes());
                    output.flush();
                }
            } catch (IOException ex) {
                Logger.getLogger(TerminalConsoleWriterThread.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}
