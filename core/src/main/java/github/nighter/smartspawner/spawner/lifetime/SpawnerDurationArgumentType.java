package github.nighter.smartspawner.spawner.lifetime;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.LiteralMessage;

import java.util.Collection;
import java.util.List;

/**
 * Brigadier argument which fails parsing for unitless numbers. This prevents a
 * numeric amount sibling from being captured as a duration.
 */
public final class SpawnerDurationArgumentType implements ArgumentType<String> {
    private static final DynamicCommandExceptionType INVALID =
            new DynamicCommandExceptionType(value ->
                    new LiteralMessage("Invalid spawner duration: " + value));
    private static final Collection<String> EXAMPLES =
            List.of("1h", "8h", "1d12h", "7d");

    private SpawnerDurationArgumentType() {
    }

    public static SpawnerDurationArgumentType duration() {
        return new SpawnerDurationArgumentType();
    }

    public static String getDuration(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        String value = reader.getString().substring(start, reader.getCursor());
        try {
            SpawnerDuration.parseMillis(value);
            return value;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader, value);
        }
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
