package github.nighter.smartspawner.commands.give;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.BaseSubCommand;
import github.nighter.smartspawner.utils.DynamicEntityValidator;
import github.nighter.smartspawner.spawner.item.SpawnerItemFactory;
import github.nighter.smartspawner.spawner.lifetime.SpawnerDuration;
import github.nighter.smartspawner.spawner.lifetime.SpawnerDurationArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@NullMarked
public class GiveSubCommand extends BaseSubCommand {
    private final SpawnerItemFactory spawnerItemFactory;
    private final List<String> supportedMobs;
    private static final int MAX_AMOUNT = 6400;

    public GiveSubCommand(SmartSpawner plugin) {
        super(plugin);
        this.spawnerItemFactory = plugin.getSpawnerItemFactory();
        // Generate supported mobs list from DynamicEntityValidator
        this.supportedMobs = DynamicEntityValidator.getValidEntities().stream()
                .map(EntityType::name)
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public String getName() {
        return "give";
    }

    @Override
    public String getPermission() {
        return "smartspawner.command.give";
    }

    @Override
    public String getDescription() {
        return "Give spawners to players";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(getName());
        builder.requires(source -> hasPermission(source.getSender()));

        // Add subcommands for regular, vanilla, and item spawners
        builder.then(buildRegularGiveCommand());
        builder.then(buildVanillaGiveCommand());
        builder.then(buildItemSpawnerGiveCommand());

        return builder;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRegularGiveCommand() {
        return Commands.literal("spawner")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("mobType", StringArgumentType.word())
                                .suggests(createMobSuggestions())
                                .executes(context -> executeGive(context, false, 1, -1L))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
                                        .executes(context -> executeGive(context, false,
                                                IntegerArgumentType.getInteger(context, "amount"), -1L))
                                        .then(Commands.argument("duration", SpawnerDurationArgumentType.duration())
                                                .suggests(createDurationSuggestions())
                                                .executes(context -> executeGive(context, false,
                                                        IntegerArgumentType.getInteger(context, "amount"),
                                                        parseDuration(context)))))
                                .then(Commands.argument("duration", SpawnerDurationArgumentType.duration())
                                        .suggests(createDurationSuggestions())
                                        .executes(context -> executeGive(context, false, 1,
                                                parseDuration(context))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildVanillaGiveCommand() {
        return Commands.literal("vanilla_spawner")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("mobType", StringArgumentType.word())
                                .suggests(createMobSuggestions())
                                .executes(context -> executeGive(context, true, 1, -1L))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
                                        .executes(context -> executeGive(context, true,
                                                IntegerArgumentType.getInteger(context, "amount"), -1L)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildItemSpawnerGiveCommand() {
        return Commands.literal("item_spawner")
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(Commands.argument("itemType", StringArgumentType.word())
                                .suggests(createItemSuggestions())
                                .executes(context -> executeGiveItemSpawner(context, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
                                        .executes(context -> executeGiveItemSpawner(context,
                                                IntegerArgumentType.getInteger(context, "amount"))))));
    }

    private SuggestionProvider<CommandSourceStack> createMobSuggestions() {
        return (context, builder) -> {
            String input = builder.getRemaining().toLowerCase();
            supportedMobs.stream()
                    .map(String::toLowerCase) // Convert to lowercase for suggestions
                    .filter(mob -> mob.startsWith(input))
                    .forEach(builder::suggest);
            if ("all".startsWith(input)) {
                builder.suggest("all");
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> createDurationSuggestions() {
        return (context, builder) -> {
            for (String duration : List.of("1h", "8h", "16h", "24h", "3d", "7d")) {
                if (duration.startsWith(builder.getRemaining().toLowerCase())) {
                    builder.suggest(duration);
                }
            }
            return builder.buildFuture();
        };
    }

    private long parseDuration(CommandContext<CommandSourceStack> context) {
        try {
            return SpawnerDuration.parseMillis(
                    SpawnerDurationArgumentType.getDuration(context, "duration"));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Long.MIN_VALUE;
        }
    }

    private SuggestionProvider<CommandSourceStack> createItemSuggestions() {
        return (context, builder) -> {
            String input = builder.getRemaining().toLowerCase();
            plugin.getItemSpawnerSettingsConfig().getValidItemSpawnerMaterials().stream()
                    .map(material -> material.name().toLowerCase())
                    .filter(item -> item.startsWith(input))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        return 0;
    }

    private int executeGive(CommandContext<CommandSourceStack> context, boolean isVanilla,
                            int amount, long durationMillis) {
        CommandSender sender = context.getSource().getSender();
        
        // Log command execution
        logCommandExecution(context);

        if (durationMillis == Long.MIN_VALUE) {
            plugin.getMessageService().sendMessage(sender, "give.invalid_duration");
            return 0;
        }

        try {
            // Get the player selector and resolve it
            var playerSelector = context.getArgument("player", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class);
            List<Player> players = playerSelector.resolve(context.getSource());

            if (players.isEmpty()) {
                plugin.getMessageService().sendMessage(sender, "give.player_not_found");
                return 0;
            }

            Player target = players.get(0); // Get the first (and typically only) player from the selector
            String mobType = StringArgumentType.getString(context, "mobType");

            if (!isVanilla && mobType.equalsIgnoreCase("all")) {
                for (String supportedMob : supportedMobs) {
                    EntityType entityType = EntityType.valueOf(supportedMob);
                    ItemStack item = durationMillis > 0L
                            ? spawnerItemFactory.createSmartSpawnerItem(entityType, amount, durationMillis)
                            : spawnerItemFactory.createSmartSpawnerItem(entityType, amount);
                    giveOrDropOverflow(target, item);
                }
                target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target.getName());
                placeholders.put("amount", String.valueOf(amount));
                placeholders.put("entity", "all supported mobs");
                placeholders.put("ᴇɴᴛɪᴛʏ", "all supported mobs");
                plugin.getMessageService().sendMessage(sender, "give.spawner_given", placeholders);
                plugin.getMessageService().sendMessage(target, "give.spawner_received", placeholders);
                return 1;
            }

            // Validate mob type (case insensitive check)
            if (!supportedMobs.contains(mobType.toUpperCase())) {
                plugin.getMessageService().sendMessage(sender, "give.invalid_mob_type");
                return 0;
            }

            EntityType entityType = EntityType.valueOf(mobType.toUpperCase());
            ItemStack spawnerItem;

            if (isVanilla) {
                // Use the correct method for vanilla spawners
                spawnerItem = spawnerItemFactory.createVanillaSpawnerItem(entityType, amount);
            } else {
                // Use spawner item factory for smart spawners
                spawnerItem = durationMillis > 0L
                        ? spawnerItemFactory.createSmartSpawnerItem(entityType, amount, durationMillis)
                        : spawnerItemFactory.createSmartSpawnerItem(entityType, amount);
            }

            giveOrDropOverflow(target, spawnerItem);

            // Play sound
            target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

            // Get formatted entity names for placeholders
            String entityName = plugin.getLanguageManager().getFormattedMobName(entityType);
            String smallCapsEntityName = plugin.getLanguageManager().getSmallCaps(entityName);

            // Create placeholders for sender message
            HashMap<String, String> senderPlaceholders = new HashMap<>();
            senderPlaceholders.put("player", target.getName());
            senderPlaceholders.put("entity", entityName);
            senderPlaceholders.put("ᴇɴᴛɪᴛʏ", smallCapsEntityName);
            senderPlaceholders.put("amount", String.valueOf(amount));

            // Create placeholders for target message
            HashMap<String, String> targetPlaceholders = new HashMap<>();
            targetPlaceholders.put("amount", String.valueOf(amount));
            targetPlaceholders.put("entity", entityName);
            targetPlaceholders.put("ᴇɴᴛɪᴛʏ", smallCapsEntityName);

            // Send messages with placeholders
            plugin.getMessageService().sendMessage(sender, "give.spawner_given", senderPlaceholders);
            plugin.getMessageService().sendMessage(target, "give.spawner_received", targetPlaceholders);

            return 1;
        } catch (IllegalArgumentException | ArithmeticException e) {
            plugin.getMessageService().sendMessage(sender, "give.invalid_duration");
            return 0;
        } catch (Exception e) {
            plugin.getLogger().severe("Error executing give command: " + e.getMessage());
            return 0;
        }
    }

    private int executeGiveItemSpawner(CommandContext<CommandSourceStack> context, int amount) {
        CommandSender sender = context.getSource().getSender();
        
        // Log command execution
        logCommandExecution(context);

        try {
            // Get the player selector and resolve it
            var playerSelector = context.getArgument("player", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class);
            List<Player> players = playerSelector.resolve(context.getSource());

            if (players.isEmpty()) {
                plugin.getMessageService().sendMessage(sender, "give.player_not_found");
                return 0;
            }

            Player target = players.get(0);
            String itemType = StringArgumentType.getString(context, "itemType");

            // Validate item type
            Material itemMaterial;
            try {
                itemMaterial = Material.valueOf(itemType.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getMessageService().sendMessage(sender, "give.invalid_item_type");
                return 0;
            }

            // Verify it's a valid item spawner type
            if (!plugin.getItemSpawnerSettingsConfig().isValidItemSpawner(itemMaterial)) {
                plugin.getMessageService().sendMessage(sender, "give.invalid_item_spawner");
                return 0;
            }

            // Create item spawner
            ItemStack spawnerItem = spawnerItemFactory.createItemSpawnerItem(itemMaterial, amount);

            giveOrDropOverflow(target, spawnerItem);

            // Play sound
            target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

            // Get formatted item names for placeholders
            String itemName = plugin.getLanguageManager().getVanillaItemName(itemMaterial);
            String smallCapsItemName = plugin.getLanguageManager().getSmallCaps(itemName);

            // Create placeholders for sender message (use "entity" key for compatibility with spawner messages)
            HashMap<String, String> senderPlaceholders = new HashMap<>();
            senderPlaceholders.put("player", target.getName());
            senderPlaceholders.put("entity", itemName);
            senderPlaceholders.put("ᴇɴᴛɪᴛʏ", smallCapsItemName);
            senderPlaceholders.put("amount", String.valueOf(amount));

            // Create placeholders for target message
            HashMap<String, String> targetPlaceholders = new HashMap<>();
            targetPlaceholders.put("amount", String.valueOf(amount));
            targetPlaceholders.put("entity", itemName);
            targetPlaceholders.put("ᴇɴᴛɪᴛʏ", smallCapsItemName);

            // Send messages with placeholders (use same keys as regular spawners)
            plugin.getMessageService().sendMessage(sender, "give.spawner_given", senderPlaceholders);
            plugin.getMessageService().sendMessage(target, "give.spawner_received", targetPlaceholders);

            return 1;
        } catch (Exception e) {
            plugin.getLogger().severe("Error executing give item spawner command: " + e.getMessage());
            return 0;
        }
    }

    private void giveOrDropOverflow(Player target, ItemStack itemStack) {
        boolean droppedItems = false;

        for (ItemStack stack : splitIntoValidStacks(itemStack)) {
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(stack.clone());
            if (leftovers.isEmpty()) {
                continue;
            }

            droppedItems = true;
            for (ItemStack leftover : leftovers.values()) {
                for (ItemStack dropStack : splitIntoValidStacks(leftover)) {
                    target.getWorld().dropItem(target.getLocation(), dropStack);
                }
            }
        }

        if (droppedItems) {
            plugin.getMessageService().sendMessage(target, "give.inventory_full");
        }

        target.updateInventory();
    }

    private List<ItemStack> splitIntoValidStacks(ItemStack itemStack) {
        List<ItemStack> stacks = new ArrayList<>();
        int maxStackSize = Math.max(1, itemStack.getMaxStackSize());
        int remainingAmount = itemStack.getAmount();

        while (remainingAmount > 0) {
            int stackAmount = Math.min(maxStackSize, remainingAmount);
            ItemStack stack = itemStack.clone();
            stack.setAmount(stackAmount);
            stacks.add(stack);
            remainingAmount -= stackAmount;
        }

        return stacks;
    }
}
