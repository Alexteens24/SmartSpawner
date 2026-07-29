package github.nighter.smartspawner.extras;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.data.SpawnerManager;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.spawner.properties.ItemSignature;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.utils.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public class HopperTransfer {
    private final SmartSpawner plugin;
    private final SpawnerManager spawnerManager;
    private final SpawnerGuiViewManager guiManager;

    public HopperTransfer(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerManager = plugin.getSpawnerManager();
        this.guiManager = plugin.getSpawnerGuiViewManager();
    }

    public void process(BlockPos hopperPos) {
        Location hopperLoc = hopperPos.toLocation();
        if (hopperLoc == null) {
            return;
        }

        Block hopperBlock = hopperLoc.getBlock();
        if (hopperBlock.getType() != Material.HOPPER) {
            return;
        }

        Block spawnerBlock = hopperBlock.getRelative(BlockFace.UP);
        if (spawnerBlock.getType() == Material.SPAWNER) {
            transferItems(hopperLoc, spawnerBlock.getLocation());
        }
    }

    private void transferItems(Location hopperLoc, Location spawnerLoc) {
        SpawnerData spawner = spawnerManager.getSpawnerByLocation(spawnerLoc);
        if (spawner == null || spawner.isSelling()) {
            return;
        }

        ReentrantLock lock = spawner.getInventoryLock();
        if (!lock.tryLock()) {
            return;
        }

        try {
            if (spawner.isSelling()) {
                return;
            }

            VirtualInventory virtualInventory = spawner.getVirtualInventory();
            if (virtualInventory == null) {
                return;
            }

            var state = hopperLoc.getBlock().getState(false);
            if (!(state instanceof Hopper hopper)) {
                return;
            }

            Inventory hopperInventory = hopper.getInventory();
            ItemStack[] before = cloneContents(hopperInventory.getContents());
            ItemStack[] plannedContents = cloneContents(before);
            Map<ItemSignature, Long> available = virtualInventory.getConsolidatedItems();
            Map<ItemSignature, Long> toRemove = new HashMap<>();
            int remainingOperations = Math.max(0, plugin.getHopperConfig().getStackPerTransfer());

            // Fill compatible partial stacks first.
            for (int slot = 0; slot < plannedContents.length && remainingOperations > 0; slot++) {
                ItemStack destination = plannedContents[slot];
                if (destination == null || destination.getType() == Material.AIR
                        || destination.getAmount() >= destination.getMaxStackSize()) {
                    continue;
                }

                ItemSignature signature = VirtualInventory.getSignature(destination);
                long stored = available.getOrDefault(signature, 0L);
                if (stored <= 0L) {
                    continue;
                }

                int moved = (int) Math.min(stored, destination.getMaxStackSize() - destination.getAmount());
                destination.setAmount(destination.getAmount() + moved);
                reserve(available, toRemove, signature, moved);
                remainingOperations--;
            }

            // Empty hopper slots can receive the first remaining logical stacks directly.
            for (int slot = 0; slot < plannedContents.length && remainingOperations > 0; slot++) {
                ItemStack destination = plannedContents[slot];
                if (destination != null && destination.getType() != Material.AIR) {
                    continue;
                }

                Map.Entry<ItemSignature, Long> source = firstAvailable(available);
                if (source == null) {
                    break;
                }

                ItemStack inserted = source.getKey().getTemplate();
                int moved = (int) Math.min(source.getValue(), inserted.getMaxStackSize());
                inserted.setAmount(moved);
                plannedContents[slot] = inserted;
                reserve(available, toRemove, source.getKey(), moved);
                remainingOperations--;
            }

            if (toRemove.isEmpty() || !spawner.removeItemsAndUpdateSellValue(toRemove)) {
                return;
            }

            try {
                hopperInventory.setContents(plannedContents);
            } catch (RuntimeException updateFailure) {
                spawner.addItemsAndUpdateSellValue(toRemove);
                try {
                    hopperInventory.setContents(before);
                } catch (RuntimeException restoreFailure) {
                    updateFailure.addSuppressed(restoreFailure);
                }
                throw updateFailure;
            }

            guiManager.updateSpawnerMenuViewers(spawner);
            spawnerManager.markSpawnerModified(spawner.getSpawnerId());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Error transferring items from spawner to hopper at " + hopperLoc, ex);
        } finally {
            lock.unlock();
        }
    }

    private static void reserve(Map<ItemSignature, Long> available,
                                Map<ItemSignature, Long> toRemove,
                                ItemSignature signature,
                                long amount) {
        long remaining = available.getOrDefault(signature, 0L) - amount;
        if (remaining > 0L) {
            available.put(signature, remaining);
        } else {
            available.remove(signature);
        }
        toRemove.merge(signature, amount, HopperTransfer::saturatingAdd);
    }

    private static Map.Entry<ItemSignature, Long> firstAvailable(Map<ItemSignature, Long> available) {
        for (Map.Entry<ItemSignature, Long> entry : available.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
                return entry;
            }
        }
        return null;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
