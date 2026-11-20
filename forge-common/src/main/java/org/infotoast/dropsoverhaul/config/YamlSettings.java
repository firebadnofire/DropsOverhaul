package org.infotoast.dropsoverhaul.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.infotoast.dropsoverhaul.common.DropPolicy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Forge-side YAML persistence using the same schema and defaults as Bukkit. */
public final class YamlSettings {
    private final Path path;
    private Map<String, Object> data;

    public YamlSettings(Path path) throws IOException {
        this.path = path;
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (InputStream defaults = YamlSettings.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) throw new IOException("Bundled config.yml is missing");
                Files.copy(defaults, path);
            }
        }
        reload();
    }

    @SuppressWarnings("unchecked")
    public void reload() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(1_000_000);
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map)) throw new IOException("Configuration must be a YAML mapping: " + path);
            Map<String, Object> candidate = (Map<String, Object>) loaded;
            if (!Integer.valueOf(2).equals(candidate.get("Config-Version"))) {
                throw new IOException("Unsupported Config-Version; preserve and migrate " + path + " to schema 2");
            }
            Map<String, Object> settings = (Map<String, Object>) candidate.get("Settings");
            if (settings == null || !(settings.get("Drops-To-Remove") instanceof List)
                    || !(settings.get("Full-Inventory-Delete") instanceof Boolean)
                    || !(settings.get("Kill-Drops-Radius") instanceof Number)) {
                throw new IOException("Invalid Settings types in " + path);
            }
            for (Object block : (List<?>) settings.get("Drops-To-Remove")) {
                if (!(block instanceof String)) throw new IOException("Drops-To-Remove must contain strings: " + path);
            }
            data = candidate;
        } catch (RuntimeException error) {
            throw new IOException("Cannot parse " + path + ": " + error.getMessage(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settings() { return (Map<String, Object>) data.get("Settings"); }

    @SuppressWarnings("unchecked")
    public List<String> blocks() { return new ArrayList<String>((List<String>) settings().get("Drops-To-Remove")); }
    public boolean deleteOverflow() { return (Boolean) settings().get("Full-Inventory-Delete"); }
    public int radius() { return ((Number) settings().get("Kill-Drops-Radius")).intValue(); }
    public DropPolicy policy() { return new DropPolicy(blocks(), deleteOverflow()); }

    public void setBlocks(List<String> blocks) throws IOException {
        Object previous = settings().put("Drops-To-Remove", new ArrayList<String>(blocks));
        try { save(); } catch (IOException error) { settings().put("Drops-To-Remove", previous); throw error; }
    }

    public void configure(List<String> blocks, boolean deleteOverflow) throws IOException {
        List<String> previousBlocks = blocks();
        boolean previousOverflow = deleteOverflow();
        settings().put("Drops-To-Remove", new ArrayList<String>(blocks));
        settings().put("Full-Inventory-Delete", deleteOverflow);
        try { save(); } catch (IOException error) {
            settings().put("Drops-To-Remove", previousBlocks);
            settings().put("Full-Inventory-Delete", previousOverflow);
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    public String message(String key, String fallback) {
        Object current = data.get("Messages");
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map)) return fallback;
            current = ((Map<String, Object>) current).get(part);
        }
        return current instanceof String ? ((String) current).replace('&', '\u00a7') : fallback;
    }

    private void save() throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "dropsoverhaul-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new Yaml().dump(data, writer);
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
