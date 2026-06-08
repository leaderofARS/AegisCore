package persistence;

import java.util.List;
import java.util.Optional;

/** Pluggable persistence interface for player profiles. */
public interface ProfileStore {
    /** Saves or updates a player profile. */
    void save(PlayerProfile profile);

    /** Loads a player profile by display name. */
    Optional<PlayerProfile> load(String displayName);

    /** Checks if a profile exists for the given display name. */
    boolean exists(String displayName);

    /** Lists display names of all stored player profiles. */
    List<String> listAllNames();
}
