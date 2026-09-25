/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.lumance;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Built-in Lumance mod entrypoint. This ships inside Lumance itself and is always
 * loaded in fabric mode, alongside whatever the user drops into {@code mods/}.
 */
public class LumanceMod implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        enableWindowsAnsi();
        String version = FabricLoader.getInstance().getModContainer("lumance")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        System.out.println("Lumance fabric module active (v" + version + ")");
    }

    /**
     * Lets the Windows console render the ANSI colors our log pattern emits.
     * Modern terminals understand them natively; legacy conhost needs virtual
     * terminal processing switched on via JNA (a Mojang-shipped library, so no
     * extra dependency). Best effort: any failure just leaves plain output.
     */
    private static void enableWindowsAnsi() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        try {
            // Reflective so a missing JNA can never break startup (all JNA
            // types resolve lazily here, inside the try).
            Class<?> kernel32 = Class.forName("com.sun.jna.platform.win32.Kernel32");
            Class<?> handle = Class.forName("com.sun.jna.platform.win32.WinDef$HANDLE");
            Class<?> byRef = Class.forName("com.sun.jna.ptr.IntByReference");
            Object instance = kernel32.getField("INSTANCE").get(null);
            Object stdout = kernel32.getMethod("GetStdHandle", int.class).invoke(instance, -11);
            Object modeRef = byRef.getDeclaredConstructor().newInstance();
            boolean ok = (Boolean) kernel32.getMethod("GetConsoleMode", handle, byRef).invoke(instance, stdout, modeRef);
            if (!ok) {
                return;
            }
            int mode = (Integer) byRef.getMethod("getValue").invoke(modeRef);
            kernel32.getMethod("SetConsoleMode", handle, int.class).invoke(instance, stdout, mode | 0x0004);
        } catch (Throwable ignored) {
            // Piped output, old Windows, anything unexpected: stay monochrome.
        }
    }
}
