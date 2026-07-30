package github.nighter.smartspawner.spawner.utils;

import github.nighter.smartspawner.SmartSpawner;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Utility class for distinguishing between vanilla and custom spawners
 */
public class SpawnerTypeChecker {
    private static NamespacedKey VANILLA_SPAWNER_KEY;
    private static NamespacedKey ITEM_SPAWNER_KEY;
    private static NamespacedKey LIFETIME_DURATION_KEY;
    private static NamespacedKey EMPTY_SPAWNER_KEY;
    private static NamespacedKey OMNI_SPAWNER_KEY;

    /**
     * Initializes the spawner type checker with plugin instance
     * @param plugin The SmartSpawner plugin instance
     */
    public static void init(SmartSpawner plugin) {
        VANILLA_SPAWNER_KEY = new NamespacedKey(plugin, "vanilla_spawner");
        ITEM_SPAWNER_KEY = new NamespacedKey(plugin, "item_spawner_material");
        LIFETIME_DURATION_KEY = new NamespacedKey(plugin, "lifetime_duration_ms");
        EMPTY_SPAWNER_KEY = new NamespacedKey(plugin, "empty_spawner");
        OMNI_SPAWNER_KEY = new NamespacedKey(plugin, "omni_spawner");
    }

    /**
     * Checks if an item is a vanilla spawner
     * @param item The ItemStack to check
     * @return true if it's a vanilla spawner, false otherwise
     */
    public static boolean isVanillaSpawner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                VANILLA_SPAWNER_KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * Checks if an item is an item spawner
     * @param item The ItemStack to check
     * @return true if it's an item spawner, false otherwise
     */
    public static boolean isItemSpawner(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                ITEM_SPAWNER_KEY, PersistentDataType.STRING);
    }

    /**
     * Gets the item material for an item spawner
     * @param item The ItemStack to check
     * @return The Material being spawned, or null if not an item spawner
     */
    public static Material getItemSpawnerMaterial(ItemStack item) {
        if (!isItemSpawner(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        String materialName = meta.getPersistentDataContainer().get(
                ITEM_SPAWNER_KEY, PersistentDataType.STRING);

        if (materialName == null) {
            return null;
        }

        try {
            return Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isEmptySpawner(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                EMPTY_SPAWNER_KEY, PersistentDataType.BOOLEAN);
    }

    public static boolean isOmniSpawner(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER
                || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                OMNI_SPAWNER_KEY, PersistentDataType.BOOLEAN);
    }

    public static long getLifetimeDurationMillis(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return -1L;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1L;
        }
        Long duration = meta.getPersistentDataContainer().get(
                LIFETIME_DURATION_KEY, PersistentDataType.LONG);
        return duration == null || duration <= 0L ? -1L : duration;
    }
}
