package github.nighter.smartspawner.spawner.lifetime;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.data.storage.SpawnerStorage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

public final class OnlineTimeClock {
    private static final long CHECKPOINT_INTERVAL_TICKS = 1200L;
    private static final String SIDECAR_KEY = "online_time_millis";

    private final SmartSpawner plugin;
    private final SpawnerStorage storage;
    private final File sidecarFile;
    private final long sessionStartNanos;
    private final long persistedMillis;
    private Scheduler.Task checkpointTask;

    public OnlineTimeClock(SmartSpawner plugin) {
        this.plugin = plugin;
        this.storage = plugin.getSpawnerStorage();
        this.sidecarFile = new File(plugin.getDataFolder(),
                "lifetime_clock.yml");
        this.persistedMillis = loadOrImportPersistedMillis();
        this.sessionStartNanos = System.nanoTime();
        this.checkpointTask = Scheduler.runTaskTimerAsync(
                this::save,
                CHECKPOINT_INTERVAL_TICKS,
                CHECKPOINT_INTERVAL_TICKS);
    }

    public long now() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - sessionStartNanos);
        try {
            return Math.addExact(persistedMillis, Math.max(0L, elapsed));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public synchronized void save() {
        storage.saveOnlineTimeMillis(now());
    }

    public void shutdown() {
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        save();
    }

    private long loadOrImportPersistedMillis() {
        long backendValue = storage.loadOnlineTimeMillis();
        if (backendValue >= 0L) {
            return backendValue;
        }

        long sidecarValue = loadSidecar();
        storage.saveOnlineTimeMillis(sidecarValue);
        long verified = storage.loadOnlineTimeMillis();
        if (verified >= 0L && sidecarFile.exists()) {
            File migrated = new File(sidecarFile.getParentFile(),
                    sidecarFile.getName() + ".migrated");
            try {
                Files.move(sidecarFile.toPath(), migrated.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info(
                        "Migrated lifetime clock into the active storage backend.");
            } catch (IOException failure) {
                plugin.getLogger().warning(
                        "Lifetime clock was persisted, but the old sidecar could not be renamed: "
                                + failure.getMessage());
            }
        }
        return verified >= 0L ? verified : sidecarValue;
    }

    private long loadSidecar() {
        if (!sidecarFile.exists()) {
            return 0L;
        }
        YamlConfiguration data =
                YamlConfiguration.loadConfiguration(sidecarFile);
        return Math.max(0L, data.getLong(SIDECAR_KEY, 0L));
    }
}
