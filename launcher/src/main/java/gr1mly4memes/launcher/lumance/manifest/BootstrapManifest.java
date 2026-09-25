/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The lumance.properties manifest embedded in the bootstrap jar. It pins every artifact the
 * launcher needs: the vanilla bundle, the nested server jar, the library set and the overlay
 * patch that turns vanilla into Lumance.
 */
public final class BootstrapManifest {
    private final Properties props;

    private BootstrapManifest(Properties props) {
        this.props = props;
    }

    public static BootstrapManifest load() throws IOException {
        try (InputStream in = BootstrapManifest.class.getResourceAsStream("/lumance.properties")) {
            if (in == null) {
                throw new IOException("lumance.properties is missing - this jar was not assembled by the Lumance build");
            }
            Properties props = new Properties();
            props.load(in);
            return new BootstrapManifest(props);
        }
    }

    public String lumanceVersion() {
        return props.getProperty("lumance.version", "?");
    }

    public String minecraftVersion() {
        return props.getProperty("minecraft.version", "?");
    }

    public String mainClass() {
        return props.getProperty("main.class", "net.minecraft.server.Main");
    }

    public String bundleUrl() {
        return required("bundle.url");
    }

    public String bundleSha1() {
        return required("bundle.sha1");
    }

    public String serverEntry() {
        return required("server.entry");
    }

    public String serverSha1() {
        return required("server.sha1");
    }

    public String overlayDir() {
        return props.getProperty("overlay.dir", "lumance/overlay");
    }

    public String overlayPatchedSha1() {
        return required("overlay.patched.sha1");
    }

    public String fabricRepo() {
        return props.getProperty("fabric.repo", "https://maven.fabricmc.net/");
    }

    public String fabricLoaderVersion() {
        return props.getProperty("fabric.loader.version", "?");
    }

    public String modFile() {
        return props.getProperty("lumance.mod.file", "lumance/mod.jar");
    }

    public String modSha256() {
        return required("lumance.mod.sha256");
    }

    public String configFile() {
        return props.getProperty("lumance.config.file", "lumance/log4j2.xml");
    }

    public String configSha256() {
        return required("lumance.config.sha256");
    }

    public List<Lib> fabricLibs() {
        int count;
        try {
            count = Integer.parseInt(props.getProperty("fabric.count", "0"));
        } catch (NumberFormatException e) {
            count = 0;
        }
        List<Lib> libs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("fabric." + i + ".path");
            String sha256 = props.getProperty("fabric." + i + ".sha256", "");
            String size = props.getProperty("fabric." + i + ".size", "");
            if (path != null) {
                libs.add(new Lib(path, sha256, size));
            }
        }
        return libs;
    }

    /** Mods bundled with Lumance (e.g. Fabric API), loaded via fabric.addMods. */
    public List<Lib> bundledMods() {
        int count;
        try {
            count = Integer.parseInt(props.getProperty("bundledmod.count", "0"));
        } catch (NumberFormatException e) {
            count = 0;
        }
        List<Lib> libs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("bundledmod." + i + ".path");
            String sha256 = props.getProperty("bundledmod." + i + ".sha256", "");
            String size = props.getProperty("bundledmod." + i + ".size", "");
            if (path != null) {
                libs.add(new Lib(path, sha256, size));
            }
        }
        return libs;
    }

    public List<Lib> libs() {
        int count = Integer.parseInt(props.getProperty("lib.count", "0"));
        List<Lib> libs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("lib." + i + ".path");
            String sha256 = props.getProperty("lib." + i + ".sha256", "");
            String size = props.getProperty("lib." + i + ".size", "");
            if (path != null) {
                libs.add(new Lib(path, sha256, size));
            }
        }
        return libs;
    }

    private String required(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("lumance.properties is missing required key: " + key);
        }
        return value;
    }

    public record Lib(String path, String sha256, String size) {
    }
}
