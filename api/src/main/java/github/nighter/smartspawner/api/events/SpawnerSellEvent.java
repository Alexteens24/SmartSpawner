package github.nighter.smartspawner.api.events;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Called when items are sold from a spawner's storage.
 */
@Getter
@Setter
public class SpawnerSellEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private final Player player;
    private final Location location;
    private final List<SpawnerSoldItem> soldItems;
    private final EntityType entityType;
    private double moneyAmount;
    private boolean cancelled = false;

    /**
     * Creates a new spawner sell event.
     *
     * @param player the player selling the items
     * @param location the location of the spawner
     * @param soldItems the exact item templates and quantities being sold
     * @param moneyAmount the amount of money to be given
     */
    public SpawnerSellEvent(Player player, Location location, List<SpawnerSoldItem> soldItems, double moneyAmount) {
        this(player, location, soldItems, moneyAmount, null);
    }

    /**
     * Creates a new spawner sell event.
     *
     * @param player the player selling the items
     * @param location the location of the spawner
     * @param soldItems the exact item templates and quantities being sold
     * @param moneyAmount the amount of money to be given
     * @param entityType the spawned entity type of the selling spawner
     */
    public SpawnerSellEvent(Player player, Location location, List<SpawnerSoldItem> soldItems, double moneyAmount,
                            @Nullable EntityType entityType) {
        this.player = player;
        this.location = location;
        this.soldItems = List.copyOf(soldItems);
        this.moneyAmount = moneyAmount;
        this.entityType = entityType;
    }

    public long getTotalItemCount() {
        long total = 0L;
        for (SpawnerSoldItem soldItem : soldItems) {
            long amount = soldItem.getAmount();
            if (total > Long.MAX_VALUE - amount) {
                return Long.MAX_VALUE;
            }
            total += amount;
        }
        return total;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static @NotNull HandlerList getHandlerList() {
        return handlers;
    }
}
