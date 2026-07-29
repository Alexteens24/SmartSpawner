package github.nighter.smartspawner.spawner.lifetime;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class OnlineTimeClock {
    private static final long CHECKPOINT_INTERVAL_TICKS = 1200L;
    private static final String CLOCK_KEY = "online_time_millis";

    private final SmartSpawner plugin;
    private final File clockFile;
    private final long sessionStartNanos;
    private final long persistedMillis;
    private Scheduler.Task checkpointTask;

    public OnlineTimeClock(SmartSpawner plugin) {
        this.plugin = plugin;
        this.clockFile = new File(plugin.getDataFolder(), "lifetime_clock.yml");
        this.persistedMillis = loadPersistedMillis();
        this.sessionStartNanos = System.nanoTime();
        this.checkpointTask = Scheduler.runTaskTimerAsync(
                this::save,
                CHECKPOINT_INTERVAL_TICKS,
                CHECKPOINT_INTERVAL_TICKS
        );
    }

    public long now() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sessionStartNanos);
        try {
            return Math.addExact(persistedMillis, Math.max(0L, elapsed));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public synchronized void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set(CLOCK_KEY, now());
        try {
            data.save(clockFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save the spawner online-time clock: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        save();
    }

    private long loadPersistedMillis() {
        if (!clockFile.exists()) {
            return 0L;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(clockFile);
        return Math.max(0L, data.getLong(CLOCK_KEY, 0L));
    }
}
