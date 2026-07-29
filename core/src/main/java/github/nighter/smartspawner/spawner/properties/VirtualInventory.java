package github.nighter.smartspawner.spawner.properties;

import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A logical inventory which stores one template and a long count per distinct item.
 * Physical ItemStacks are created only for the page/range currently being displayed.
 */
public class VirtualInventory {
    private final Map<ItemSignature, Long> consolidatedItems = new ConcurrentHashMap<>();
    @Getter
    private volatile int maxSlots;
    private volatile List<Map.Entry<ItemSignature, Long>> sortedEntriesCache;
    private volatile Material preferredSortMaterial;

    public VirtualInventory(int maxSlots) {
        this.maxSlots = Math.max(0, maxSlots);
    }

    public static ItemSignature getSignature(ItemStack item) {
        return new ItemSignature(item);
    }

    public void addItem(ItemStack item, long amount) {
        if (item == null || item.getType() == Material.AIR || amount <= 0L) {
            return;
        }
        addItems(Map.of(getSignature(item), amount));
    }

    public void addItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Map<ItemSignature, Long> consolidated = new HashMap<>(items.size());
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            consolidated.merge(getSignature(item), (long) item.getAmount(), VirtualInventory::saturatingAdd);
        }
        addItems(consolidated);
    }

    public void addItems(Map<ItemSignature, Long> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            ItemSignature signature = entry.getKey();
            Long amount = entry.getValue();
            if (signature == null || amount == null || amount <= 0L) {
                continue;
            }
            consolidatedItems.merge(signature, amount, VirtualInventory::saturatingAdd);
            changed = true;
        }
        if (changed) {
            invalidateDisplayCache();
        }
    }

    public boolean containsAtLeast(Map<ItemSignature, Long> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            if (consolidatedItems.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    public boolean removeItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }

        Map<ItemSignature, Long> consolidated = new HashMap<>(items.size());
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            consolidated.merge(getSignature(item), (long) item.getAmount(), VirtualInventory::saturatingAdd);
        }
        return removeItems(consolidated);
    }

    public boolean removeItems(Map<ItemSignature, Long> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }

        Map<ItemSignature, Long> normalized = new HashMap<>(items.size());
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            ItemSignature signature = entry.getKey();
            Long amount = entry.getValue();
            if (signature == null || amount == null || amount <= 0L) {
                continue;
            }
            normalized.merge(signature, amount, VirtualInventory::saturatingAdd);
        }
        if (normalized.isEmpty()) {
            return true;
        }
        if (!containsAtLeast(normalized)) {
            return false;
        }

        for (Map.Entry<ItemSignature, Long> entry : normalized.entrySet()) {
            consolidatedItems.computeIfPresent(entry.getKey(), (ignored, current) -> {
                long remaining = current - entry.getValue();
                return remaining > 0L ? remaining : null;
            });
        }
        invalidateDisplayCache();
        return true;
    }

    /**
     * Compatibility method. Callers handling large inventories should request a page/range.
     */
    public Map<Integer, ItemStack> getDisplayInventory() {
        return getDisplayRange(0L, maxSlots);
    }

    public Map<Integer, ItemStack> getDisplayPage(int page, int pageSize) {
        if (pageSize <= 0) {
            return Collections.emptyMap();
        }
        long safePage = Math.max(1L, page);
        long startSlot = (safePage - 1L) * pageSize;
        return getDisplayRange(startSlot, pageSize);
    }

    public Map<Integer, ItemStack> getDisplayRange(long startSlot, int maxResults) {
        if (maxResults <= 0 || startSlot < 0L || startSlot >= maxSlots || consolidatedItems.isEmpty()) {
            return Collections.emptyMap();
        }

        int sectionLimit = (int) Math.min((long) maxResults, (long) maxSlots - startSlot);
        if (sectionLimit <= 0) {
            return Collections.emptyMap();
        }

        Map<Integer, ItemStack> section = new LinkedHashMap<>(Math.min(sectionLimit, 45));
        long currentGlobalSlot = 0L;
        int relativeSlot = 0;

        for (Map.Entry<ItemSignature, Long> entry : getSortedEntries()) {
            if (relativeSlot >= sectionLimit || currentGlobalSlot >= maxSlots) {
                break;
            }

            ItemSignature signature = entry.getKey();
            int maxStackSize = signature.getMaxStackSize();
            long totalAmount = entry.getValue();
            if (maxStackSize <= 0 || totalAmount <= 0L) {
                continue;
            }

            long stacksForEntry = slotsFor(totalAmount, maxStackSize);
            if (stacksForEntry <= startSlot - currentGlobalSlot) {
                currentGlobalSlot += stacksForEntry;
                continue;
            }

            long stacksToSkip = Math.max(0L, startSlot - currentGlobalSlot);
            long skippedItems = saturatedMultiply(stacksToSkip, maxStackSize);
            long remainingAmount = Math.max(0L, totalAmount - skippedItems);
            currentGlobalSlot += stacksToSkip;

            while (remainingAmount > 0L
                    && relativeSlot < sectionLimit
                    && currentGlobalSlot < maxSlots) {
                ItemStack displayItem = signature.getTemplate();
                int displayedAmount = (int) Math.min(remainingAmount, maxStackSize);
                displayItem.setAmount(displayedAmount);
                section.put(relativeSlot++, displayItem);
                remainingAmount -= displayedAmount;
                currentGlobalSlot++;
            }
        }

        return Collections.unmodifiableMap(section);
    }

    public Map<ItemSignature, Long> getConsolidatedItems() {
        return new HashMap<>(consolidatedItems);
    }

    public long getTotalItems() {
        long total = 0L;
        for (Long amount : consolidatedItems.values()) {
            if (amount != null && amount > 0L) {
                total = saturatingAdd(total, amount);
            }
        }
        return total;
    }

    public int getUsedSlots() {
        long total = 0L;
        for (Map.Entry<ItemSignature, Long> entry : consolidatedItems.entrySet()) {
            long entrySlots = slotsFor(entry.getValue(), entry.getKey().getMaxStackSize());
            if (entrySlots >= (long) maxSlots - total) {
                return maxSlots;
            }
            total += entrySlots;
        }
        return (int) total;
    }

    public boolean isDirty() {
        return sortedEntriesCache == null;
    }

    public void sortItems(Material preferredMaterial) {
        this.preferredSortMaterial = preferredMaterial;
        invalidateDisplayCache();
    }

    public void resize(int newMaxSlots) {
        this.maxSlots = Math.max(0, newMaxSlots);
    }

    public void setMaxSlots(int newMaxSlots) {
        resize(newMaxSlots);
    }

    private synchronized List<Map.Entry<ItemSignature, Long>> getSortedEntries() {
        List<Map.Entry<ItemSignature, Long>> cached = sortedEntriesCache;
        if (cached != null) {
            return cached;
        }

        List<Map.Entry<ItemSignature, Long>> rebuilt = new ArrayList<>(consolidatedItems.entrySet());
        Comparator<Map.Entry<ItemSignature, Long>> byMaterial =
                Comparator.comparing(entry -> entry.getKey().getMaterialName());
        if (preferredSortMaterial != null) {
            rebuilt.sort(Comparator
                    .comparing((Map.Entry<ItemSignature, Long> entry) ->
                            entry.getKey().getMaterial() != preferredSortMaterial)
                    .thenComparing(byMaterial));
        } else {
            rebuilt.sort(byMaterial);
        }
        sortedEntriesCache = rebuilt;
        return rebuilt;
    }

    private synchronized void invalidateDisplayCache() {
        sortedEntriesCache = null;
    }

    private static long slotsFor(long amount, int maxStackSize) {
        if (amount <= 0L || maxStackSize <= 0) {
            return 0L;
        }
        return ((amount - 1L) / maxStackSize) + 1L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
