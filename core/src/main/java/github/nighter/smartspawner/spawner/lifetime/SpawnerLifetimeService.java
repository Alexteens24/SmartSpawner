package github.nighter.smartspawner.spawner.lifetime;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Material;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deadline-driven lifetime scheduler. It schedules work only when an expiry or
 * visible hologram boundary is due instead of dispatching one task per timed
 * spawner every second.
 */
public final class SpawnerLifetimeService {
    private static final long POLL_INTERVAL_TICKS = 20L;

    private record Deadline(String id, long dueAt, long generation) {
    }

    private final SmartSpawner plugin;
    private final OnlineTimeClock clock;
    private final Object queueLock = new Object();
    private final PriorityQueue<Deadline> deadlines =
            new PriorityQueue<>(Comparator.comparingLong(Deadline::dueAt));
    private final ConcurrentHashMap<String, Long> generations =
            new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private Scheduler.Task checkTask;

    public SpawnerLifetimeService(SmartSpawner plugin) {
        this.plugin = plugin;
        this.clock = new OnlineTimeClock(plugin);
        this.checkTask = Scheduler.runTaskTimer(
                this::dispatchDueDeadlines,
                POLL_INTERVAL_TICKS,
                POLL_INTERVAL_TICKS);
    }

    public long now() {
        return clock.now();
    }

    public long createExpiry(long durationMillis) {
        return Math.addExact(now(), durationMillis);
    }

    public void registerOrReschedule(SpawnerData spawner) {
        if (spawner == null || !spawner.isTimed() || spawner.isExpired()) {
            if (spawner != null) {
                unregister(spawner.getSpawnerId());
            }
            return;
        }
        long generation = generationSequence.incrementAndGet();
        generations.put(spawner.getSpawnerId(), generation);
        long dueAt = nextDeadline(spawner, now());
        synchronized (queueLock) {
            deadlines.add(new Deadline(spawner.getSpawnerId(), dueAt, generation));
        }
    }

    public void unregister(String spawnerId) {
        if (spawnerId != null) {
            generations.remove(spawnerId);
        }
    }

    public void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        synchronized (queueLock) {
            deadlines.clear();
        }
        generations.clear();
        clock.shutdown();
    }

    private void dispatchDueDeadlines() {
        long current = now();
        while (true) {
            Deadline deadline;
            synchronized (queueLock) {
                deadline = deadlines.peek();
                if (deadline == null || deadline.dueAt() > current) {
                    return;
                }
                deadlines.poll();
            }
            if (!Long.valueOf(deadline.generation()).equals(
                    generations.get(deadline.id()))) {
                continue;
            }
            SpawnerData spawner =
                    plugin.getSpawnerManager().getSpawnerById(deadline.id());
            if (spawner == null) {
                generations.remove(deadline.id(), deadline.generation());
                continue;
            }
            Scheduler.runLocationTask(spawner.getSpawnerLocation(),
                    () -> processDeadline(spawner, deadline.generation()));
        }
    }

    private void processDeadline(SpawnerData spawner, long generation) {
        if (!Long.valueOf(generation).equals(
                generations.get(spawner.getSpawnerId()))
                || plugin.getSpawnerManager().getSpawnerById(
                        spawner.getSpawnerId()) != spawner
                || spawner.getSpawnerLocation().getBlock().getType()
                        != Material.SPAWNER) {
            return;
        }

        if (spawner.getRemainingLifetimeMillis() <= 0L) {
            expire(spawner);
            return;
        }
        spawner.updateHologramData();
        registerOrReschedule(spawner);
    }

    private long nextDeadline(SpawnerData spawner, long current) {
        long expiry = spawner.getLifetimeExpiresAt();
        if (!plugin.getConfig().getBoolean("hologram.enabled", false)) {
            return expiry;
        }
        long remaining = Math.max(1L, expiry - current);
        long boundary = remaining >= 86_400_000L
                ? 3_600_000L
                : remaining >= 3_600_000L ? 60_000L : 1_000L;
        long untilBoundary = remaining % boundary;
        if (untilBoundary == 0L) {
            untilBoundary = boundary;
        }
        // Crossing a formatter tier (day -> hour or hour -> minute) changes
        // the text after one second even when the larger unit is exact.
        if (remaining >= 86_400_000L) {
            untilBoundary = Math.min(untilBoundary,
                    Math.max(1_000L, remaining - 86_400_000L + 1_000L));
        } else if (remaining >= 3_600_000L) {
            untilBoundary = Math.min(untilBoundary,
                    Math.max(1_000L, remaining - 3_600_000L + 1_000L));
        }
        return Math.min(expiry, saturatingAdd(current, untilBoundary));
    }

    private void expire(SpawnerData spawner) {
        if (!spawner.tryStartStorageOperation(
                SpawnerData.StorageOperation.REMOVE)) {
            registerRetry(spawner);
            return;
        }
        try {
            if (plugin.getSpawnerManager().getSpawnerById(
                    spawner.getSpawnerId()) != spawner
                    || spawner.getSpawnerLocation().getBlock().getType()
                            != Material.SPAWNER
                    || spawner.isExpired()
                    || !spawner.isTimed()
                    || spawner.getRemainingLifetimeMillis() > 0L) {
                return;
            }

            spawner.getDataLock().lock();
            try {
                spawner.setExpired(true);
                spawner.setSpawnerActive(false);
                spawner.getSpawnerStop().set(true);
                spawner.clearPreGeneratedLoot();
            } finally {
                spawner.getDataLock().unlock();
            }

            unregister(spawner.getSpawnerId());
            spawner.updateHologramData();
            plugin.getSpawnerManager().markSpawnerModified(spawner.getSpawnerId());
            if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
                plugin.getSpawnerGuiViewManager().forceStateChangeUpdate(spawner);
            }
        } finally {
            spawner.finishStorageOperation(
                    SpawnerData.StorageOperation.REMOVE);
        }
    }

    private void registerRetry(SpawnerData spawner) {
        long generation = generationSequence.incrementAndGet();
        generations.put(spawner.getSpawnerId(), generation);
        synchronized (queueLock) {
            deadlines.add(new Deadline(spawner.getSpawnerId(),
                    saturatingAdd(now(), 1000L), generation));
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
