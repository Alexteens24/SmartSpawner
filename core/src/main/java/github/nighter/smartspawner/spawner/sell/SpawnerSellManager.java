package github.nighter.smartspawner.spawner.sell;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.events.SpawnerSellEvent;
import github.nighter.smartspawner.api.events.SpawnerSoldItem;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.spawner.utils.SpawnerTypeChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

public class SpawnerSellManager {
    private final SmartSpawner plugin;
    private final MessageService messageService;
    private final SpawnerGuiViewManager spawnerGuiViewManager;

    public SpawnerSellManager(SmartSpawner plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
        this.spawnerGuiViewManager = plugin.getSpawnerGuiViewManager();
    }

    public enum SellOutcome {
        SOLD,
        EXP_ONLY,
        CANCELLED,
        NO_SELLABLE_ITEMS,
        ECONOMY_FAILED,
        INVENTORY_CHANGED,
        BUSY,
        FAILED;

        public boolean isSuccessful() {
            return this == SOLD || this == EXP_ONLY;
        }
    }

    public void sellAllItems(Player player, SpawnerData spawner) {
        sellAllItems(player, spawner, false, null);
    }

    public void sellAllItems(Player player, SpawnerData spawner, Runnable onComplete) {
        sellAllItems(player, spawner, false, ignored -> {
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /**
     * Sells all priced items and optionally collects EXP. The CAS is acquired before EXP is
     * collected, so duplicate clicks cannot claim EXP twice.
     */
    public void sellAllItems(Player player, SpawnerData spawner, boolean collectExp,
                             Consumer<SellOutcome> onComplete) {
        if (!spawner.startSelling()) {
            messageService.sendMessage(player, "action_in_progress");
            complete(onComplete, SellOutcome.BUSY);
            return;
        }

        long expCollected = 0L;
        long expMending = 0L;
        try {
            spawnerGuiViewManager.closeAllViewersInventory(spawner);
            if (collectExp) {
                long[] expData = plugin.getSpawnerMenuAction().collectExpSilently(player, spawner);
                expCollected = expData[0];
                expMending = expData[1];
            }
        } catch (RuntimeException expFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "EXP collection failed during sell for " + player.getName(), expFailure);
            finish(spawner, onComplete, SellOutcome.FAILED);
            messageService.sendMessage(player, "action_failed");
            return;
        }

        SellResult result;
        try {
            result = createSellResult(spawner);
        } catch (RuntimeException calculationFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Sell calculation failed for " + player.getName(), calculationFailure);
            finish(spawner, onComplete, SellOutcome.FAILED);
            messageService.sendMessage(player, "action_failed");
            notifyExpOnly(player, expCollected, expMending);
            return;
        }

        final long finalExpCollected = expCollected;
        final long finalExpMending = expMending;
        Location location = spawner.getSpawnerLocation();
        try {
            Scheduler.runLocationTask(location, () -> {
                SellOutcome outcome;
                try {
                    outcome = applySellResult(player, spawner, result, finalExpCollected, finalExpMending);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Sell transaction failed for " + player.getName(), failure);
                    messageService.sendMessage(player, "action_failed");
                    notifyExpOnly(player, finalExpCollected, finalExpMending);
                    outcome = SellOutcome.FAILED;
                } finally {
                    spawner.stopSelling();
                }
                complete(onComplete, outcome);
            });
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not schedule sell transaction for " + player.getName(), schedulingFailure);
            finish(spawner, onComplete, SellOutcome.FAILED);
            messageService.sendMessage(player, "action_failed");
            notifyExpOnly(player, finalExpCollected, finalExpMending);
        }
    }

    private SellResult createSellResult(SpawnerData spawner) {
        Map<ItemSignature, Long> snapshot;
        spawner.getInventoryLock().lock();
        try {
            snapshot = spawner.getVirtualInventory().getConsolidatedItems();
        } finally {
            spawner.getInventoryLock().unlock();
        }

        Map<String, Double> prices = spawner.createPriceCache();
        Map<ItemSignature, Long> sellable = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        long totalItems = 0L;

        for (Map.Entry<ItemSignature, Long> entry : snapshot.entrySet()) {
            ItemSignature signature = entry.getKey();
            long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (amount <= 0L || SpawnerTypeChecker.isEmptySpawner(signature.getUnsafeTemplateRef())) {
                continue;
            }

            double unitPrice = spawner.findItemPrice(signature, prices);
            if (!Double.isFinite(unitPrice) || unitPrice <= 0.0) {
                continue;
            }

            sellable.put(signature, amount);
            totalItems = saturatingAdd(totalItems, amount);
            total = total.add(BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(amount)));
        }

        double totalValue = total.doubleValue();
        if (!Double.isFinite(totalValue)) {
            throw new IllegalStateException("Sell value exceeds the economy provider's numeric range");
        }
        return new SellResult(totalValue, totalItems, sellable);
    }

    private SellOutcome applySellResult(Player player, SpawnerData spawner, SellResult result,
                                        long expCollected, long expMending) {
        if (!result.isSuccessful()) {
            if (expCollected > 0L) {
                notifyExpOnly(player, expCollected, expMending);
                return SellOutcome.EXP_ONLY;
            }
            if (spawner.getVirtualInventory().getUsedSlots() == 0) {
                messageService.sendMessage(player, "spawner_storage_empty");
            } else {
                messageService.sendMessage(player, "no_sellable_items");
            }
            return SellOutcome.NO_SELLABLE_ITEMS;
        }

        double amount = result.getTotalValue();
        if (SpawnerSellEvent.getHandlerList().getRegisteredListeners().length != 0) {
            SpawnerSellEvent event = new SpawnerSellEvent(
                    player,
                    spawner.getSpawnerLocation(),
                    toApiItems(result.getItemsToRemove()),
                    amount,
                    spawner.getEntityType());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                notifyExpOnly(player, expCollected, expMending);
                return expCollected > 0L ? SellOutcome.EXP_ONLY : SellOutcome.CANCELLED;
            }
            amount = event.getMoneyAmount();
            if (!Double.isFinite(amount) || amount < 0.0) {
                messageService.sendMessage(player, "action_failed");
                notifyExpOnly(player, expCollected, expMending);
                return SellOutcome.FAILED;
            }
        }

        Map<ItemSignature, Long> items = result.getItemsToRemove();
        spawner.getInventoryLock().lock();
        try {
            VirtualInventory inventory = spawner.getVirtualInventory();
            if (!inventory.containsAtLeast(items)) {
                messageService.sendMessage(player, "action_failed");
                notifyExpOnly(player, expCollected, expMending);
                return SellOutcome.INVENTORY_CHANGED;
            }
            if (!spawner.removeItemsAndUpdateSellValue(items)) {
                messageService.sendMessage(player, "action_failed");
                notifyExpOnly(player, expCollected, expMending);
                return SellOutcome.INVENTORY_CHANGED;
            }

            boolean deposited;
            try {
                deposited = plugin.getItemPriceManager().getCurrencyManager().deposit(amount, player);
            } catch (RuntimeException economyFailure) {
                spawner.addItemsAndUpdateSellValue(items);
                throw economyFailure;
            }
            if (!deposited) {
                spawner.addItemsAndUpdateSellValue(items);
                messageService.sendMessage(player, "action_failed");
                notifyExpOnly(player, expCollected, expMending);
                return SellOutcome.ECONOMY_FAILED;
            }
        } finally {
            spawner.getInventoryLock().unlock();
        }

        runPostCommit(player, "mark persistence state",
                () -> plugin.getSpawnerManager().markSpawnerModified(spawner.getSpawnerId()));
        runPostCommit(player, "update capacity", spawner::updateCapacityStatus);
        runPostCommit(player, "update hologram", spawner::updateHologramData);
        runPostCommit(player, "update GUI",
                () -> spawnerGuiViewManager.updateSpawnerMenuViewers(spawner));
        double finalAmount = amount;
        runPostCommit(player, "send success message",
                () -> sendSellSuccess(player, result.getItemsSold(), finalAmount,
                        expCollected, expMending));
        runPostCommit(player, "update sell timestamp", spawner::markLastSellAsProcessed);
        return SellOutcome.SOLD;
    }

    private List<SpawnerSoldItem> toApiItems(Map<ItemSignature, Long> items) {
        List<SpawnerSoldItem> result = new ArrayList<>(items.size());
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            result.add(new SpawnerSoldItem(entry.getKey().getTemplate(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private void sendSellSuccess(Player player, long itemsSold, double amount,
                                 long expCollected, long expMending) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", plugin.getLanguageManager().formatNumber(itemsSold));
        placeholders.put("price", plugin.getLanguageManager().formatNumber(amount));

        if (expCollected > 0L) {
            placeholders.put("exp", plugin.getLanguageManager().formatNumber(
                    Math.max(0L, expCollected - expMending)));
            if (expMending > 0L) {
                placeholders.put("exp_mending", plugin.getLanguageManager().formatNumber(expMending));
                messageService.sendMessage(player, "sell_and_exp_success_with_mending", placeholders);
            } else {
                messageService.sendMessage(player, "sell_and_exp_success", placeholders);
            }
        } else {
            messageService.sendMessage(player, "sell_success", placeholders);
        }
    }

    private void notifyExpOnly(Player player, long expCollected, long expMending) {
        if (expCollected > 0L) {
            plugin.getSpawnerMenuAction().sendExpCollectionMessage(player, expCollected, expMending);
        }
    }

    private void finish(SpawnerData spawner, Consumer<SellOutcome> completion, SellOutcome outcome) {
        spawner.stopSelling();
        complete(completion, outcome);
    }

    private void complete(Consumer<SellOutcome> completion, SellOutcome outcome) {
        if (completion != null) {
            completion.accept(outcome);
        }
    }

    private void runPostCommit(Player player, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException postCommitFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Sell completed, but could not " + operation + " for " + player.getName(),
                    postCommitFailure);
        }
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
