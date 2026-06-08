package persistence;

import core.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thread-safe flat-file implementation of {@link ProfileStore}.
 *
 * <p>Stores profiles as JSON files in {@code logs/profiles/<displayName>.json}.
 */
public class FileProfileStore implements ProfileStore {

    private static final String PROFILES_DIR = "logs/profiles";
    private final File directory;

    /**
     * Constructs a new FileProfileStore, creating the storage directory if needed.
     */
    public FileProfileStore() {
        this.directory = new File(PROFILES_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    @Override
    public synchronized void save(PlayerProfile profile) {
        if (profile == null || profile.displayName() == null) return;
        File file = new File(directory, profile.displayName().toLowerCase() + ".json");
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.print(ProfileSerializer.toJson(profile));
        } catch (IOException e) {
            Logger.logServerError("FileProfileStore failed to save profile for " +
                profile.displayName() + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized Optional<PlayerProfile> load(String displayName) {
        if (displayName == null) return Optional.empty();
        File file = new File(directory, displayName.toLowerCase() + ".json");
        if (!file.exists()) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            PlayerProfile profile = ProfileSerializer.fromJson(sb.toString());
            return Optional.of(profile);
        } catch (Exception e) {
            Logger.logServerError("FileProfileStore failed to load profile for " +
                displayName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized boolean exists(String displayName) {
        if (displayName == null) return false;
        File file = new File(directory, displayName.toLowerCase() + ".json");
        return file.exists();
    }

    @Override
    public synchronized List<String> listAllNames() {
        List<String> names = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String filename = file.getName();
                names.add(filename.substring(0, filename.length() - 5)); // strip .json
            }
        }
        return names;
    }
}
