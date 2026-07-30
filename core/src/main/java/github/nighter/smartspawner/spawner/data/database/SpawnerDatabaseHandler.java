package github.nighter.smartspawner.spawner.data.database;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.commands.list.gui.CrossServerSpawnerData;
import github.nighter.smartspawner.spawner.data.legacy.LegacyInventoryCodec;
import github.nighter.smartspawner.spawner.data.storage.SpawnerInventoryCodec;
import github.nighter.smartspawner.spawner.data.storage.SpawnerStorage;
import github.nighter.smartspawner.spawner.data.storage.StorageMode;
import github.nighter.smartspawner.spawner.data.storage.MutationQueue;
import github.nighter.smartspawner.spawner.data.storage.PendingMutation;
import github.nighter.smartspawner.spawner.data.storage.SpawnerSnapshot;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Database-backed storage handler for spawner data.
 * Implements SpawnerStorage interface with MariaDB operations.
 */
public class SpawnerDatabaseHandler implements SpawnerStorage {
    private final SmartSpawner plugin;
    private final Logger logger;
    private final DatabaseManager databaseManager;
    private final String serverName;

    private final MutationQueue mutations = new MutationQueue();
    private final AtomicBoolean isSaving = new AtomicBoolean(false);
    private final Object saveMonitor = new Object();
    private volatile boolean shuttingDown;
    private Scheduler.Task saveTask = null;

    // Cache for raw location strings (used by WorldEventHandler)
    private final Map<String, String> locationCache = new ConcurrentHashMap<>();

    // SQL Statements
    private static final String SELECT_ALL_SQL = """
            SELECT spawner_id, world_name, loc_x, loc_y, loc_z, entity_type, item_spawner_material,
                   spawner_exp, spawner_active, spawner_range, spawner_stop, spawn_delay,
                   max_spawner_loot_slots, max_stored_exp, min_mobs, max_mobs, stack_size,
                   max_stack_size, last_spawn_time, is_at_capacity, last_interacted_player,
                   preferred_sort_item, filtered_items, inventory_data, total_items,
                   lifetime_expires_at, lifetime_expired
            FROM smart_spawners WHERE server_name = ?
            """;

    private static final String SELECT_ONE_SQL = """
            SELECT spawner_id, world_name, loc_x, loc_y, loc_z, entity_type, item_spawner_material,
                   spawner_exp, spawner_active, spawner_range, spawner_stop, spawn_delay,
                   max_spawner_loot_slots, max_stored_exp, min_mobs, max_mobs, stack_size,
                   max_stack_size, last_spawn_time, is_at_capacity, last_interacted_player,
                   preferred_sort_item, filtered_items, inventory_data, total_items,
                   lifetime_expires_at, lifetime_expired
            FROM smart_spawners WHERE server_name = ? AND spawner_id = ?
            """;

    private static final String SELECT_LOCATION_SQL = """
            SELECT world_name, loc_x, loc_y, loc_z FROM smart_spawners
            WHERE server_name = ? AND spawner_id = ?
            """;

    // MySQL/MariaDB upsert syntax
    private static final String UPSERT_SQL_MYSQL = """
            INSERT INTO smart_spawners (
                spawner_id, server_name, world_name, loc_x, loc_y, loc_z,
                entity_type, item_spawner_material, spawner_exp, spawner_active,
                spawner_range, spawner_stop, spawn_delay, max_spawner_loot_slots,
                max_stored_exp, min_mobs, max_mobs, stack_size, max_stack_size,
                last_spawn_time, is_at_capacity, last_interacted_player,
                preferred_sort_item, filtered_items, inventory_data, total_items,
                lifetime_expires_at, lifetime_expired
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                world_name = VALUES(world_name),
                loc_x = VALUES(loc_x),
                loc_y = VALUES(loc_y),
                loc_z = VALUES(loc_z),
                entity_type = VALUES(entity_type),
                item_spawner_material = VALUES(item_spawner_material),
                spawner_exp = VALUES(spawner_exp),
                spawner_active = VALUES(spawner_active),
                spawner_range = VALUES(spawner_range),
                spawner_stop = VALUES(spawner_stop),
                spawn_delay = VALUES(spawn_delay),
                max_spawner_loot_slots = VALUES(max_spawner_loot_slots),
                max_stored_exp = VALUES(max_stored_exp),
                min_mobs = VALUES(min_mobs),
                max_mobs = VALUES(max_mobs),
                stack_size = VALUES(stack_size),
                max_stack_size = VALUES(max_stack_size),
                last_spawn_time = VALUES(last_spawn_time),
                is_at_capacity = VALUES(is_at_capacity),
                last_interacted_player = VALUES(last_interacted_player),
                preferred_sort_item = VALUES(preferred_sort_item),
                filtered_items = VALUES(filtered_items),
                inventory_data = VALUES(inventory_data),
                total_items = VALUES(total_items),
                lifetime_expires_at = VALUES(lifetime_expires_at),
                lifetime_expired = VALUES(lifetime_expired)
            """;

    // SQLite upsert syntax (ON CONFLICT)
    private static final String UPSERT_SQL_SQLITE = """
            INSERT INTO smart_spawners (
                spawner_id, server_name, world_name, loc_x, loc_y, loc_z,
                entity_type, item_spawner_material, spawner_exp, spawner_active,
                spawner_range, spawner_stop, spawn_delay, max_spawner_loot_slots,
                max_stored_exp, min_mobs, max_mobs, stack_size, max_stack_size,
                last_spawn_time, is_at_capacity, last_interacted_player,
                preferred_sort_item, filtered_items, inventory_data, total_items,
                lifetime_expires_at, lifetime_expired
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(server_name, spawner_id) DO UPDATE SET
                world_name = excluded.world_name,
                loc_x = excluded.loc_x,
                loc_y = excluded.loc_y,
                loc_z = excluded.loc_z,
                entity_type = excluded.entity_type,
                item_spawner_material = excluded.item_spawner_material,
                spawner_exp = excluded.spawner_exp,
                spawner_active = excluded.spawner_active,
                spawner_range = excluded.spawner_range,
                spawner_stop = excluded.spawner_stop,
                spawn_delay = excluded.spawn_delay,
                max_spawner_loot_slots = excluded.max_spawner_loot_slots,
                max_stored_exp = excluded.max_stored_exp,
                min_mobs = excluded.min_mobs,
                max_mobs = excluded.max_mobs,
                stack_size = excluded.stack_size,
                max_stack_size = excluded.max_stack_size,
                last_spawn_time = excluded.last_spawn_time,
                is_at_capacity = excluded.is_at_capacity,
                last_interacted_player = excluded.last_interacted_player,
                preferred_sort_item = excluded.preferred_sort_item,
                filtered_items = excluded.filtered_items,
                inventory_data = excluded.inventory_data,
                total_items = excluded.total_items,
                lifetime_expires_at = excluded.lifetime_expires_at,
                lifetime_expired = excluded.lifetime_expired
            """;

    private static final String DELETE_SQL = """
            DELETE FROM smart_spawners WHERE server_name = ? AND spawner_id = ?
            """;

    public SpawnerDatabaseHandler(SmartSpawner plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.databaseManager = databaseManager;
        this.serverName = databaseManager.getServerName();
    }

    @Override
    public boolean initialize() {
        if (!databaseManager.isActive()) {
            logger.severe("Database manager is not active, cannot initialize SpawnerDatabaseHandler");
            return false;
        }

        // Start the periodic save task
        startSaveTask();
        return true;
    }

    private void startSaveTask() {
        // Hardcoded 5-minute interval (5 * 60 * 20 = 6000 ticks)
        long intervalTicks = 6000L;

        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }

        saveTask = Scheduler.runTaskTimerAsync(() -> {
            plugin.debug("Running scheduled database save task");
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
        if (spawnerId != null) {
            mutations.deleted(spawnerId);
            locationCache.remove(spawnerId);
        }
    }

    @Override
    public void queueSpawnerForSaving(String spawnerId) {
        markSpawnerModified(spawnerId);
    }

    @Override
    public void flushChanges() {
        if (mutations.isEmpty()) {
            plugin.debug("No database changes to flush");
            return;
        }

        if (!isSaving.compareAndSet(false, true)) {
            plugin.debug("Database flush operation already in progress");
            return;
        }

        plugin.debug("Flushing " + mutations.size()
                + " pending spawner mutations to database");
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
                if (!commitMutationBatch(captured)) {
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
            if (!failed && !shuttingDown && !mutations.isEmpty()) {
                flushChanges();
            }
        }
    }

    private boolean commitMutationBatch(Map<String, PendingMutation> batch) {
        String upsertSql = databaseManager.getStorageMode() == StorageMode.SQLITE
                ? UPSERT_SQL_SQLITE
                : UPSERT_SQL_MYSQL;

        Map<String, String> encoded = new HashMap<>();
        try {
            for (Map.Entry<String, PendingMutation> entry : batch.entrySet()) {
                if (entry.getValue().operation() == PendingMutation.Operation.UPSERT) {
                    String payload = SpawnerInventoryCodec.encodeToString(
                            entry.getValue().snapshot().inventory());
                    databaseManager.validateInventoryPayloadSize(payload);
                    encoded.put(entry.getKey(), payload);
                }
            }
        } catch (Exception encodeFailure) {
            logger.log(Level.SEVERE,
                    "Could not encode database spawner mutation batch", encodeFailure);
            return false;
        }

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement upsert = conn.prepareStatement(upsertSql);
             PreparedStatement delete = conn.prepareStatement(DELETE_SQL)) {
            conn.setAutoCommit(false);
            try {
                int upserts = 0;
                int deletes = 0;
                for (Map.Entry<String, PendingMutation> entry : batch.entrySet()) {
                    PendingMutation mutation = entry.getValue();
                    if (mutation.operation() == PendingMutation.Operation.DELETE) {
                        delete.setString(1, serverName);
                        delete.setString(2, entry.getKey());
                        delete.addBatch();
                        deletes++;
                    } else {
                        setSpawnerParameters(upsert, mutation.snapshot(),
                                encoded.get(entry.getKey()));
                        upsert.addBatch();
                        upserts++;
                    }
                }
                if (upserts > 0) {
                    upsert.executeBatch();
                }
                if (deletes > 0) {
                    delete.executeBatch();
                }
                conn.commit();
                plugin.debug("Committed " + batch.size()
                        + " database spawner mutations");
                return true;
            } catch (Exception failure) {
                conn.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            logger.log(Level.SEVERE,
                    "Error committing database spawner mutation batch", failure);
            return false;
        }
    }

    private void setSpawnerParameters(PreparedStatement stmt,
                                      SpawnerSnapshot spawner,
                                      String encodedInventory) throws SQLException {
        stmt.setString(1, spawner.id());
        stmt.setString(2, serverName);
        stmt.setString(3, spawner.worldName());
        stmt.setInt(4, spawner.x());
        stmt.setInt(5, spawner.y());
        stmt.setInt(6, spawner.z());
        stmt.setString(7, spawner.entityType());
        stmt.setString(8, spawner.itemSpawnerMaterial());
        stmt.setLong(9, spawner.spawnerExp());
        stmt.setBoolean(10, spawner.spawnerActive());
        stmt.setInt(11, spawner.spawnerRange());
        stmt.setBoolean(12, spawner.spawnerStop());
        stmt.setLong(13, spawner.spawnDelay());
        stmt.setInt(14, spawner.maxSpawnerLootSlots());
        stmt.setLong(15, spawner.maxStoredExp());
        stmt.setInt(16, spawner.minMobs());
        stmt.setInt(17, spawner.maxMobs());
        stmt.setInt(18, spawner.stackSize());
        stmt.setInt(19, spawner.maxStackSize());
        stmt.setLong(20, spawner.lastSpawnTime());
        stmt.setBoolean(21, spawner.atCapacity());
        stmt.setString(22, spawner.lastInteractedPlayer());
        stmt.setString(23, spawner.preferredSortItem());
        stmt.setString(24, spawner.filteredItems().isEmpty()
                ? null : String.join(",", spawner.filteredItems()));
        stmt.setString(25, encodedInventory);
        stmt.setLong(26, SpawnerInventoryCodec.totalItems(spawner.inventory()));
        stmt.setLong(27, spawner.lifetimeExpiresAt());
        stmt.setBoolean(28, spawner.expired());
    }

    @Override
    public Map<String, SpawnerData> loadAllSpawnersRaw() {
        Map<String, SpawnerData> loadedSpawners = new HashMap<>();
        int legacyInventories = 0;
        int modernInventories = 0;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL)) {

            stmt.setString(1, serverName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String spawnerId = rs.getString("spawner_id");
                    String rawInventory = rs.getString("inventory_data");
                    if (rawInventory != null && !rawInventory.isEmpty()) {
                        if (rawInventory.startsWith(SpawnerInventoryCodec.PREFIX)) {
                            modernInventories++;
                        } else {
                            legacyInventories++;
                        }
                    }
                    try {
                        SpawnerData spawner = loadSpawnerFromResultSet(rs);
                        loadedSpawners.put(spawnerId, spawner);

                        // Cache location for WorldEventHandler
                        if (spawner == null) {
                            String worldName = rs.getString("world_name");
                            int x = rs.getInt("loc_x");
                            int y = rs.getInt("loc_y");
                            int z = rs.getInt("loc_z");
                            locationCache.put(spawnerId, String.format("%s,%d,%d,%d", worldName, x, y, z));
                        }
                    } catch (Exception e) {
                        plugin.debug("Error loading spawner " + spawnerId + ": " + e.getMessage());
                        loadedSpawners.put(spawnerId, null);
                    }
                }
            }

            if (legacyInventories > 0 || modernInventories > 0) {
                logger.info("Database inventory formats for server " + serverName
                        + ": " + legacyInventories + " legacy, "
                        + modernInventories + " ssinv1.");
                if (legacyInventories > 0 && modernInventories > 0) {
                    logger.warning("Mixed legacy/ssinv1 database rows detected. "
                            + "Backups are created per legacy row immediately "
                            + "before lazy rewrite.");
                } else if (legacyInventories == 0 && modernInventories > 0) {
                    logger.warning("All detected database inventories for "
                            + serverName + " are already ssinv1; a new backup "
                            + "cannot recover their former legacy form.");
                }
            }

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error loading spawners from database", e);
        }

        return loadedSpawners;
    }

    @Override
    public SpawnerData loadSpecificSpawner(String spawnerId) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ONE_SQL)) {

            stmt.setString(1, serverName);
            stmt.setString(2, spawnerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return loadSpawnerFromResultSet(rs);
                }
            }

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error loading spawner " + spawnerId + " from database", e);
        }

        return null;
    }

    @Override
    public String getRawLocationString(String spawnerId) {
        // Check cache first
        String cached = locationCache.get(spawnerId);
        if (cached != null) {
            return cached;
        }

        // Query database
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_LOCATION_SQL)) {

            stmt.setString(1, serverName);
            stmt.setString(2, spawnerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("loc_x");
                    int y = rs.getInt("loc_y");
                    int z = rs.getInt("loc_z");
                    String location = String.format("%s,%d,%d,%d", worldName, x, y, z);
                    locationCache.put(spawnerId, location);
                    return location;
                }
            }

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting location for spawner " + spawnerId, e);
        }

        return null;
    }

    @Override
    public long loadOnlineTimeMillis() {
        try {
            return databaseManager.loadServerOnlineTimeMillis(serverName);
        } catch (SQLException failure) {
            logger.log(Level.WARNING,
                    "Could not load database lifetime clock", failure);
            return 0L;
        }
    }

    @Override
    public void saveOnlineTimeMillis(long onlineTimeMillis) {
        try {
            databaseManager.saveServerOnlineTimeMillis(
                    serverName, onlineTimeMillis);
        } catch (SQLException failure) {
            logger.log(Level.WARNING,
                    "Could not checkpoint database lifetime clock", failure);
        }
    }

    private SpawnerData loadSpawnerFromResultSet(ResultSet rs) throws SQLException {
        String spawnerId = rs.getString("spawner_id");
        String worldName = rs.getString("world_name");
        int x = rs.getInt("loc_x");
        int y = rs.getInt("loc_y");
        int z = rs.getInt("loc_z");

        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.debug("World not yet loaded for spawner " + spawnerId + ": " + worldName);
            return null;
        }

        Location location = new Location(world, x, y, z);
        String entityTypeStr = rs.getString("entity_type");
        boolean omniSpawner = "OMNI".equalsIgnoreCase(entityTypeStr);
        EntityType entityType;
        if (omniSpawner) {
            entityType = EntityType.PIG;
        } else {
            try {
                entityType = EntityType.valueOf(entityTypeStr);
            } catch (IllegalArgumentException e) {
                logger.severe("Invalid entity type for spawner " + spawnerId + ": " + entityTypeStr);
                return null;
            }
        }

        // Create spawner based on type
        SpawnerData spawner;
        String itemMaterialStr = rs.getString("item_spawner_material");
        if (entityType == EntityType.ITEM && itemMaterialStr != null) {
            try {
                Material itemMaterial = Material.valueOf(itemMaterialStr);
                spawner = new SpawnerData(spawnerId, location, itemMaterial, plugin);
            } catch (IllegalArgumentException e) {
                logger.severe("Invalid item spawner material for spawner " + spawnerId + ": " + itemMaterialStr);
                return null;
            }
        } else {
            spawner = new SpawnerData(spawnerId, location, entityType, plugin);
        }
        if (omniSpawner) {
            spawner.setOmniSpawner(true);
        }

        // Load settings
        spawner.setSpawnerExpData(rs.getLong("spawner_exp"));
        spawner.setSpawnerActive(rs.getBoolean("spawner_active"));
        spawner.setSpawnerRange(rs.getInt("spawner_range"));
        spawner.getSpawnerStop().set(rs.getBoolean("spawner_stop"));
        spawner.setSpawnDelay(Math.max(1L, rs.getLong("spawn_delay")));
        spawner.setMaxSpawnerLootSlots(rs.getInt("max_spawner_loot_slots"));
        spawner.setMaxStoredExp(rs.getLong("max_stored_exp"));
        spawner.setMinMobs(rs.getInt("min_mobs"));
        spawner.setMaxMobs(rs.getInt("max_mobs"));
        spawner.setMaxStackSize(rs.getInt("max_stack_size"));
        spawner.setStackSize(rs.getInt("stack_size"), false); // Don't restart hopper during batch load
        spawner.setLastSpawnTime(rs.getLong("last_spawn_time"));
        spawner.setIsAtCapacity(rs.getBoolean("is_at_capacity"));
        spawner.setLifetimeExpiresAt(rs.getLong("lifetime_expires_at"));
        spawner.setExpired(rs.getBoolean("lifetime_expired"));
        if (spawner.isExpired()) {
            spawner.setSpawnerActive(false);
            spawner.getSpawnerStop().set(true);
        }

        // Load player interaction data
        spawner.setLastInteractedPlayer(rs.getString("last_interacted_player"));

        // Load preferred sort item
        String preferredSortItemStr = rs.getString("preferred_sort_item");
        if (preferredSortItemStr != null && !preferredSortItemStr.isEmpty()) {
            try {
                Material preferredSortItem = Material.valueOf(preferredSortItemStr);
                spawner.setPreferredSortItem(preferredSortItem);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid preferred sort item for spawner " + spawnerId + ": " + preferredSortItemStr);
            }
        }

        // Load filtered items
        String filteredItemsStr = rs.getString("filtered_items");
        if (filteredItemsStr != null && !filteredItemsStr.isEmpty()) {
            deserializeFilteredItems(filteredItemsStr, spawner.getFilteredItems());
        }

        // Load inventory
        String inventoryData = rs.getString("inventory_data");
        VirtualInventory virtualInv = new VirtualInventory(spawner.getMaxSpawnerLootSlots());
        boolean legacyInventory = false;
        if (inventoryData != null && !inventoryData.isEmpty()) {
            try {
                Map<ItemStack, Long> items;
                boolean legacy = !inventoryData.startsWith(SpawnerInventoryCodec.PREFIX);
                legacyInventory = legacy;
                if (legacy) {
                    items = LegacyInventoryCodec.deserialize(
                            LegacyInventoryCodec.parseJsonArray(inventoryData));
                } else {
                    items = SpawnerInventoryCodec.decodeString(inventoryData);
                }
                for (Map.Entry<ItemStack, Long> entry : items.entrySet()) {
                    virtualInv.addItem(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                throw new SQLException("Refusing to load spawner " + spawnerId
                        + " because its inventory could not be decoded", e);
            }
        }
        spawner.setVirtualInventory(virtualInv);
        spawner.markSellValueDirty();

        // Apply sort preference to virtual inventory
        if (spawner.getPreferredSortItem() != null) {
            virtualInv.sortItems(spawner.getPreferredSortItem());
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
            databaseManager.backupLegacyInventoryRow(serverName, spawnerId, inventoryData);
            mutations.modified(SpawnerSnapshot.capture(spawner));
        }

        return spawner;
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
            logger.severe("Database shutdown completed with " + mutations.size()
                    + " unsaved spawner mutations: " + mutations.ids());
        }
        locationCache.clear();
    }

    // ============== Serialization Helpers ==============

    private String serializeFilteredItems(Set<Material> filteredItems) {
        if (filteredItems == null || filteredItems.isEmpty()) {
            return null;
        }
        return filteredItems.stream()
                .map(Material::name)
                .collect(Collectors.joining(","));
    }

    private void deserializeFilteredItems(String data, Set<Material> filteredItems) {
        if (data == null || data.isEmpty()) return;

        String[] materialNames = data.split(",");
        for (String materialName : materialNames) {
            try {
                Material material = Material.valueOf(materialName.trim());
                filteredItems.add(material);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid material in filtered items: " + materialName);
            }
        }
    }

    // ============== Cross-Server Query Methods ==============

    /**
     * Get the current server name.
     * @return The server name from config
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Asynchronously get all distinct server names from the database.
     * @param callback Consumer to receive the list of server names on the main thread
     */
    public void getDistinctServerNamesAsync(Consumer<List<String>> callback) {
        Scheduler.runTaskAsync(() -> {
            List<String> servers = new ArrayList<>();
            String sql = "SELECT DISTINCT server_name FROM smart_spawners ORDER BY server_name";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    servers.add(rs.getString("server_name"));
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching server names from database", e);
            }

            // Return to main thread
            Scheduler.runTask(() -> callback.accept(servers));
        });
    }

    /**
     * Asynchronously get world names with spawner counts for a specific server.
     * @param targetServer The server name to query
     * @param callback Consumer to receive map of world name -> spawner statistics
     */
    public void getWorldsForServerAsync(String targetServer, Consumer<Map<String, WorldSpawnerStats>> callback) {
        Scheduler.runTaskAsync(() -> {
            Map<String, WorldSpawnerStats> worlds = new LinkedHashMap<>();
            String sql = "SELECT world_name, COUNT(*) AS total, COALESCE(SUM(stack_size), 0) AS total_stacked " +
                    "FROM smart_spawners WHERE server_name = ? GROUP BY world_name ORDER BY world_name";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, targetServer);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        worlds.put(
                                rs.getString("world_name"),
                                new WorldSpawnerStats(rs.getInt("total"), rs.getInt("total_stacked"))
                        );
                    }
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching worlds for server " + targetServer, e);
            }

            Scheduler.runTask(() -> callback.accept(worlds));
        });
    }

    public record WorldSpawnerStats(int total, int totalStacked) {}

    /**
     * Asynchronously get total stacked spawner count for a server/world.
     * @param targetServer The server name
     * @param worldName The world name
     * @param callback Consumer to receive total stack count
     */
    public void getTotalStacksForWorldAsync(String targetServer, String worldName, Consumer<Integer> callback) {
        Scheduler.runTaskAsync(() -> {
            int total = 0;
            String sql = "SELECT SUM(stack_size) as total FROM smart_spawners WHERE server_name = ? AND world_name = ?";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, targetServer);
                stmt.setString(2, worldName);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt("total");
                    }
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching stack total for " + targetServer + "/" + worldName, e);
            }

            final int finalTotal = total;
            Scheduler.runTask(() -> callback.accept(finalTotal));
        });
    }

    /**
     * Asynchronously get spawner data for a specific server and world.
     * Returns CrossServerSpawnerData objects that don't require Bukkit Location objects.
     * @param targetServer The server name to query
     * @param worldName The world name to query
     * @param callback Consumer to receive list of spawner data
     */
    public void getCrossServerSpawnersAsync(String targetServer, String worldName, Consumer<List<CrossServerSpawnerData>> callback) {
        Scheduler.runTaskAsync(() -> {
            List<CrossServerSpawnerData> spawners = new ArrayList<>();
            String sql = """
                SELECT spawner_id, server_name, world_name, loc_x, loc_y, loc_z,
                       entity_type, stack_size, spawner_stop, last_interacted_player,
                       spawner_exp, total_items
                FROM smart_spawners
                WHERE server_name = ? AND world_name = ?
                ORDER BY stack_size DESC
                """;

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, targetServer);
                stmt.setString(2, worldName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String spawnerId = rs.getString("spawner_id");
                        String server = rs.getString("server_name");
                        String world = rs.getString("world_name");
                        int x = rs.getInt("loc_x");
                        int y = rs.getInt("loc_y");
                        int z = rs.getInt("loc_z");

                        EntityType entityType;
                        try {
                            entityType = EntityType.valueOf(rs.getString("entity_type"));
                        } catch (IllegalArgumentException e) {
                            entityType = EntityType.PIG; // Fallback
                        }

                        int stackSize = rs.getInt("stack_size");
                        boolean active = !rs.getBoolean("spawner_stop");
                        String lastPlayer = rs.getString("last_interacted_player");
                        long storedExp = rs.getLong("spawner_exp");

                        long totalItems = rs.getLong("total_items");

                        spawners.add(new CrossServerSpawnerData(
                                spawnerId, server, world, x, y, z,
                                entityType, stackSize, active, lastPlayer,
                                storedExp, totalItems
                        ));
                    }
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching spawners for " + targetServer + "/" + worldName, e);
            }

            Scheduler.runTask(() -> callback.accept(spawners));
        });
    }

    /**
     * Get spawner count for a specific server.
     * @param targetServer The server name
     * @param callback Consumer to receive the count
     */
    public void getSpawnerCountForServerAsync(String targetServer, Consumer<Integer> callback) {
        Scheduler.runTaskAsync(() -> {
            int count = 0;
            String sql = "SELECT COUNT(*) as count FROM smart_spawners WHERE server_name = ?";

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, targetServer);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getInt("count");
                    }
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching spawner count for " + targetServer, e);
            }

            final int finalCount = count;
            Scheduler.runTask(() -> callback.accept(finalCount));
        });
    }

    /**
     * Asynchronously get spawner data for a specific server and world with filter and sort.
     * @param targetServer The server name to query
     * @param worldName The world name to query
     * @param filter Filter option (ALL, ACTIVE, INACTIVE)
     * @param sort Sort option (DEFAULT, STACK_SIZE_DESC, STACK_SIZE_ASC)
     * @param callback Consumer to receive list of spawner data
     */
    public void getCrossServerSpawnersAsync(String targetServer, String worldName,
                                            String filter, String sort,
                                            Consumer<List<CrossServerSpawnerData>> callback) {
        Scheduler.runTaskAsync(() -> {
            List<CrossServerSpawnerData> spawners = new ArrayList<>();

            // Build dynamic SQL based on filter and sort
            StringBuilder sql = new StringBuilder("""
                SELECT spawner_id, server_name, world_name, loc_x, loc_y, loc_z,
                       entity_type, stack_size, spawner_stop, last_interacted_player,
                       spawner_exp, total_items
                FROM smart_spawners
                WHERE server_name = ? AND world_name = ?
                """);

            // Add filter condition
            if ("ACTIVE".equalsIgnoreCase(filter)) {
                sql.append(" AND spawner_stop = FALSE");
            } else if ("INACTIVE".equalsIgnoreCase(filter)) {
                sql.append(" AND spawner_stop = TRUE");
            }

            // Add sort order
            if ("STACK_SIZE_ASC".equalsIgnoreCase(sort)) {
                sql.append(" ORDER BY stack_size ASC");
            } else if ("STACK_SIZE_DESC".equalsIgnoreCase(sort)) {
                sql.append(" ORDER BY stack_size DESC");
            } else {
                sql.append(" ORDER BY spawner_id ASC"); // DEFAULT sort
            }

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

                stmt.setString(1, targetServer);
                stmt.setString(2, worldName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String spawnerId = rs.getString("spawner_id");
                        String server = rs.getString("server_name");
                        String world = rs.getString("world_name");
                        int x = rs.getInt("loc_x");
                        int y = rs.getInt("loc_y");
                        int z = rs.getInt("loc_z");

                        EntityType entityType;
                        try {
                            entityType = EntityType.valueOf(rs.getString("entity_type"));
                        } catch (IllegalArgumentException e) {
                            entityType = EntityType.PIG; // Fallback
                        }

                        int stackSize = rs.getInt("stack_size");
                        boolean active = !rs.getBoolean("spawner_stop");
                        String lastPlayer = rs.getString("last_interacted_player");
                        long storedExp = rs.getLong("spawner_exp");
                        long totalItems = rs.getLong("total_items");

                        spawners.add(new CrossServerSpawnerData(
                                spawnerId, server, world, x, y, z,
                                entityType, stackSize, active, lastPlayer,
                                storedExp, totalItems
                        ));
                    }
                }

            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching spawners for " + targetServer + "/" + worldName, e);
            }

            Scheduler.runTask(() -> callback.accept(spawners));
        });
    }

}
