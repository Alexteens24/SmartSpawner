package github.nighter.smartspawner.spawner.data.storage;

import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable, backend-neutral copy of all persisted spawner state.
 *
 * <p>Storage workers must never resolve a mutable {@link SpawnerData} after a
 * mutation has been queued. Keeping the snapshot in the mutation also lets a
 * world unload its runtime objects without losing a pending save.</p>
 */
public record SpawnerSnapshot(
        String id,
        String worldName,
        int x,
        int y,
        int z,
        String entityType,
        String itemSpawnerMaterial,
        long spawnerExp,
        boolean spawnerActive,
        int spawnerRange,
        boolean spawnerStop,
        long spawnDelay,
        int maxSpawnerLootSlots,
        long maxStoredExp,
        int minMobs,
        int maxMobs,
        int stackSize,
        int maxStackSize,
        long lastSpawnTime,
        boolean atCapacity,
        String lastInteractedPlayer,
        String preferredSortItem,
        Set<String> filteredItems,
        Map<ItemSignature, Long> inventory,
        long lifetimeExpiresAt,
        boolean expired
) {
    public static SpawnerSnapshot capture(SpawnerData spawner) {
        Location location = spawner.getSpawnerLocation();
        Map<ItemSignature, Long> inventoryCopy = new LinkedHashMap<>();

        spawner.getDataLock().lock();
        spawner.getInventoryLock().lock();
        try {
            for (Map.Entry<ItemSignature, Long> entry
                    : spawner.getVirtualInventory().getConsolidatedItems().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                    inventoryCopy.put(
                            new ItemSignature(entry.getKey().getTemplate()),
                            entry.getValue());
                }
            }

            Set<String> filtered = spawner.getFilteredItems().stream()
                    .map(Material::name)
                    .collect(Collectors.toUnmodifiableSet());

            return new SpawnerSnapshot(
                    spawner.getSpawnerId(),
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ(),
                    spawner.isOmniSpawner()
                            ? "OMNI" : spawner.getEntityType().name(),
                    spawner.isItemSpawner() ? spawner.getSpawnedItemMaterial().name() : null,
                    Math.max(0L, spawner.getSpawnerExp()),
                    spawner.getSpawnerActive(),
                    spawner.getSpawnerRange(),
                    spawner.getSpawnerStop().get(),
                    spawner.getSpawnDelay(),
                    spawner.getMaxSpawnerLootSlots(),
                    spawner.getMaxStoredExp(),
                    spawner.getMinMobs(),
                    spawner.getMaxMobs(),
                    spawner.getStackSize(),
                    spawner.getMaxStackSize(),
                    spawner.getLastSpawnTime(),
                    spawner.getIsAtCapacity(),
                    spawner.getLastInteractedPlayer(),
                    spawner.getPreferredSortItem() == null
                            ? null : spawner.getPreferredSortItem().name(),
                    filtered,
                    Collections.unmodifiableMap(inventoryCopy),
                    spawner.isTimed() ? spawner.getLifetimeExpiresAt() : -1L,
                    spawner.isExpired());
        } finally {
            spawner.getInventoryLock().unlock();
            spawner.getDataLock().unlock();
        }
    }

    public String locationString() {
        return worldName + "," + x + "," + y + "," + z;
    }
}
