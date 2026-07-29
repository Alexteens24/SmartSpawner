package github.nighter.smartspawner.spawner.lifetime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpawnerDuration {
    public static final long MIN_DURATION_MILLIS = 60L * 60L * 1000L;
    private static final Pattern PART_PATTERN = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);

    private SpawnerDuration() {
    }

    public static long parseMillis(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }

        String normalized = input.trim().toLowerCase(Locale.ROOT).replace("_", "");
        Matcher matcher = PART_PATTERN.matcher(normalized);
        long total = 0L;
        int consumed = 0;

        while (matcher.find()) {
            if (matcher.start() != consumed) {
                throw new IllegalArgumentException("Invalid duration format");
            }

            long value = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "d" -> 24L * 60L * 60L * 1000L;
                case "h" -> 60L * 60L * 1000L;
                case "m" -> 60L * 1000L;
                case "s" -> 1000L;
                default -> throw new IllegalArgumentException("Unsupported duration unit");
            };

            total = Math.addExact(total, Math.multiplyExact(value, multiplier));
            consumed = matcher.end();
        }

        if (consumed != normalized.length() || total < MIN_DURATION_MILLIS) {
            throw new IllegalArgumentException("Duration must be at least one hour");
        }
        return total;
    }

    public static String formatCompact(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0) {
            return String.format("%dd %02dh", days, hours);
        }
        if (hours > 0) {
            return String.format("%02dh %02dm", hours, minutes);
        }
        return String.format("%02dm %02ds", minutes, seconds);
    }
}
