package github.nighter.smartspawner.spawner.lootgen.loot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public record LootItem(Material material, int minAmount, int maxAmount, double chance, Integer minDurability,
                       Integer maxDurability, PotionType potionType, double sellPrice) {

    public ItemStack createItemStack() {
        if (material == null) {
            return null; // Material not available in this version
        }

        ItemStack item = new ItemStack(material, 1);

        // Apply durability if needed
        if (minDurability != null && maxDurability != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof Damageable) {
                int durability = ThreadLocalRandom.current().nextInt(maxDurability - minDurability + 1) + minDurability;
                ((Damageable) meta).setDamage(durability);
                item.setItemMeta(meta);
            }
        }

        // Handle potion effects for tipped arrows using modern API
        if (material == Material.TIPPED_ARROW && potionType != null) {
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            if (meta != null) {
                meta.setBasePotionType(potionType);
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    public int generateAmount(Random random) {
        return random.nextInt(maxAmount - minAmount + 1) + minAmount;
    }

    public double getAverageAmount() {
        return ((long) this.maxAmount + this.minAmount) / 2.0;
    }

    public boolean isAvailable() {
        return material != null;
    }
}
