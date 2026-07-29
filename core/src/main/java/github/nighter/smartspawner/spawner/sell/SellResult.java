package github.nighter.smartspawner.spawner.sell;

import github.nighter.smartspawner.spawner.properties.ItemSignature;
import lombok.Getter;

import java.util.Map;

public final class SellResult {
    @Getter
    private final double totalValue;
    @Getter
    private final long itemsSold;
    @Getter
    private final Map<ItemSignature, Long> itemsToRemove;
    @Getter
    private final long timestamp;
    @Getter
    private final boolean successful;

    public SellResult(double totalValue, long itemsSold, Map<ItemSignature, Long> itemsToRemove) {
        this.totalValue = totalValue;
        this.itemsSold = itemsSold;
        this.itemsToRemove = Map.copyOf(itemsToRemove);
        this.timestamp = System.currentTimeMillis();
        this.successful = Double.isFinite(totalValue) && totalValue > 0.0 && !itemsToRemove.isEmpty();
    }

    public static SellResult empty() {
        return new SellResult(0.0, 0L, Map.of());
    }

    public boolean hasItems() {
        return !itemsToRemove.isEmpty();
    }
}
