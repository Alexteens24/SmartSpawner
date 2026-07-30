package github.nighter.smartspawner.spawner.interactions.destroy;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.events.SpawnerPlayerBreakEvent;
import github.nighter.smartspawner.extras.HopperService;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.hooks.protections.CheckBreakBlock;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.gui.main.SpawnerMenuAction;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.config.Config;
import github.nighter.smartspawner.spawner.config.SpawnerSettingsConfig;
import github.nighter.smartspawner.spawner.item.SpawnerItemFactory;
import github.nighter.smartspawner.spawner.utils.SpawnerLocationLockManager;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class SpawnerBreakListener implements Listener {
    private static final int MAX_STACK_SIZE = 64;
    private static final int MAX_PHYSICAL_DROP_STACKS = 256;
    private static final String DROP_CHANCE_BYPASS_PERMISSION = "smartspawner.break.bypassdropchance";
    private final BreakPluginContext plugin;
    private final MessageService messageService;
    private final SpawnerManager spawnerManager;
    private final SpawnerItemFactory spawnerItemFactory;
    private final SpawnerLocationLockManager locationLockManager;
    private Config breakConfig;

    public SpawnerBreakListener(SmartSpawner plugin) {
        this(new SmartSpawnerBreakPluginContext(plugin));
    }

    SpawnerBreakListener(BreakPluginContext plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
        this.spawnerManager = plugin.getSpawnerManager();
        this.spawnerItemFactory = plugin.getSpawnerItemFactory();
        this.locationLockManager = plugin.getSpawnerLocationLockManager();
        loadConfig();
    }

    public void loadConfig() {
        this.breakConfig = Config.get();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final Location location = block.getLocation();

        if (block.getType() != Material.SPAWNER) {
            return;
        }

        SpawnerRemovalService removalService = plugin.getSpawnerRemovalService();
        if (removalService != null && removalService.isRemovalPending(location)) {
            event.setCancelled(true);
            messageService.sendMessage(player, "action_in_progress");
            return;
        }

        if (!CheckBreakBlock.CanPlayerBreakBlock(player, location)) {
            event.setCancelled(true);
            return;
        }

        if (!breakConfig.isBreakEnabled()) {
            event.setCancelled(true);
            return;
        }

        final SpawnerData spawner = spawnerManager.getSpawnerByLocation(location);

        if (!player.hasPermission("smartspawner.break")) {
            event.setCancelled(true);
            messageService.sendMessage(player, "break_no_permission");
            return;
        }

        if (!breakConfig.isNaturalBreakable()) {
            if (spawner == null) {
                block.setType(Material.AIR);
                event.setCancelled(true);
                return;
            }
        }

        boolean breakHandled;
        if (spawner != null) {
            breakHandled = handleSmartSpawnerBreak(block, spawner, player);
        } else {
            CreatureSpawner creatureSpawner = (CreatureSpawner) block.getState(false);
            if(callAPIEvent(player, block.getLocation(), 1, creatureSpawner.getSpawnedType())) {
                event.setCancelled(true);
                return;
            }
            breakHandled = handleVanillaSpawnerBreak(block, creatureSpawner, player);
        }

        event.setCancelled(true);
        if (breakHandled) {
            cleanupAssociatedHopper(block);
        }
    }

    private boolean handleSmartSpawnerBreak(Block block, SpawnerData spawner, Player player) {
        Location location = block.getLocation();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!validateBreakConditions(player, tool, spawner)) {
            return false;
        }

        // Acquire location-based lock to prevent race conditions
        // This prevents simultaneous GUI destack + pickaxe break duplication exploits
        if (!locationLockManager.tryLock(location)) {
            // Another break operation is already in progress
            messageService.sendMessage(player, "action_in_progress");
            return false;
        }

        boolean ownsBreakOperation = false;
        try {
            // Re-verify spawner still exists after acquiring lock
            SpawnerData currentSpawner = spawnerManager.getSpawnerByLocation(location);
            if (currentSpawner == null || !currentSpawner.getSpawnerId().equals(spawner.getSpawnerId())) {
                // Spawner was removed/changed by another operation
                return false;
            }

            if (!currentSpawner.tryStartStorageOperation(
                    SpawnerData.StorageOperation.BREAK)) {
                messageService.sendMessage(player, "action_in_progress");
                return false;
            }
            ownsBreakOperation = true;

            if (currentSpawner.isExpired()) {
                if (currentSpawner.getVirtualInventory().getTotalItems() > 0L
                        || currentSpawner.getSpawnerExp() > 0L) {
                    messageService.sendMessage(player,
                            "lifetime.expired_storage_not_empty");
                    return false;
                }

                ItemStack emptyCage =
                        spawnerItemFactory.createEmptySpawnerItem();
                int cageAmount = currentSpawner.getStackSize();
                int maxStack = Math.max(1, emptyCage.getMaxStackSize());
                long physicalStacks =
                        (cageAmount + (long) maxStack - 1L) / maxStack;
                if (physicalStacks > MAX_PHYSICAL_DROP_STACKS) {
                    messageService.sendMessage(player,
                            "break_too_many_drops");
                    return false;
                }

                plugin.getSpawnerGuiViewManager()
                        .closeAllViewersInventory(currentSpawner);
                Location dropLocation =
                        findSafeDropLocation(block, player);
                cleanupSpawner(block, currentSpawner);

                if (breakConfig.isDirectToInventory()) {
                    giveSpawnersToPlayer(player, cageAmount, emptyCage);
                    player.playSound(player.getLocation(),
                            Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
                } else {
                    dropItemStacks(block.getWorld(), dropLocation,
                            emptyCage, cageAmount);
                }
                if (player.getGameMode() != GameMode.CREATIVE) {
                    reduceDurability(tool, player, breakConfig.getDurabilityLoss());
                }
                return true;
            }

            boolean wantsStackBreak = currentSpawner.isTimed()
                    ? currentSpawner.getStackSize() > 1
                    : player.isSneaking() && currentSpawner.getStackSize() > 1;
            boolean bypassDropChance = hasDropChanceBypass(player);

            // Track player interaction for last interaction field
            currentSpawner.updateLastInteractedPlayer(player.getName());

            plugin.getSpawnerGuiViewManager().closeAllViewersInventory(currentSpawner);

            SpawnerBreakResult result = processDrops(player, location, currentSpawner,
                    currentSpawner.isTimed()
                            ? wantsStackBreak
                            : wantsStackBreak && breakConfig.isSneakBreakEnabled(),
                    bypassDropChance);
            if (!result.isSuccess()) {
                return false;
            }

            if (result.isFullyRemoved()) {
                // Option B: only trigger break-time auto claim/sell when the spawner is fully removed.
                currentSpawner.finishStorageOperation(
                        SpawnerData.StorageOperation.BREAK);
                ownsBreakOperation = false;
                boolean cleanupDeferred = maybeAutoSellAndClaimExp(player, currentSpawner,
                    () -> applyBreakResult(block, currentSpawner, player, result));
                if (!cleanupDeferred) {
                    applyBreakResult(block, currentSpawner, player, result);
                }
            } else {
                applyBreakResult(block, currentSpawner, player, result);
            }

            if (player.getGameMode() != GameMode.CREATIVE) {
                reduceDurability(tool, player, result.getDurabilityLoss());
            }
            return true;
        } finally {
            if (ownsBreakOperation) {
                spawner.finishStorageOperation(
                        SpawnerData.StorageOperation.BREAK);
            }
            locationLockManager.unlock(location);
        }
    }

    private boolean handleVanillaSpawnerBreak(Block block, CreatureSpawner creatureSpawner, Player player) {
        Location location = block.getLocation();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!validateBreakConditions(player, tool, null)) {
            return false;
        }

        // Acquire location-based lock for vanilla spawners too
        if (!locationLockManager.tryLock(location)) {
            messageService.sendMessage(player, "action_in_progress");
            return false;
        }

        try {
            // Re-check block is still a spawner after acquiring lock
            if (block.getType() != Material.SPAWNER) {
                return false;
            }

            EntityType entityType = creatureSpawner.getSpawnedType();
            ItemStack spawnerItem;
            if (breakConfig.isConvertNaturalToSmartSpawner()) {
                spawnerItem = spawnerItemFactory.createSmartSpawnerItem(entityType);
            } else {
                spawnerItem = spawnerItemFactory.createVanillaSpawnerItem(entityType);
            }

            World world = location.getWorld();
            if (world != null) {
                block.setType(Material.AIR);

                if (hasDropChanceBypass(player) || shouldDropSpawner(getNaturalSpawnerDropChance(entityType))) {
                    if (breakConfig.isDirectToInventory()) {
                        giveSpawnersToPlayer(player, 1, spawnerItem);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
                    } else {
                        world.dropItemNaturally(findSafeDropLocation(block, player), spawnerItem);
                    }
                } else {
                    messageService.sendMessage(player, "drop_failed");
                }

                reduceDurability(tool, player, breakConfig.getDurabilityLoss());
                return true;
            }

            return false;
        } finally {
            locationLockManager.unlock(location);
        }
    }

    private boolean validateBreakConditions(Player player, ItemStack tool, SpawnerData spawner) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }

        if (!player.hasPermission("smartspawner.break")) {
            messageService.sendMessage(player, "break_no_permission");
            return false;
        }

        if (!isValidTool(tool)) {
            messageService.sendMessage(player, "break_tool_required");
            return false;
        }

        if (breakConfig.isSilkTouchRequired()) {
            if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) < breakConfig.getSilkTouchLevel()) {
                messageService.sendMessage(player, "break_silk_touch");
                return false;
            }
        }

        return true;
    }

    SpawnerBreakResult processDrops(Player player, Location location, SpawnerData spawner, boolean isCrouching,
                                    boolean bypassDropChance) {
        final int currentStackSize = spawner.getStackSize();

        World world = location.getWorld();
        if (world == null) {
            return new SpawnerBreakResult(false, 0, 0, breakConfig.getDurabilityLoss(), false, new ItemStack(Material.SPAWNER));
        }

        // Create the appropriate spawner item based on type
        ItemStack template;
        if (spawner.isOmniSpawner()) {
            if (spawner.isTimed()) {
                long perSpawnerDuration = Math.max(1L,
                        spawner.getRemainingLifetimeMillis()
                                / Math.max(1, currentStackSize));
                template = spawnerItemFactory.createOmniSpawnerItem(
                        1, perSpawnerDuration);
            } else {
                template = spawnerItemFactory.createOmniSpawnerItem(1);
            }
        } else if (spawner.isItemSpawner()) {
            template = spawnerItemFactory.createItemSpawnerItem(spawner.getSpawnedItemMaterial());
        } else {
            EntityType entityType = spawner.getEntityType();
            if (spawner.isTimed()) {
                long perSpawnerDuration = Math.max(1L,
                        spawner.getRemainingLifetimeMillis() / Math.max(1, currentStackSize));
                template = spawnerItemFactory.createSmartSpawnerItem(entityType, 1, perSpawnerDuration);
            } else {
                template = spawnerItemFactory.createSmartSpawnerItem(entityType);
            }
        }

        int dropAmount;
        boolean shouldDeleteSpawner;
        int newStackSize = currentStackSize;

        if (isCrouching) {
            if (spawner.isTimed()) {
                dropAmount = currentStackSize;
                shouldDeleteSpawner = true;
            } else if (currentStackSize <= MAX_STACK_SIZE) {
                dropAmount = currentStackSize;
                shouldDeleteSpawner = true;
            } else {
                dropAmount = MAX_STACK_SIZE;
                shouldDeleteSpawner = false;
                newStackSize = currentStackSize - MAX_STACK_SIZE;
            }
        } else {
            dropAmount = 1;
            shouldDeleteSpawner = currentStackSize <= 1;
            if (!shouldDeleteSpawner) {
                newStackSize = currentStackSize - 1;
            }
        }

        if(callAPIEvent(player, location, dropAmount, spawner.getEntityType())) {
            return new SpawnerBreakResult(false, dropAmount, 0, 0, false, template);
        }

        int actualDropAmount = bypassDropChance ? dropAmount : rollDroppedAmount(dropAmount, getSmartSpawnerDropChance(spawner));
        long physicalStacks = (actualDropAmount + (long) Math.max(1, template.getMaxStackSize()) - 1L)
                / Math.max(1, template.getMaxStackSize());
        if (physicalStacks > MAX_PHYSICAL_DROP_STACKS) {
            messageService.sendMessage(player, "break_too_many_drops");
            return new SpawnerBreakResult(false, 0, 0, 0, false, template);
        }
        if (!shouldDeleteSpawner) {
            spawner.setStackSize(newStackSize);
        }
        return new SpawnerBreakResult(true, actualDropAmount, dropAmount, breakConfig.getDurabilityLoss(), shouldDeleteSpawner, template);
    }

    void applyBreakResult(Block spawnerBlock, SpawnerData spawner, Player player, SpawnerBreakResult result) {
        if (result.isFullyRemoved()) {
            cleanupSpawner(spawnerBlock, spawner);
        } else {
            spawnerManager.markSpawnerModified(spawner.getSpawnerId());
        }

        if (breakConfig.isDirectToInventory()) {
            if (result.getDroppedAmount() <= 0) {
                messageService.sendMessage(player, "drop_failed");
                return;
            }
            giveSpawnersToPlayer(player, result.getDroppedAmount(), result.getDropTemplate());
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            return;
        }

        World world = spawnerBlock.getWorld();
        if (world == null) {
            return;
        }

        if (result.getDroppedAmount() <= 0) {
            messageService.sendMessage(player, "drop_failed");
            return;
        }

        dropItemStacks(world, findSafeDropLocation(spawnerBlock, player),
                result.getDropTemplate(), result.getDroppedAmount());
    }

    private double getSmartSpawnerDropChance(SpawnerData spawner) {
        if (spawner.isOmniSpawner()) {
            return 100.0;
        }
        SpawnerSettingsConfig settingsConfig = plugin.getSpawnerSettingsConfig();
        if (settingsConfig == null) {
            return 100.0;
        }
        return settingsConfig.getSpawnerDropChance(spawner.getEntityType());
    }

    private boolean hasSmartSpawnerDropChance(SpawnerData spawner) {
        if (spawner.isOmniSpawner()) {
            return false;
        }
        SpawnerSettingsConfig settingsConfig = plugin.getSpawnerSettingsConfig();
        return settingsConfig != null && settingsConfig.hasSpawnerDropChance(spawner.getEntityType());
    }

    private boolean hasDropChanceBypass(Player player) {
        return player.hasPermission(DROP_CHANCE_BYPASS_PERMISSION);
    }

    private double getNaturalSpawnerDropChance(EntityType entityType) {
        return breakConfig.getNaturalSpawnerDropChance(entityType);
    }

    private boolean shouldDropSpawner(double dropChance) {
        if (dropChance >= 100.0) {
            return true;
        }
        if (dropChance <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble(100.0) < dropChance;
    }

    private int rollDroppedAmount(int amount, double dropChance) {
        if (amount <= 0 || dropChance <= 0.0) {
            return 0;
        }
        if (dropChance >= 100.0) {
            return amount;
        }

        double probability = dropChance / 100.0;
        if (amount > 100_000) {
            double mean = amount * probability;
            double deviation = Math.sqrt(amount * probability * (1.0 - probability));
            long sampled = Math.round(mean
                    + ThreadLocalRandom.current().nextGaussian() * deviation);
            return (int) Math.clamp(sampled, 0L, (long) amount);
        }

        int droppedAmount = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < amount; i++) {
            if (random.nextDouble(100.0) < dropChance) {
                droppedAmount++;
            }
        }
        return droppedAmount;
    }

    private boolean callAPIEvent(Player player, Location location, int dropAmount, EntityType entityType) {
        if(SpawnerPlayerBreakEvent.getHandlerList().getRegisteredListeners().length != 0) {
            SpawnerPlayerBreakEvent e = new SpawnerPlayerBreakEvent(player, location, dropAmount, entityType);
            Bukkit.getPluginManager().callEvent(e);
            return e.isCancelled();
        }
        return false;
    }

    private void reduceDurability(ItemStack tool, Player player, int durabilityLoss) {
        if (tool.getType().getMaxDurability() == 0) {
            return;
        }

        ItemMeta meta = tool.getItemMeta();
        if (meta.isUnbreakable()) {
            return;
        }
        if (meta instanceof Damageable) {
            Damageable damageable = (Damageable) meta;
            int currentDurability = damageable.getDamage();
            int newDurability = currentDurability + durabilityLoss;

            if (newDurability >= tool.getType().getMaxDurability()) {
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDurability);
                tool.setItemMeta(meta);
            }
        }
    }

    private void cleanupSpawner(Block block, SpawnerData spawner) {
        spawner.getSpawnerStop().set(true);
        block.setType(Material.AIR);

        String spawnerId = spawner.getSpawnerId();
        plugin.getRangeChecker().deactivateSpawner(spawner);
        spawnerManager.removeSpawner(spawnerId);
        spawnerManager.markSpawnerDeleted(spawnerId);

        // Remove location lock to prevent memory leak
        Location location = block.getLocation();
        locationLockManager.removeLock(location);
    }

    /**
     * Finds a safe drop location for spawner items. Scans adjacent faces in priority order
     * (down -> horizontal -> up) for true air blocks. If every side is blocked (including
     * slab/partial-block scenarios), it falls back to dropping at the player's location.
     */
    private Location findSafeDropLocation(Block block, Player player) {
        BlockFace[] priority = {
            BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP
        };
        for (BlockFace face : priority) {
            Block neighbor = block.getRelative(face);
            if (isSafeItemDropSpace(neighbor)) {
                return neighbor.getLocation().toCenterLocation();
            }
        }

        // Fully enclosed (including slab/partial-block cases) - drop near player instead.
        return player.getLocation().toCenterLocation();
    }

    private boolean isSafeItemDropSpace(Block block) {
        // Only true air blocks are considered safe. This rejects slabs/partial blocks.
        return block.getType().isAir();
    }

    private boolean isValidTool(ItemStack tool) {
        if (tool == null) {
            return false;
        }
        return breakConfig.isRequiredTool(tool.getType());
    }

    boolean maybeAutoSellAndClaimExp(Player player, SpawnerData spawner, Runnable onSellComplete) {
        if (!breakConfig.isSellAndXpBreak()) {
            return false;
        }

        SpawnerMenuAction menuAction = plugin.getSpawnerMenuAction();
        if (menuAction != null && spawner.getSpawnerExp() > 0) {
            menuAction.collectExpForPlayer(player, spawner);
        }

        if (!plugin.hasSellIntegration() || !player.hasPermission("smartspawner.sellall")) {
            return false;
        }

        if (spawner.getVirtualInventory().getTotalItems() > 0L) {
            // Serialize the spawner removal behind sell completion to avoid delete/modify races
            // with the async sell task. The break itself must always proceed afterwards: auto-sell
            // is a best-effort bonus, so a sell that removes nothing (items have no sell value, the
            // API event cancelled it, or the economy deposit failed) must not block the player from
            // breaking the spawner - just like a normal break, any remaining stored items are gone
            // once the spawner is removed. sellAllItems guarantees the callback runs exactly once
            // on the spawner's region/main thread.
            plugin.getSpawnerSellManager().sellAllItems(player, spawner, onSellComplete);
            return true;
        }

        return false;
    }

    private void giveSpawnersToPlayer(Player player, int amount, ItemStack template) {
        int remaining = amount;
        int maxStack = Math.max(1, template.getMaxStackSize());
        boolean overflow = false;
        while (remaining > 0) {
            ItemStack itemToGive = template.clone();
            int stackAmount = Math.min(maxStack, remaining);
            itemToGive.setAmount(stackAmount);
            Map<Integer, ItemStack> failedItems = player.getInventory().addItem(itemToGive);
            if (!failedItems.isEmpty()) {
                overflow = true;
                for (ItemStack failedItem : failedItems.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation().toCenterLocation(), failedItem);
                }
            }
            remaining -= stackAmount;
        }
        if (overflow) {
            messageService.sendMessage(player, "inventory_full");
        }

        player.updateInventory();
    }

    private void dropItemStacks(World world, Location dropLocation,
                                ItemStack template, int amount) {
        int remaining = amount;
        int maxStack = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            ItemStack dropItem = template.clone();
            int stackAmount = Math.min(maxStack, remaining);
            dropItem.setAmount(stackAmount);
            world.dropItemNaturally(dropLocation, dropItem);
            remaining -= stackAmount;
        }
    }

    private void giveStoredItemsToPlayer(Player player, SpawnerData spawner) {
        spawner.getInventoryLock().lock();
        try {
            for (Map.Entry<ItemSignature, Long> entry
                    : spawner.getVirtualInventory().getConsolidatedItems().entrySet()) {
                long remaining = entry.getValue();
                ItemStack template = entry.getKey().getTemplate();
                int maxStack = Math.max(1, template.getMaxStackSize());
                while (remaining > 0L) {
                    ItemStack stack = template.clone();
                    int amount = (int) Math.min(maxStack, remaining);
                    stack.setAmount(amount);
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                    for (ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(
                                player.getLocation().toCenterLocation(), leftover);
                    }
                    remaining -= amount;
                }
            }
        } finally {
            spawner.getInventoryLock().unlock();
        }
        player.updateInventory();
    }

    static class SpawnerBreakResult {
        @Getter private final boolean success;
        @Getter private final int droppedAmount;
        private final int removedAmount;
        private final int baseDurabilityLoss;
        @Getter private final boolean fullyRemoved;
        @Getter private final ItemStack dropTemplate;

        public SpawnerBreakResult(boolean success, int droppedAmount, int removedAmount, int baseDurabilityLoss,
                                  boolean fullyRemoved, ItemStack dropTemplate) {
            this.success = success;
            this.droppedAmount = droppedAmount;
            this.removedAmount = removedAmount;
            this.baseDurabilityLoss = baseDurabilityLoss;
            this.fullyRemoved = fullyRemoved;
            this.dropTemplate = dropTemplate.clone();
        }

        public int getDurabilityLoss() {
            return baseDurabilityLoss;
        }
    }

    @EventHandler
    public void onSpawnerDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (block.getType() != Material.SPAWNER) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType() == Material.AIR) {
            return;
        }

        if (isValidTool(tool)) {
            if (breakConfig.isSilkTouchRequired()) {
                if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) < breakConfig.getSilkTouchLevel()) {
                    messageService.sendMessage(player, "break_silk_touch");
                    return;
                }
            }

            if (!player.hasPermission("smartspawner.break")) {
                messageService.sendMessage(player, "break_no_permission");
            }

        } else {
            messageService.sendMessage(player, "break_tool_required");
        }
    }

    public void cleanupAssociatedHopper(Block block) {
        HopperService currentHopperService = plugin.getHopperService();
        if (currentHopperService == null) {
            return;
        }

        currentHopperService.getTracker().removeBelowSpawner(block);
    }

    interface BreakPluginContext {
        MessageService getMessageService();
        SpawnerManager getSpawnerManager();
        HopperService getHopperService();
        SpawnerItemFactory getSpawnerItemFactory();
        SpawnerLocationLockManager getSpawnerLocationLockManager();
        SpawnerGuiViewManager getSpawnerGuiViewManager();
        SpawnerSettingsConfig getSpawnerSettingsConfig();
        boolean hasSellIntegration();
        SpawnerMenuAction getSpawnerMenuAction();
        github.nighter.smartspawner.spawner.sell.SpawnerSellManager getSpawnerSellManager();
        github.nighter.smartspawner.spawner.lootgen.SpawnerRangeChecker getRangeChecker();
        SpawnerRemovalService getSpawnerRemovalService();
    }

    private static final class SmartSpawnerBreakPluginContext implements BreakPluginContext {
        private final SmartSpawner plugin;

        private SmartSpawnerBreakPluginContext(SmartSpawner plugin) {
            this.plugin = plugin;
        }

        @Override public MessageService getMessageService() { return plugin.getMessageService(); }
        @Override public SpawnerManager getSpawnerManager() { return plugin.getSpawnerManager(); }
        @Override public HopperService getHopperService() { return plugin.getHopperService(); }
        @Override public SpawnerItemFactory getSpawnerItemFactory() { return plugin.getSpawnerItemFactory(); }
        @Override public SpawnerLocationLockManager getSpawnerLocationLockManager() { return plugin.getSpawnerLocationLockManager(); }
        @Override public SpawnerGuiViewManager getSpawnerGuiViewManager() { return plugin.getSpawnerGuiViewManager(); }
        @Override public SpawnerSettingsConfig getSpawnerSettingsConfig() { return plugin.getSpawnerSettingsConfig(); }
        @Override public boolean hasSellIntegration() { return plugin.hasSellIntegration(); }
        @Override public SpawnerMenuAction getSpawnerMenuAction() { return plugin.getSpawnerMenuAction(); }
        @Override public github.nighter.smartspawner.spawner.sell.SpawnerSellManager getSpawnerSellManager() { return plugin.getSpawnerSellManager(); }
        @Override public github.nighter.smartspawner.spawner.lootgen.SpawnerRangeChecker getRangeChecker() { return plugin.getRangeChecker(); }
        @Override public SpawnerRemovalService getSpawnerRemovalService() { return plugin.getSpawnerRemovalService(); }
    }
}
