package github.nighter.smartspawner.api.events;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable item template and exact quantity exposed by {@link SpawnerSellEvent}.
 */
public final class SpawnerSoldItem {
    private final ItemStack item;
    private final long amount;

    public SpawnerSoldItem(@NotNull ItemStack item, long amount) {
        if (item.getType() == Material.AIR) {
            throw new IllegalArgumentException("Sold item cannot be AIR");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("Sold item amount must be positive");
        }
        this.item = item.asQuantity(1);
        this.amount = amount;
    }

    public @NotNull ItemStack getItem() {
        return item.clone();
    }

    public long getAmount() {
        return amount;
    }
}
