package github.nighter.smartspawner.spawner.lifetime;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class SpawnerLifetimeService {
    private static final long CHECK_INTERVAL_TICKS = 20L;

    private final SmartSpawner plugin;
    private final OnlineTimeClock clock;
    private Scheduler.Task checkTask;

    public SpawnerLifetimeService(SmartSpawner plugin) {
        this.plugin = plugin;
        this.clock = new OnlineTimeClock(plugin);
        this.checkTask = Scheduler.runTaskTimer(this::checkLoadedSpawners, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    public long now() {
        return clock.now();
    }

    public long createExpiry(long durationMillis) {
        return Math.addExact(now(), durationMillis);
    }

    public void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        clock.shutdown();
    }

    private void checkLoadedSpawners() {
        long now = now();
        for (SpawnerData spawner : plugin.getSpawnerManager().getAllSpawners()) {
            if (!spawner.isTimed()) {
                continue;
            }
            if (!spawner.isExpired() && spawner.getLifetimeExpiresAt() <= now) {
                Scheduler.runLocationTask(spawner.getSpawnerLocation(), () -> expire(spawner));
            } else {
                Scheduler.runLocationTask(spawner.getSpawnerLocation(), spawner::updateHologramData);
            }
        }
    }

    private void expire(SpawnerData spawner) {
        spawner.getDataLock().lock();
        try {
            if (spawner.isExpired() || !spawner.isTimed() || spawner.getRemainingLifetimeMillis() > 0L) {
                return;
            }

            spawner.setExpired(true);
            spawner.setSpawnerActive(false);
            spawner.getSpawnerStop().set(true);
            spawner.clearPreGeneratedLoot();

            List<ItemStack> emptySpawners = new ArrayList<>();
            int remaining = spawner.getStackSize();
            int maxStack = plugin.getSpawnerItemFactory().createEmptySpawnerItem().getMaxStackSize();
            while (remaining > 0) {
                int amount = Math.min(maxStack, remaining);
                emptySpawners.add(plugin.getSpawnerItemFactory().createEmptySpawnerItem(amount));
                remaining -= amount;
            }
            spawner.addItemsAndUpdateSellValue(emptySpawners);
            spawner.updateHologramData();
            plugin.getSpawnerManager().markSpawnerModified(spawner.getSpawnerId());

            if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
                plugin.getSpawnerGuiViewManager().forceStateChangeUpdate(spawner);
            }
        } finally {
            spawner.getDataLock().unlock();
        }
    }
}
