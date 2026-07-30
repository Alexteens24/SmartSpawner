package github.nighter.smartspawner.spawner.data;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.data.storage.SpawnerStorage;
import github.nighter.smartspawner.spawner.data.storage.MutationQueue;
import github.nighter.smartspawner.spawner.data.storage.PendingMutation;
import github.nighter.smartspawner.spawner.data.storage.SpawnerSnapshot;
import github.nighter.smartspawner.spawner.data.legacy.LegacyInventoryCodec;
import github.nighter.smartspawner.spawner.data.storage.SpawnerInventoryCodec;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SpawnerFileHandler implements SpawnerStorage {
    private final SmartSpawner plugin;
    private final Logger logger;
    private File spawnerDataFile;
    private FileConfiguration spawnerData;

    private static final String DATA_VERSION_KEY = "data_version";
    private final int CURRENT_VERSION;

    private final MutationQueue mutations = new MutationQueue();
    private final AtomicBoolean isSaving = new AtomicBoolean(false);
    private final Object saveMonitor = new Object();
    private final Object persistenceLock = new Object();
    private volatile boolean shuttingDown;
    private volatile boolean legacyBackupAttempted;
    private Scheduler.Task saveTask = null;

    public SpawnerFileHandler(SmartSpawner plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.CURRENT_VERSION = plugin.getDATA_VERSION();
        setupSpawnerDataFile();
        startSaveTask();
    }

    @Override
    public boolean initialize() {
        // Initialization already happens in constructor
        // This method exists for interface compliance
        return spawnerDataFile != null && spawnerDataFile.exists();
    }

    private void setupSpawnerDataFile() {
        spawnerDataFile = new File(plugin.getDataFolder(), "spawners_data.yml");
        if (!spawnerDataFile.exists()) {
            plugin.saveResource("spawners_data.yml", false);
        }

        spawnerData = YamlConfiguration.loadConfiguration(spawnerDataFile);

        int version = spawnerData.getInt(DATA_VERSION_KEY, 1);
        if (version < CURRENT_VERSION) {
            logger.info("Data version " + version + " detected. Current version is " + CURRENT_VERSION + ".");
            logger.info("A migration will be attempted when the plugin fully loads.");
        }
        logInventoryFormatSummary();
    }

    private void logInventoryFormatSummary() {
        ConfigurationSection section =
                spawnerData.getConfigurationSection("spawners");
        if (section == null) {
            return;
        }
        int legacy = 0;
        int modern = 0;
        for (String id : section.getKeys(false)) {
            Object raw = spawnerData.get("spawners." + id + ".inventory");
            if (raw instanceof String encoded
                    && encoded.startsWith(SpawnerInventoryCodec.PREFIX)) {
                modern++;
            } else if (raw instanceof List<?> list && !list.isEmpty()) {
                legacy++;
            }
        }
        if (legacy > 0 || modern > 0) {
            logger.info("Spawner inventory formats: " + legacy
                    + " legacy, " + modern + " ssinv1.");
            if (legacy > 0 && modern > 0) {
                logger.warning("Mixed legacy/ssinv1 YAML data detected. "
                        + "The pre-ssinv1 snapshot can only preserve legacy rows "
                        + "that still exist.");
            } else if (legacy == 0 && modern > 0) {
                logger.warning("All detected YAML inventories are already ssinv1; "
                        + "a new backup cannot recover their former legacy form.");
            }
        }
    }

    private void startSaveTask() {
        // Hardcoded 5-minute interval (5 * 60 * 20 = 6000 ticks)
        long intervalTicks = 6000L;

        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }

        saveTask = Scheduler.runTaskTimerAsync(() -> {
            plugin.debug("Running scheduled save task");
            flushChanges();
        }, intervalTicks, intervalTicks);
    }

    @Override
    public void markSpawnerModified(String spawnerId) {
        SpawnerData spawner = spawnerId == null
                ? null : plugin.getSpawnerManager().getSpawnerById(spawnerId);
        if (spawner != null) {
            mutations.modified(SpawnerSnapshot.capture(spawner));
        }
    }

    @Override
    public void markSpawnerCreated(SpawnerSnapshot snapshot) {
        mutations.created(snapshot);
    }

    @Override
    public void queueWorldSnapshots(Collection<SpawnerSnapshot> snapshots) {
        mutations.modifiedAll(snapshots);
    }

    @Override
    public void markSpawnerDeleted(String spawnerId) {
        mutations.deleted(spawnerId);
    }

    @Override
    public void flushChanges() {
        if (mutations.isEmpty()) {
            plugin.debug("No changes to flush");
            return;
        }

        if (!isSaving.compareAndSet(false, true)) {
            plugin.debug("Flush operation already in progress");
            return;
        }

        plugin.debug("Flushing " + mutations.size() + " pending spawner mutations");
        Scheduler.runTaskAsync(this::runFlushWorker);
    }

    private void runFlushWorker() {
        boolean failed = false;
        try {
            while (!mutations.isEmpty()) {
                Map<String, PendingMutation> captured = mutations.capture();
                if (captured.isEmpty()) {
                    break;
                }
                if (!saveMutationBatch(captured)) {
                    failed = true;
                    break;
                }
                mutations.commit(captured);
            }
        } finally {
            isSaving.set(false);
            synchronized (saveMonitor) {
                saveMonitor.notifyAll();
            }
            // Close the race where a mutation arrived after the last empty check.
            if (!failed && !shuttingDown && !mutations.isEmpty()) {
                flushChanges();
            }
        }
    }

    private boolean saveMutationBatch(Map<String, PendingMutation> batch) {
        try {
            Map<String, String> encodedInventories = new HashMap<>();
            for (Map.Entry<String, PendingMutation> entry : batch.entrySet()) {
                if (entry.getValue().operation() == PendingMutation.Operation.UPSERT) {
                    encodedInventories.put(entry.getKey(),
                            SpawnerInventoryCodec.encodeToString(
                                    entry.getValue().snapshot().inventory()));
                }
            }

            synchronized (persistenceLock) {
                YamlConfiguration next = new YamlConfiguration();
                next.loadFromString(spawnerData.saveToString());
                next.set(DATA_VERSION_KEY, CURRENT_VERSION);

                for (Map.Entry<String, PendingMutation> entry : batch.entrySet()) {
                    if (entry.getValue().operation() == PendingMutation.Operation.DELETE) {
                        next.set("spawners." + entry.getKey(), null);
                        continue;
                    }
                    String spawnerId = entry.getKey();
                    SpawnerSnapshot spawner = entry.getValue().snapshot();
                    String path = "spawners." + spawnerId;

                    next.set(path + ".location", spawner.locationString());
                    next.set(path + ".entityType", spawner.entityType());
                    next.set(path + ".itemSpawnerMaterial",
                            spawner.itemSpawnerMaterial());

                    String settings = String.format(
                            "%d,%b,%d,%b,%d,%d,%d,%d,%d,%d,%d,%d,%b",
                            spawner.spawnerExp(), spawner.spawnerActive(),
                            spawner.spawnerRange(), spawner.spawnerStop(),
                            spawner.spawnDelay(), spawner.maxSpawnerLootSlots(),
                            spawner.maxStoredExp(), spawner.minMobs(), spawner.maxMobs(),
                            spawner.stackSize(), spawner.maxStackSize(),
                            spawner.lastSpawnTime(), spawner.atCapacity());

                    next.set(path + ".settings", settings);
                    next.set(path + ".lifetimeExpiresAt",
                            spawner.lifetimeExpiresAt() >= 0L
                                    ? spawner.lifetimeExpiresAt() : null);
                    next.set(path + ".expired", spawner.expired() ? true : null);
                    next.set(path + ".lastInteractedPlayer",
                            spawner.lastInteractedPlayer());
                    next.set(path + ".preferredSortItem",
                            spawner.preferredSortItem());
                    next.set(path + ".filteredItems",
                            spawner.filteredItems().isEmpty()
                                    ? null : String.join(",", spawner.filteredItems()));
                    String encoded = encodedInventories.get(spawnerId);
                    next.set(path + ".inventory",
                            encoded == null ? Collections.emptyList() : encoded);
                }

                saveAtomically(next);
                spawnerData = next;
            }
            return true;
        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE,
                    "Could not atomically save spawner mutation batch", e);
            return false;
        }
    }

    private void saveAtomically(YamlConfiguration config) throws IOException {
        File temp = new File(spawnerDataFile.getParentFile(),
                spawnerDataFile.getName() + ".tmp");
        Files.writeString(temp.toPath(), config.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp.toPath(), spawnerDataFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), spawnerDataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Map<String, SpawnerData> loadAllSpawnersRaw() {
        Map<String, SpawnerData> loadedSpawners = new HashMap<>();

        ConfigurationSection spawnersSection = spawnerData.getConfigurationSection("spawners");
        if (spawnersSection == null) return loadedSpawners;

        for (String spawnerId : spawnersSection.getKeys(false)) {
            try {
                // Use non-logging version and skip hopper restart during batch load
                SpawnerData spawner = loadSpawnerFromConfig(spawnerId, false, false);
                // Add to map even if null (world not loaded)
                loadedSpawners.put(spawnerId, spawner);
            } catch (Exception e) {
                plugin.debug("Error loading spawner " + spawnerId + ": " + e.getMessage());
                // Add null entry to indicate error
                loadedSpawners.put(spawnerId, null);
            }
        }

        return loadedSpawners;
    }

    @Override
    public SpawnerData loadSpecificSpawner(String spawnerId) {
        try {
            return loadSpawnerFromConfig(spawnerId, false);
        } catch (Exception e) {
            plugin.debug("Error loading spawner " + spawnerId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the raw location string for a spawner (used by WorldEventHandler)
     */
    @Override
    public String getRawLocationString(String spawnerId) {
        String path = "spawners." + spawnerId + ".location";
        return spawnerData.getString(path);
    }

    @Override
    public long loadOnlineTimeMillis() {
        synchronized (persistenceLock) {
            if (!spawnerData.contains(
                    "metadata.lifetime_online_time_millis")) {
                return -1L;
            }
            return Math.max(0L, spawnerData.getLong(
                    "metadata.lifetime_online_time_millis", 0L));
        }
    }

    @Override
    public void saveOnlineTimeMillis(long onlineTimeMillis) {
        synchronized (persistenceLock) {
            try {
                YamlConfiguration next = new YamlConfiguration();
                next.loadFromString(spawnerData.saveToString());
                next.set("metadata.lifetime_online_time_millis",
                        Math.max(0L, onlineTimeMillis));
                saveAtomically(next);
                spawnerData = next;
            } catch (Exception failure) {
                logger.log(java.util.logging.Level.WARNING,
                        "Failed to checkpoint lifetime clock in spawners_data.yml",
                        failure);
            }
        }
    }

    private SpawnerData loadSpawnerFromConfig(String spawnerId, boolean logErrors) {
        return loadSpawnerFromConfig(spawnerId, logErrors, true);
    }

    private SpawnerData loadSpawnerFromConfig(String spawnerId, boolean logErrors, boolean restartHopper) {
        String path = "spawners." + spawnerId;

        String locationString = spawnerData.getString(path + ".location");
        if (locationString == null) {
            if (logErrors) {
                logger.severe("Invalid location for spawner " + spawnerId);
            }
            return null;
        }

        String[] locParts = locationString.split(",");
        if (locParts.length != 4) {
            if (logErrors) {
                logger.severe("Invalid location format for spawner " + spawnerId);
            }
            return null;
        }

        org.bukkit.World world = Bukkit.getWorld(locParts[0]);
        if (world == null) {
            if (logErrors) {
                logger.severe("World not found for spawner " + spawnerId + ": " + locParts[0]);
            } else {
                plugin.debug("World not yet loaded for spawner " + spawnerId + ": " + locParts[0]);
            }
            return null;
        }

        Location location = new Location(world,
                Integer.parseInt(locParts[1]),
                Integer.parseInt(locParts[2]),
                Integer.parseInt(locParts[3]));

        String entityTypeString = spawnerData.getString(path + ".entityType");
        if (entityTypeString == null) {
            if (logErrors) {
                logger.severe("Missing entity type for spawner " + spawnerId);
            }
            return null;
        }

        boolean omniSpawner = "OMNI".equalsIgnoreCase(entityTypeString);
        EntityType entityType;
        if (omniSpawner) {
            entityType = EntityType.PIG;
        } else {
            try {
                entityType = EntityType.valueOf(entityTypeString);
            } catch (IllegalArgumentException e) {
                if (logErrors) {
                    logger.severe("Invalid entity type for spawner " + spawnerId + ": " + entityTypeString);
                }
                return null;
            }
        }

        // Check if this is an item spawner
        SpawnerData spawner;
        if (entityType == EntityType.ITEM) {
            String itemSpawnerMaterialString = spawnerData.getString(path + ".itemSpawnerMaterial");
            if (itemSpawnerMaterialString != null) {
                try {
                    Material itemMaterial = Material.valueOf(itemSpawnerMaterialString);
                    spawner = new SpawnerData(spawnerId, location, itemMaterial, plugin);
                } catch (IllegalArgumentException e) {
                    if (logErrors) {
                        logger.severe("Invalid item spawner material for spawner " + spawnerId + ": " + itemSpawnerMaterialString);
                    }
                    return null;
                }
            } else {
                // Fallback to regular entity spawner if no item material specified
                spawner = new SpawnerData(spawnerId, location, entityType, plugin);
            }
        } else {
            spawner = new SpawnerData(spawnerId, location, entityType, plugin);
        }
        if (omniSpawner) {
            spawner.setOmniSpawner(true);
        }

        String settingsString = spawnerData.getString(path + ".settings");
        if (settingsString != null) {
            String[] settings = settingsString.split(",");

            int version = spawnerData.getInt(DATA_VERSION_KEY, 1);

            try {
                if (version >= 3) {
                    if (settings.length >= 13) {
                        spawner.setSpawnerExpData(parseClampedLong(settings[0], 0L, Long.MAX_VALUE));
                        spawner.setSpawnerActive(Boolean.parseBoolean(settings[1]));
                        spawner.setSpawnerRange(Integer.parseInt(settings[2]));
                        spawner.getSpawnerStop().set(Boolean.parseBoolean(settings[3]));
                        spawner.setSpawnDelay(parseClampedLong(settings[4], 1L, Long.MAX_VALUE));
                        spawner.setMaxSpawnerLootSlots(Integer.parseInt(settings[5]));
                        spawner.setMaxStoredExp(parseClampedLong(settings[6], 0L, Long.MAX_VALUE));
                        spawner.setMinMobs(Integer.parseInt(settings[7]));
                        spawner.setMaxMobs(Integer.parseInt(settings[8]));
                        // Load maxStackSize BEFORE stackSize so the saved limit is in place
                        // when setStackSize validates the value, preventing data loss if the
                        // global config limit was lowered after this spawner was saved.
                        spawner.setMaxStackSize(parseClampedInt(settings[10], 1, Integer.MAX_VALUE));
                        spawner.setStackSize(parseClampedInt(settings[9], 1, Integer.MAX_VALUE), restartHopper);
                        spawner.setLastSpawnTime(Long.parseLong(settings[11]));
                        spawner.setIsAtCapacity(Boolean.parseBoolean(settings[12]));
                    }
                } else {
                    spawner.setSpawnerExpData(parseClampedLong(settings[0], 0L, Long.MAX_VALUE));
                    spawner.setSpawnerActive(Boolean.parseBoolean(settings[1]));
                    spawner.setSpawnerRange(Integer.parseInt(settings[2]));
                    spawner.getSpawnerStop().set(Boolean.parseBoolean(settings[3]));
                    spawner.setSpawnDelay(parseClampedLong(settings[4], 1L, Long.MAX_VALUE));
                    spawner.setMaxSpawnerLootSlots(Integer.parseInt(settings[5]));
                    spawner.setMaxStoredExp(parseClampedLong(settings[6], 0L, Long.MAX_VALUE));
                    spawner.setMinMobs(Integer.parseInt(settings[7]));
                    spawner.setMaxMobs(Integer.parseInt(settings[8]));
                    spawner.setStackSize(parseClampedInt(settings[9], 1, Integer.MAX_VALUE), restartHopper);
                    spawner.setLastSpawnTime(Long.parseLong(settings[10]));
                    spawner.setIsAtCapacity(false);
                }
            } catch (NumberFormatException e) {
                logger.severe("Invalid settings format for spawner " + spawnerId);
                logger.severe("Settings: " + settingsString);
                e.printStackTrace();
                return null;
            }
        }

        if (spawnerData.contains(path + ".lifetimeExpiresAt")) {
            spawner.setLifetimeExpiresAt(Math.max(0L,
                    spawnerData.getLong(path + ".lifetimeExpiresAt")));
            spawner.setExpired(spawnerData.getBoolean(path + ".expired", false));
            if (spawner.isExpired()) {
                spawner.setSpawnerActive(false);
                spawner.getSpawnerStop().set(true);
            }
        }

        String filteredItemsStr = spawnerData.getString(path + ".filteredItems");
        if (filteredItemsStr != null && !filteredItemsStr.isEmpty()) {
            String[] materialNames = filteredItemsStr.split(",");
            for (String materialName : materialNames) {
                try {
                    Material material = Material.valueOf(materialName.trim());
                    spawner.getFilteredItems().add(material);
                } catch (IllegalArgumentException e) {
                    logger.warning("Invalid material in filtered items for spawner " + spawnerId + ": " + materialName);
                }
            }
        }

        VirtualInventory virtualInv = new VirtualInventory(spawner.getMaxSpawnerLootSlots());
        Object rawInventory = spawnerData.get(path + ".inventory");
        boolean legacyInventory = false;
        try {
            Map<ItemStack, Long> items;
            if (rawInventory instanceof String encoded && !encoded.isEmpty()) {
                items = SpawnerInventoryCodec.decodeString(encoded);
            } else if (rawInventory instanceof List<?> rawList && !rawList.isEmpty()) {
                List<String> entries = new ArrayList<>(rawList.size());
                for (Object value : rawList) {
                    if (!(value instanceof String entry)) {
                        throw new IOException("Legacy inventory contains a non-string entry");
                    }
                    entries.add(entry);
                }
                items = LegacyInventoryCodec.deserialize(entries);
                legacyInventory = true;
            } else {
                items = Map.of();
            }
            for (Map.Entry<ItemStack, Long> entry : items.entrySet()) {
                virtualInv.addItem(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE,
                    "Refusing to load spawner " + spawnerId
                            + " because its inventory could not be decoded", e);
            return null;
        }

        spawner.setVirtualInventory(virtualInv);
        spawner.markSellValueDirty();

        // Load last interacted player
        String lastInteractedPlayer = spawnerData.getString(path + ".lastInteractedPlayer");
        spawner.setLastInteractedPlayer(lastInteractedPlayer);

        // Load preferred sort item
        String preferredSortItemStr = spawnerData.getString(path + ".preferredSortItem");
        if (preferredSortItemStr != null && !preferredSortItemStr.isEmpty()) {
            try {
                Material preferredSortItem = Material.valueOf(preferredSortItemStr);
                spawner.setPreferredSortItem(preferredSortItem);
                // Apply the sort preference to the virtual inventory
                virtualInv.sortItems(preferredSortItem);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid preferred sort item for spawner " + spawnerId + ": " + preferredSortItemStr);
            }
        }
        
        // Restore the physical spawner block state for item spawners
        if (spawner.isItemSpawner()) {
            Scheduler.runLocationTask(location, () -> {
                org.bukkit.block.Block block = location.getBlock();
                if (block.getType() == Material.SPAWNER) {
                    org.bukkit.block.BlockState state = block.getState(false);
                    if (state instanceof org.bukkit.block.CreatureSpawner cs) {
                        cs.setSpawnedType(EntityType.ITEM);
                        ItemStack spawnedItem = new ItemStack(spawner.getSpawnedItemMaterial(), 1);
                        cs.setSpawnedItem(spawnedItem);
                        cs.update(true, false);
                    }
                }
            });
        }

        if (legacyInventory) {
            ensureLegacyBackup();
            mutations.modified(SpawnerSnapshot.capture(spawner));
        }
        
        return spawner;
    }

    @Override
    public void queueSpawnerForSaving(String spawnerId) {
        markSpawnerModified(spawnerId);
    }

    @Override
    public void shutdown() {
        shuttingDown = true;
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }

        synchronized (saveMonitor) {
            while (isSaving.get()) {
                try {
                    saveMonitor.wait(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!mutations.isEmpty() && isSaving.compareAndSet(false, true)) {
            runFlushWorker();
        }
        if (!mutations.isEmpty()) {
            logger.severe("Shutdown completed with " + mutations.size()
                    + " unsaved spawner mutations: " + mutations.ids());
        }
    }

    private void ensureLegacyBackup() {
        if (legacyBackupAttempted) {
            return;
        }
        synchronized (persistenceLock) {
            if (legacyBackupAttempted) {
                return;
            }
            legacyBackupAttempted = true;
            File backup = new File(spawnerDataFile.getParentFile(),
                    "spawners_data.pre-ssinv1.yml");
            if (backup.exists()) {
                logger.info("Keeping existing pre-ssinv1 YAML backup at "
                        + backup.getAbsolutePath());
                return;
            }
            try {
                Files.copy(spawnerDataFile.toPath(), backup.toPath());
                logger.warning("Created pre-ssinv1 YAML backup at "
                        + backup.getAbsolutePath()
                        + ". This snapshot may be partial if some rows were rewritten earlier.");
            } catch (IOException failure) {
                logger.log(java.util.logging.Level.SEVERE,
                        "Could not create pre-ssinv1 YAML backup; legacy rows will not be rewritten",
                        failure);
                throw new IllegalStateException("Legacy inventory backup failed", failure);
            }
        }
    }

    private int parseClampedInt(String raw, int min, int max) {
        long value = Long.parseLong(raw);
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return (int) value;
    }

    private long parseClampedLong(String raw, long min, long max) {
        BigInteger value = new BigInteger(raw);
        BigInteger minValue = BigInteger.valueOf(min);
        BigInteger maxValue = BigInteger.valueOf(max);

        if (value.compareTo(minValue) < 0) {
            return min;
        }
        if (value.compareTo(maxValue) > 0) {
            return max;
        }
        return value.longValue();
    }
}
