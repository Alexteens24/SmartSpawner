package github.nighter.smartspawner.spawner.data.storage;

import github.nighter.smartspawner.spawner.properties.SpawnerData;

import java.util.Collection;
import java.util.Map;

/**
 * Interface defining storage operations for spawner data.
 * Implementations can use YAML files or MariaDB database.
 */
public interface SpawnerStorage {

    /**
     * Initialize the storage system.
     * Called during plugin startup.
     * @return true if initialization was successful
     */
    boolean initialize();

    /**
     * Shutdown the storage system gracefully.
     * Should flush all pending changes before returning.
     */
    void shutdown();

    /**
     * Load all spawners from storage.
     * Spawners whose worlds are not loaded will have null values.
     * @return Map of spawner IDs to SpawnerData (null values for unloadable spawners)
     */
    Map<String, SpawnerData> loadAllSpawnersRaw();

    /**
     * Load a specific spawner by ID.
     * @param spawnerId The spawner ID to load
     * @return The SpawnerData or null if not found or world not loaded
     */
    SpawnerData loadSpecificSpawner(String spawnerId);

    /**
     * Mark a spawner as modified for batch saving.
     * @param spawnerId The ID of the modified spawner
     */
    void markSpawnerModified(String spawnerId);

    /**
     * Queue a newly-created spawner. This is the only operation allowed to
     * replace an existing deletion tombstone.
     */
    void markSpawnerCreated(SpawnerSnapshot snapshot);

    /**
     * Queue snapshots captured before a world is removed from runtime indexes.
     */
    void queueWorldSnapshots(Collection<SpawnerSnapshot> snapshots);

    /**
     * Mark a spawner as deleted for batch removal.
     * @param spawnerId The ID of the deleted spawner
     */
    void markSpawnerDeleted(String spawnerId);

    /**
     * Queue a spawner for saving (alias for markSpawnerModified).
     * @param spawnerId The ID of the spawner to save
     */
    void queueSpawnerForSaving(String spawnerId);

    /**
     * Flush all pending changes to storage.
     * Called periodically and before shutdown.
     */
    void flushChanges();

    long loadOnlineTimeMillis();

    void saveOnlineTimeMillis(long onlineTimeMillis);

    /**
     * Get the raw location string for a spawner.
     * Used by WorldEventHandler for pending spawner loading.
     * @param spawnerId The spawner ID
     * @return Location string in format "world,x,y,z" or null if not found
     */
    String getRawLocationString(String spawnerId);
}
