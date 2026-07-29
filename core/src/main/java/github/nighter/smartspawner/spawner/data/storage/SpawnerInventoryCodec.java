package github.nighter.smartspawner.spawner.data.storage;

import github.nighter.smartspawner.spawner.properties.ItemSignature;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless, versioned storage codec for consolidated virtual inventories.
 */
public final class SpawnerInventoryCodec {
    public static final String PREFIX = "ssinv1:";
    private static final byte FORMAT_VERSION = 1;
    private static final int MAX_DECODED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENCODED_CHARS = ((MAX_DECODED_BYTES + 2) / 3) * 4;

    private SpawnerInventoryCodec() {
    }

    public static String encodeToString(Map<ItemSignature, Long> items) throws IOException {
        byte[] encoded = encode(items);
        return encoded == null ? null : PREFIX + Base64.getEncoder().encodeToString(encoded);
    }

    public static byte[] encode(Map<ItemSignature, Long> items) throws IOException {
        if (items == null || items.isEmpty()) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(items.size());
        List<Long> amounts = new ArrayList<>(items.size());
        for (Map.Entry<ItemSignature, Long> entry : items.entrySet()) {
            ItemSignature signature = entry.getKey();
            Long amount = entry.getValue();
            if (signature == null || amount == null || amount <= 0L) {
                continue;
            }
            ItemStack template = signature.getTemplate();
            if (template.getType() == Material.AIR) {
                continue;
            }
            template.setAmount(1);
            templates.add(template);
            amounts.add(amount);
        }
        if (templates.isEmpty()) {
            return null;
        }

        byte[] itemPayload;
        try {
            itemPayload = ItemStack.serializeItemsAsBytes(templates);
        } catch (RuntimeException failure) {
            throw new IOException("Could not serialize item templates", failure);
        }

        long expectedSize = 1L + 4L + amounts.size() * 8L + 4L + itemPayload.length;
        if (expectedSize > MAX_DECODED_BYTES) {
            throw new IOException("Spawner inventory payload is too large: " + expectedSize + " bytes");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) expectedSize);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(amounts.size());
            for (Long amount : amounts) {
                out.writeLong(amount);
            }
            out.writeInt(itemPayload.length);
            out.write(itemPayload);
        }
        return buffer.toByteArray();
    }

    public static Map<ItemStack, Long> decodeString(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        if (!encoded.startsWith(PREFIX)) {
            throw new IOException("Unknown spawner inventory encoding");
        }
        if (encoded.length() - PREFIX.length() > MAX_ENCODED_CHARS) {
            throw new IOException("Encoded spawner inventory payload exceeds safety limit");
        }

        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IOException("Invalid Base64 spawner inventory payload", invalidBase64);
        }
        return decode(blob);
    }

    public static Map<ItemStack, Long> decode(byte[] blob) throws IOException {
        if (blob == null || blob.length == 0) {
            return Map.of();
        }
        if (blob.length > MAX_DECODED_BYTES) {
            throw new IOException("Spawner inventory payload exceeds safety limit");
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported spawner inventory format version: " + version);
            }

            int count = in.readInt();
            if (count < 0) {
                throw new IOException("Negative spawner inventory entry count: " + count);
            }
            long minimumBytes = 1L + 4L + count * 8L + 4L;
            if (minimumBytes > blob.length) {
                throw new IOException("Spawner inventory entry count exceeds payload size");
            }

            long[] amounts = new long[count];
            for (int i = 0; i < count; i++) {
                amounts[i] = in.readLong();
                if (amounts[i] <= 0L) {
                    throw new IOException("Non-positive item amount at entry " + i);
                }
            }

            int payloadLength = in.readInt();
            if (payloadLength < 0 || payloadLength != in.available()) {
                throw new IOException("Invalid spawner item payload length: " + payloadLength);
            }
            byte[] itemPayload = in.readNBytes(payloadLength);

            ItemStack[] templates;
            try {
                templates = ItemStack.deserializeItemsFromBytes(itemPayload);
            } catch (RuntimeException failure) {
                throw new IOException("Could not deserialize item templates", failure);
            }
            if (templates.length != count) {
                throw new IOException("Spawner inventory entry count mismatch");
            }

            Map<ItemStack, Long> result = new LinkedHashMap<>(Math.max(16, count));
            for (int i = 0; i < count; i++) {
                ItemStack template = templates[i];
                if (template == null || template.getType() == Material.AIR) {
                    throw new IOException("Invalid item template at entry " + i);
                }
                try {
                    result.merge(template.asQuantity(1), amounts[i], Math::addExact);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Item amount overflow while decoding", overflow);
                }
            }
            return result;
        }
    }

    public static long totalItems(Map<ItemSignature, Long> items) {
        long total = 0L;
        if (items == null) {
            return total;
        }
        for (Long amount : items.values()) {
            if (amount == null || amount <= 0L) {
                continue;
            }
            if (total > Long.MAX_VALUE - amount) {
                return Long.MAX_VALUE;
            }
            total += amount;
        }
        return total;
    }
}
