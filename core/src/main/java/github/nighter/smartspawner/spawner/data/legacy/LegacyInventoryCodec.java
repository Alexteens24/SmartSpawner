package github.nighter.smartspawner.spawner.data.legacy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict read-only decoder for inventory formats written before ssinv1.
 */
public final class LegacyInventoryCodec {
    private LegacyInventoryCodec() {
    }

    public static List<String> parseJsonArray(String jsonData) throws IOException {
        List<String> entries = new ArrayList<>();
        if (jsonData == null || jsonData.isEmpty()) {
            return entries;
        }
        if (!jsonData.startsWith("[") || !jsonData.endsWith("]")) {
            throw new IOException("Legacy inventory is not a JSON array");
        }

        String content = jsonData.substring(1, jsonData.length() - 1);
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (char character : content.toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                inQuotes = !inQuotes;
            } else if (character == ',' && !inQuotes) {
                entries.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped || inQuotes) {
            throw new IOException("Malformed legacy inventory JSON");
        }
        if (!current.isEmpty()) {
            entries.add(current.toString());
        }
        return entries;
    }

    public static Map<ItemStack, Long> deserialize(List<String> data) throws IOException {
        Map<ItemStack, Long> result = new LinkedHashMap<>();
        if (data == null) {
            return result;
        }

        for (String entry : data) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            try {
                if (entry.startsWith("META#")) {
                    readMetaItem(entry, result);
                } else if (entry.startsWith("TIPPED_ARROW#")) {
                    readTippedArrows(entry, result);
                } else if (entry.contains(";")) {
                    readDamagedItems(entry, result);
                } else {
                    readPlainItem(entry, result);
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new IOException("Malformed legacy inventory entry: " + entry, failure);
            }
        }
        return result;
    }

    private static void readMetaItem(String entry, Map<ItemStack, Long> result) throws IOException {
        int separator = entry.lastIndexOf(':');
        if (separator <= "META#".length()) {
            throw new IOException("Malformed META inventory entry");
        }

        try {
            byte[] itemBytes = Base64.getDecoder().decode(entry.substring("META#".length(), separator));
            ItemStack item = ItemStack.deserializeBytes(itemBytes).asQuantity(1);
            long count = Long.parseLong(entry.substring(separator + 1));
            merge(result, item, count);
        } catch (RuntimeException failure) {
            throw new IOException("Could not decode legacy META item", failure);
        }
    }

    private static void readTippedArrows(String entry, Map<ItemStack, Long> result) throws IOException {
        for (String potionEntry : entry.substring("TIPPED_ARROW#".length()).split(",")) {
            String[] parts = potionEntry.split(":", 2);
            if (parts.length != 2) {
                throw new IOException("Malformed tipped-arrow entry");
            }
            ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
            if (arrow.getItemMeta() instanceof PotionMeta meta) {
                try {
                    meta.setBasePotionType(PotionType.valueOf(parts[0]));
                } catch (IllegalArgumentException unknownPotion) {
                    meta.setBasePotionType(PotionType.WATER);
                }
                arrow.setItemMeta(meta);
            }
            merge(result, arrow, Long.parseLong(parts[1]));
        }
    }

    private static void readDamagedItems(String entry, Map<ItemStack, Long> result) throws IOException {
        String[] parts = entry.split(";", 2);
        if (parts.length != 2) {
            throw new IOException("Malformed damaged-item entry");
        }
        Material material = Material.valueOf(parts[0]);
        for (String damageCount : parts[1].split(",")) {
            String[] pair = damageCount.split(":", 2);
            if (pair.length != 2) {
                throw new IOException("Malformed damage/count pair");
            }
            ItemStack item = new ItemStack(material);
            if (item.getItemMeta() instanceof Damageable damageable) {
                damageable.setDamage(Integer.parseInt(pair[0]));
                item.setItemMeta(damageable);
            }
            merge(result, item, Long.parseLong(pair[1]));
        }
    }

    private static void readPlainItem(String entry, Map<ItemStack, Long> result) throws IOException {
        String[] parts = entry.split(":", 2);
        if (parts.length != 2) {
            throw new IOException("Malformed plain-item entry");
        }
        merge(result, new ItemStack(Material.valueOf(parts[0])), Long.parseLong(parts[1]));
    }

    private static void merge(Map<ItemStack, Long> result, ItemStack item, long count) throws IOException {
        if (item == null || item.getType() == Material.AIR || count <= 0L) {
            throw new IOException("Legacy inventory contains an invalid item/count");
        }
        try {
            result.merge(item.asQuantity(1), count, Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new IOException("Legacy item count overflow", overflow);
        }
    }
}
