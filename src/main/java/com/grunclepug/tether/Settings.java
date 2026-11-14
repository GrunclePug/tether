package com.grunclepug.tether;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent client settings in config/tether.properties. */
final class Settings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tether.properties");

    static volatile boolean tts;

    private Settings() {
    }

    static void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
            tts = Boolean.parseBoolean(p.getProperty("tts", "false"));
        } catch (IOException e) {
            Tether.LOG.warn("Couldn't read {}", FILE, e);
        }
    }

    static void save() {
        Properties p = new Properties();
        p.setProperty("tts", Boolean.toString(tts));
        try (Writer w = Files.newBufferedWriter(FILE)) {
            p.store(w, "Tether client settings");
        } catch (IOException e) {
            Tether.LOG.warn("Couldn't write {}", FILE, e);
        }
    }
}
