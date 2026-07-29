package github.nighter.smartspawner.spawner.properties;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;

public class ItemSignature {
    private final ItemStack template;
    private final int hashCode;
    // Cache purposes
    @Getter private final Material material;
    @Getter private final int maxStackSize;
    @Getter private final int damage;
    @Getter @Accessors(fluent = true) private final boolean hasItemMeta;

    public ItemSignature(ItemStack item) {
        this.template = item.asQuantity(1); // Clone with new amount
        this.material = template.getType();
        this.maxStackSize = template.getMaxStackSize();

        ItemMeta meta = template.hasItemMeta() ? template.getItemMeta() : null;

        this.hasItemMeta = meta != null;
        this.damage = meta instanceof org.bukkit.inventory.meta.Damageable damageable
                ? damageable.getDamage()
                : 0;
        this.hashCode = 31 * material.hashCode() + Objects.hashCode(meta);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemSignature that)) return false;

        return material == that.material
                && damage == that.damage
                && Objects.equals(template.getItemMeta(), that.template.getItemMeta());
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public ItemStack getTemplate() {
        return template.clone();
    }

    // Non-cloning method for internal use
    public ItemStack getUnsafeTemplateRef() {
        return template;
    }

    public String getMaterialName() {
        return material.name();
    }

}
