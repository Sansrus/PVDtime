package org.example.pvdtime;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.example.pvdtime.api.PvdTimeApi;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class PvdTime implements ModInitializer {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private JsonObject playtimeData = new JsonObject();
    private long lastUpdateTime;
    private final long updateIntervalMillis = TimeUnit.MINUTES.toMillis(1);
    private final long logSaveIntervalMillis = TimeUnit.MINUTES.toMillis(1);
    private final long weeklyCheckIntervalMillis = TimeUnit.MINUTES.toMillis(1);
    private long lastLogSaveTime;
    private long lastWeeklyCheckTime;
    private String lastProcessedWeekId = getCurrentWeekId();
    private int requiredMinutes = 90; // Время, необходимое для получения статуса PVD
    private static final Map<UUID, PlayerPosition> playerPositions = new HashMap<>();
    private static int afkTimeThreshold = 5; // Время AFK по умолчанию (минуты)
    private static boolean afkCheckEnabled = true; // Переменная для хранения статуса проверки AFK
    private static final Map<UUID, Long> lastLogCheckTime = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> playerIsMovingAFK = new HashMap<>();
    private static final Map<UUID, Deque<BlockPos>> lastPlayerBlocks = new HashMap<>();
    private static final Set<UUID> forcedAfk = ConcurrentHashMap.newKeySet();
    private static final DateTimeFormatter JOIN_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());



    @Override
    public void onInitialize() {
        NickColorHandler.register();
        loadConfig();
        loadPlaytimeData();
        initApi();
        lastLogSaveTime = System.currentTimeMillis();
        lastWeeklyCheckTime = lastLogSaveTime;
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        // Подписываемся на вход игрока и обновляем lastJoin
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            onPlayerJoin(player);
        });

    }

    public void initApi() {
        PvdTimeApi.init(this);
    }

    private void onServerTick(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateTime >= updateIntervalMillis) {
            updatePlaytime(server);
            lastUpdateTime = currentTime;
        }

        if (currentTime - lastLogSaveTime >= logSaveIntervalMillis) {
            savePlaytimeData();
            lastLogSaveTime = currentTime;
        }

        if (currentTime - lastWeeklyCheckTime >= weeklyCheckIntervalMillis) {
            checkWeeklyPlaytime(server);
            lastWeeklyCheckTime = currentTime;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            updatePlayerBlockLog(player);
        }
    }

    private String getPreviousWeekId() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate previousWeekDate = now.minusWeeks(1); // Вычитаем неделю
        int year = previousWeekDate.get(WeekFields.ISO.weekBasedYear());
        int week = previousWeekDate.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private void updatePlaytime(MinecraftServer server) {
        String currentWeekId = getCurrentWeekId();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getName().getString();

            // Проверяем AFK статус игрока, если проверка включена
            if (afkCheckEnabled && checkAFKStatus(player)) {
                continue; // Не засчитываем AFK игроков, если проверка включена
            }

            if (!playtimeData.has(playerName)) {
                JsonObject playerEntry = new JsonObject();
                JsonObject weeks = new JsonObject();
                weeks.addProperty(currentWeekId, 0);
                playerEntry.add("weeks", weeks);
                playerEntry.addProperty("PVD", false);
                playtimeData.add(playerName, playerEntry);
            }

            JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
            JsonObject weeks = playerEntry.getAsJsonObject("weeks");

            if (!weeks.has(currentWeekId)) {
                weeks.addProperty(currentWeekId, 0);
            }

            long currentMinutes = weeks.get(currentWeekId).getAsLong();
            weeks.addProperty(currentWeekId, currentMinutes + 1);
        }
    }

    private void checkWeeklyPlaytime(MinecraftServer server) {
        String currentWeekId = getCurrentWeekId();

        // Проверяем, изменилась ли неделя
        if (!currentWeekId.equals(lastProcessedWeekId)) {
            resetWeeklyData();                       // Сбрасываем счетчики
            lastProcessedWeekId = currentWeekId;     // Обновляем последнюю обработанную неделю
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getName().getString();

            if (!playtimeData.has(playerName)) continue;

            JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
            JsonObject weeks = playerEntry.getAsJsonObject("weeks");
            long weekMinutes = weeks.has(currentWeekId) ? weeks.get(currentWeekId).getAsLong() : 0;

            boolean shouldHaveTag = weekMinutes >= requiredMinutes;
            boolean hasTag = player.getCommandTags().contains("PVD");

            if (shouldHaveTag && !hasTag) {
                player.addCommandTag("PVD");
                playerEntry.addProperty("PVD", true);
                System.out.println("[PVDtime] Добавлен тег PVD для " + playerName);
            } else if (!shouldHaveTag && hasTag) {
                player.removeCommandTag("PVD");
                playerEntry.addProperty("PVD", false);
                System.out.println("[PVDtime] Удален тег PVD для " + playerName);
            }
        }
    }

    private void archivePlaytimeData(String weekId) {
        JsonObject archiveData = new JsonObject();
        for (Map.Entry<String, JsonElement> e : playtimeData.entrySet()) {
            String playerName = e.getKey();
            JsonObject playerEntry = e.getValue().getAsJsonObject();
            JsonObject weeks = playerEntry.getAsJsonObject("weeks");
            if (weeks.has(weekId)) {
                JsonObject oneWeekOnly = new JsonObject();
                JsonObject w = new JsonObject();
                w.addProperty(weekId, weeks.get(weekId).getAsLong());
                oneWeekOnly.add("weeks", w);
                oneWeekOnly.addProperty("PVD", playerEntry.get("PVD").getAsBoolean());
                archiveData.add(playerName, oneWeekOnly);
            }
        }

        File archiveFile = Paths.get("playtime_logs", "archive_" + weekId + ".json").toFile();
        archiveFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(archiveFile)) {
            gson.toJson(archiveData, writer);
            System.out.println("[PVDTime] Архив недели " + weekId + " создан: " + archiveFile.getAbsolutePath());
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


    private void resetWeeklyData() {

        String previousWeekId = getPreviousWeekId();
        archivePlaytimeData(previousWeekId);

        playtimeData = new JsonObject();

        savePlaytimeData();
    }

    public String getCurrentWeekId() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        int year = now.get(WeekFields.ISO.weekBasedYear());
        int week = now.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private void savePlaytimeData() {
        File file = Paths.get("playtime_logs", "lastlog.json").toFile();
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(playtimeData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPlaytimeData() {
        File file = Paths.get("playtime_logs", "lastlog.json").toFile();

        if (!file.exists()) {
            playtimeData = new JsonObject();
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            playtimeData = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("pvd")
                        // Команда pvd list
                        .then(literal("list")
                                .executes(context -> {
                                    String currentWeekId = getCurrentWeekId();
                                    StringBuilder sb = new StringBuilder("§6Все игроки и их время:");

                                    List<Map.Entry<String, Long>> playersList = new ArrayList<>();
                                    for (String playerName : playtimeData.keySet()) {
                                        JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                        JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                        long time = weeks.has(currentWeekId) ? weeks.get(currentWeekId).getAsLong() : 0;
                                        if (time > 0) {
                                            if (!playerName.equals("Mitciv")) {
                                                playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                            }
                                        }
                                    }

                                    playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                    for (Map.Entry<String, Long> entry : playersList) {
                                        String playerName = entry.getKey();
                                        long minutes = entry.getValue();
                                        long hours = minutes / 60;
                                        long remainingMinutes = minutes % 60;

                                        JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                        long lastJoinMillis = playerEntry.has("lastJoin") ? playerEntry.get("lastJoin").getAsLong() : 0;
                                        String lastJoinStr = (lastJoinMillis > 0)
                                                ? JOIN_TIME_FORMATTER.format(Instant.ofEpochMilli(lastJoinMillis))
                                                : "—";

                                        String nameColor = (minutes < requiredMinutes) ? "§7- " : "§a- ";
                                        sb.append("\n").append(nameColor).append(playerName)
                                                .append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м")
                                                .append(" §r§f(").append(lastJoinStr).append(")");
                                    }

                                    if (playersList.isEmpty()) {
                                        sb.append("\n§cНет данных о времени игроков.");
                                    }

                                    context.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                    return 1;
                                })
                                .then(literal("active")
                                        .executes(context -> {
                                            String currentWeekId = getCurrentWeekId();
                                            StringBuilder sb = new StringBuilder("§6Активные PVD игроки и их время:");

                                            // Проверяем, может ли исполнитель видеть дату
                                            boolean canSeeDate = context.getSource().hasPermissionLevel(4) ||
                                                    (context.getSource().getEntity() instanceof ServerPlayerEntity execPlayer &&
                                                            "Sansrus".equals(execPlayer.getGameProfile().getName()));

                                            List<Map.Entry<String, Long>> playersList = new ArrayList<>();
                                            for (String playerName : playtimeData.keySet()) {
                                                JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                long time = weeks.has(currentWeekId) ? weeks.get(currentWeekId).getAsLong() : 0;
                                                if (time > requiredMinutes) {
                                                    if (!playerName.equals("Mitciv")) {
                                                        playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                                    }
                                                }
                                            }

                                            playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                            for (Map.Entry<String, Long> entry : playersList) {
                                                String playerName = entry.getKey();
                                                long minutes = entry.getValue();
                                                long hours = minutes / 60;
                                                long remainingMinutes = minutes % 60;

                                                JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                long lastJoinMillis = playerEntry.has("lastJoin") ? playerEntry.get("lastJoin").getAsLong() : 0;
                                                String lastJoinStr = (lastJoinMillis > 0)
                                                        ? JOIN_TIME_FORMATTER.format(Instant.ofEpochMilli(lastJoinMillis))
                                                        : "—";

                                                sb.append("\n§a- ").append(playerName)
                                                        .append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м");

                                                if (canSeeDate) {
                                                    // белая дата + сброс формата
                                                    sb.append(" §r§f(").append(lastJoinStr).append(")");
                                                }
                                            }

                                            if (playersList.isEmpty()) {
                                                sb.append("\n§cНет активных PVD игроков.");
                                            }

                                            context.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                            return 1;
                                        })
                                )
                                .then(literal("last")
                                        .executes(context -> {
                                            String previousWeekId = getPreviousWeekId();
                                            File archiveFile = Paths.get("playtime_logs", "archive_" + previousWeekId + ".json").toFile();

                                            if (!archiveFile.exists()) {
                                                context.getSource().sendFeedback(() -> Text.literal("§cНет данных за прошлую неделю."), false);
                                                return 1;
                                            }

                                            try (FileReader reader = new FileReader(archiveFile)) {
                                                JsonObject archiveData = JsonParser.parseReader(reader).getAsJsonObject();
                                                StringBuilder sb = new StringBuilder("§6Время игроков за прошлую неделю:");

                                                // Проверяем, может ли исполнитель видеть дату
                                                boolean canSeeDate = context.getSource().hasPermissionLevel(4) ||
                                                        (context.getSource().getEntity() instanceof ServerPlayerEntity execPlayer &&
                                                                "Sansrus".equals(execPlayer.getGameProfile().getName()));

                                                List<Map.Entry<String, Long>> playersList = new ArrayList<>();
                                                for (String playerName : archiveData.keySet()) {
                                                    JsonObject playerEntry = archiveData.getAsJsonObject(playerName);
                                                    JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                    long time = weeks.has(previousWeekId) ? weeks.get(previousWeekId).getAsLong() : 0;
                                                    if (time > requiredMinutes) {
                                                        if (!playerName.equals("Mitciv")) {
                                                            playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                                        }
                                                    }
                                                }

                                                playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                                for (Map.Entry<String, Long> entry : playersList) {
                                                    String playerName = entry.getKey();
                                                    long minutes = entry.getValue();
                                                    long hours = minutes / 60;
                                                    long remainingMinutes = minutes % 60;

                                                    // В архиве поле lastJoin может отсутствовать; показываем, если есть
                                                    JsonObject playerEntry = archiveData.getAsJsonObject(playerName);
                                                    long lastJoinMillis = playerEntry.has("lastJoin") ? playerEntry.get("lastJoin").getAsLong() : 0;
                                                    String lastJoinStr = (lastJoinMillis > 0)
                                                            ? JOIN_TIME_FORMATTER.format(Instant.ofEpochMilli(lastJoinMillis))
                                                            : "—";

                                                    sb.append("\n§a- ").append(playerName)
                                                            .append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м");

                                                    if (canSeeDate) {
                                                        sb.append(" §r§f(").append(lastJoinStr).append(")");
                                                    }
                                                }

                                                if (playersList.isEmpty()) {
                                                    sb.append("\n§cНет данных о времени игроков.");
                                                }

                                                context.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                                return 1;
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                                context.getSource().sendFeedback(() -> Text.literal("§cОшибка при загрузке данных."), false);
                                                return 0;
                                            }
                                        })
                                )
                        )

                        .then(literal("settings")
                                .requires(source -> source.hasPermissionLevel(4) ||
                                        (source.getEntity() instanceof ServerPlayerEntity player &&
                                                "Sansrus".equals(player.getGameProfile().getName())))
                                // pvd settings (без аргументов)
                                .executes(ctx -> {
                                    String afkStatus = afkCheckEnabled ? "§aвключен" : "§cотключен";
                                    ctx.getSource().sendFeedback(() -> Text.literal(
                                            "§6Текущие настройки:\n" +
                                                    "§e- Режим AFK: " + afkStatus + "\n" +
                                                    "§e- Время для AFK: " + afkTimeThreshold + " мин\n" +
                                                    "§e- Требуемое время PVD: " + requiredMinutes + " мин"
                                    ), false);
                                    return 1;
                                })
                                // pvd settings afk
                                .then(literal("afk")
                                        .executes(ctx -> {
                                            String status = afkCheckEnabled ? "§aвключен" : "§cотключен";
                                            ctx.getSource().sendFeedback(() -> Text.literal(
                                                    "§6Режим AFK: " + status + "\n" +
                                                            "§eТекущий порог: " + afkTimeThreshold + " мин"
                                            ), false);
                                            return 1;
                                        })
                                        .then(literal("work")
                                                .then(argument("state", StringArgumentType.word())
                                                        .suggests((ctx, b) -> b.suggest("on").suggest("off").buildFuture())
                                                        .executes(ctx -> {
                                                            String s = StringArgumentType.getString(ctx, "state");
                                                            afkCheckEnabled = s.equalsIgnoreCase("on");
                                                            saveConfig();
                                                            String status = afkCheckEnabled ? "§aвключен" : "§cотключен";
                                                            ctx.getSource().sendFeedback(() -> Text.literal(
                                                                    "§6Режим AFK: " + status
                                                            ), false);
                                                            return 1;
                                                        })
                                                )
                                        )
                                        .then(literal("time")
                                                .then(argument("minutes", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            int m = IntegerArgumentType.getInteger(ctx, "minutes");
                                                            afkTimeThreshold = m;
                                                            saveConfig();
                                                            ctx.getSource().sendFeedback(() -> Text.literal(
                                                                    "§6AFK таймер установлен на §e" + m + " мин"
                                                            ), false);
                                                            return 1;
                                                        })
                                                )
                                        ))

                                // pvd settings time
                                .then(literal("time")
                                        .executes(ctx -> {
                                            ctx.getSource().sendFeedback(() -> Text.literal(
                                                    "§6Текущий лимит PVD: §e" + requiredMinutes + " мин"
                                            ), false);
                                            return 1;
                                        })
                                        .then(literal("default")
                                                .then(argument("minutes", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            int m = IntegerArgumentType.getInteger(ctx, "minutes");
                                                            requiredMinutes = m;
                                                            saveConfig();
                                                            checkWeeklyPlaytime(ctx.getSource().getServer());
                                                            ctx.getSource().sendFeedback(() -> Text.literal(
                                                                    "§6Новый лимит PVD: §e" + m + " мин"
                                                            ), false);
                                                            return 1;
                                                        })
                                                )
                                        )
                                        .then(literal("clear")
                                                        .then(argument("player", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> {
                                                                    String rem = builder.getRemaining().toLowerCase();
                                                                    MinecraftServer server = ctx.getSource().getServer();

                                                                    // Всегда предлагаем "all" в clear, если он совпадает по введённому
                                                                    if ("all".contains(rem) || rem.isEmpty()) {
                                                                        builder.suggest("all");
                                                                    }

                                                                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                                                                        String name = p.getGameProfile().getName();
                                                                        if (name.toLowerCase().contains(rem)) {
                                                                            builder.suggest(name);
                                                                        }
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                                .executes(ctx -> {
                                                                    String playerName = StringArgumentType.getString(ctx, "player");
                                                                    String currentWeekId = getCurrentWeekId();

                                                                    if (playerName.equalsIgnoreCase("all")) {
                                                                        for (String pn : playtimeData.keySet()) {
                                                                            JsonObject playerEntry = playtimeData.getAsJsonObject(pn);
                                                                            JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                                            weeks.entrySet().forEach(entry -> entry.setValue(gson.toJsonTree(0)));
                                                                            playerEntry.addProperty("PVD", false);
                                                                        }

                                                                        MinecraftServer server = ctx.getSource().getServer();
                                                                        for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                                                                            pl.removeCommandTag("PVD");
                                                                        }

                                                                        savePlaytimeData();
                                                                        ctx.getSource().sendFeedback(() -> Text.literal("§6Все счетчики обнулены"), false);
                                                                        return 1;
                                                                    }

                                                                    if (playtimeData.has(playerName)) {
                                                                        JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                                        JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                                        weeks.addProperty(currentWeekId, 0);
                                                                        playerEntry.addProperty("PVD", false);

                                                                        ServerPlayerEntity player = ctx.getSource().getServer()
                                                                                .getPlayerManager().getPlayer(playerName);
                                                                        if (player != null) {
                                                                            player.removeCommandTag("PVD");
                                                                        }

                                                                        savePlaytimeData();
                                                                        ctx.getSource().sendFeedback(() ->
                                                                                Text.literal("§6Счетчик игрока " + playerName + " обнулен"), false);
                                                                    } else {
                                                                        ctx.getSource().sendFeedback(() ->
                                                                                Text.literal("§cИгрок " + playerName + " не найден"), false);
                                                                    }
                                                                    return 1;
                                                                })
                                                        )
                                        )

                                        .then(literal("set")
                                                        .then(argument("player", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> {
                                                                    String rem = builder.getRemaining().toLowerCase();
                                                                    MinecraftServer server = ctx.getSource().getServer();

                                                                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                                                                        String name = p.getGameProfile().getName();
                                                                        if (name.toLowerCase().contains(rem)) {
                                                                            builder.suggest(name);
                                                                        }
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                                .then(argument("time", IntegerArgumentType.integer())
                                                                        .executes(ctx -> {
                                                                            String playerName = StringArgumentType.getString(ctx, "player");
                                                                            int newTime = IntegerArgumentType.getInteger(ctx, "time");
                                                                            String currentWeekId = getCurrentWeekId();

                                                                            if (!playtimeData.has(playerName)) {
                                                                                JsonObject playerEntry = new JsonObject();
                                                                                JsonObject weeks = new JsonObject();
                                                                                weeks.addProperty(currentWeekId, newTime);
                                                                                playerEntry.add("weeks", weeks);
                                                                                playerEntry.addProperty("PVD", newTime >= 5);
                                                                                playtimeData.add(playerName, playerEntry);
                                                                            } else {
                                                                                JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                                                if (!playerEntry.has("weeks") || playerEntry.get("weeks").isJsonNull()) {
                                                                                    playerEntry.add("weeks", new JsonObject());
                                                                                }
                                                                                JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                                                weeks.addProperty(currentWeekId, newTime);
                                                                                playerEntry.addProperty("PVD", newTime >= 5);
                                                                            }

                                                                            savePlaytimeData();
                                                                            ctx.getSource().sendFeedback(() -> Text.literal("§6Для " + playerName + " установлено время: " + newTime + " минут."), false);
                                                                            return 1;
                                                                        })
                                                                )
                                                        )
                                        )
                        )
                )
        );
        dispatcher.register(
                literal("afk")
                        .executes(ctx -> {
                            // /afk  — пометить себя AFK
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity self)) {
                                ctx.getSource().sendFeedback(() -> Text.literal("§cТолько игроки могут выполнить эту команду без аргументов."), false);
                                return 0;
                            }

                            UUID id = self.getUuid();
                            forcedAfk.add(id);
                            ctx.getSource().sendFeedback(() -> Text.literal("§6Вход в режим АФК."), false);
                            return 1;
                        })
                        // /afk <player>  — принудительно пометить другого (требуются права или имя Sansrus)
                        .then(argument("player", StringArgumentType.word())
                                .requires(source -> source.hasPermissionLevel(4) ||
                                        (source.getEntity() instanceof ServerPlayerEntity player &&
                                                "Sansrus".equals(player.getGameProfile().getName())))
                                .suggests((ctx, builder) -> {
                                    String rem = builder.getRemaining().toLowerCase();
                                    MinecraftServer server = ctx.getSource().getServer();

                                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                                        String name = p.getGameProfile().getName();
                                        if (name.toLowerCase().contains(rem)) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(targetName);
                                    if (target == null) {
                                        ctx.getSource().sendFeedback(() -> Text.literal("§cИгрок не найден или не в сети."), false);
                                        return 0;
                                    }
                                    forcedAfk.add(target.getUuid());
                                    ctx.getSource().sendFeedback(() -> Text.literal("§6Игрок " + targetName + " помечен как AFK."), false);
                                    return 1;
                                })
                        )

        );
    }

    private void saveConfig() {
        File configFile = new File("playtime_logs/config.json");
        JsonObject config = new JsonObject();
        config.addProperty("requiredMinutes", requiredMinutes);
        config.addProperty("afkCheckEnabled", afkCheckEnabled);
        config.addProperty("afkTimeThreshold", afkTimeThreshold);

        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        File configFile = new File("playtime_logs/config.json");
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();

                if (config.has("requiredMinutes")) {
                    requiredMinutes = config.get("requiredMinutes").getAsInt();
                }
                if (config.has("afkCheckEnabled")) {
                    afkCheckEnabled = config.get("afkCheckEnabled").getAsBoolean();
                }
                if (config.has("afkTimeThreshold")) {
                    afkTimeThreshold = config.get("afkTimeThreshold").getAsInt();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean checkAFKStatus(ServerPlayerEntity player) {
        if (!afkCheckEnabled) return false;

        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        long thresholdMs = TimeUnit.MINUTES.toMillis(afkTimeThreshold);
        boolean isMoveAfk = playerIsMovingAFK.getOrDefault(id, false);

        // --- Принудительный AFK: если игрок помечен вручную, считаем его AFK
        // но если игрок двинулся — снимаем принудительный AFK ---
        if (forcedAfk.contains(id)) {
            double x = Math.floor(player.getX());
            double y = Math.floor(player.getY());
            double z = Math.floor(player.getZ());

            PlayerPosition last = playerPositions.get(id);
            if (last == null) {
                last = new PlayerPosition(x, y, z, now);
                playerPositions.put(id, last);
            }

            if (last.x != x || last.y != y || last.z != z) {
                // Движение — снимаем принудительный AFK
                forcedAfk.remove(id);
                // Обновляем позицию и продолжаем обычную проверку
                last.x = x;
                last.y = y;
                last.z = z;
                last.lastMoveTime = now;
                return false;
            }

            // Всё ещё стоит на месте — считается AFK
            return true;
        }

        // === Проверка по последним 30 блокам ===
        List<BlockPos> lastBlocks = getLast30Blocks(player);
        if (lastBlocks.size() >= 30) {
            Map<BlockPos, Integer> coordCount = new HashMap<>();
            for (BlockPos pos : lastBlocks) {
                coordCount.merge(pos, 1, Integer::sum);
            }

            int maxCount = coordCount.values().stream().max(Integer::compare).orElse(0);

            if (isMoveAfk) {
                if (maxCount <= 5) {
                    playerIsMovingAFK.remove(id);
                    return false;
                } else {
                    return true;
                }
            } else {
                Long lastLogCheck = lastLogCheckTime.get(id);
                if (lastLogCheck == null || (now - lastLogCheck) >= thresholdMs) {
                    lastLogCheckTime.put(id, now);
                    if (maxCount >= 5) {
                        playerIsMovingAFK.put(id, true);
                        return true;
                    }
                }
            }
        }

        // === Проверка по транспорту ===
        double x = Math.floor(player.getX());
        double y = Math.floor(player.getY());
        double z = Math.floor(player.getZ());

        PlayerPosition last = playerPositions.get(id);
        if (last == null) {
            last = new PlayerPosition(x, y, z, now);
            playerPositions.put(id, last);
        }

        boolean isRiding = player.getVehicle() != null;
        if (isRiding) {
            if (last.ridingStartTime == 0) {
                last.ridingStartTime = now;
            } else {
                long ridingTime = now - last.ridingStartTime;
                return ridingTime >= thresholdMs;
            }
            return false;
        } else {
            last.ridingStartTime = 0;
        }

        // === Проверка неподвижности, если не на транспорте ===
        if (last.x != x || last.y != y || last.z != z) {
            last.x = x;
            last.y = y;
            last.z = z;
            last.lastMoveTime = now;
        } else {
            long elapsed = now - last.lastMoveTime;
            return elapsed >= thresholdMs;
        }

        // default
        // unreachable but for completeness:
         return false;
    }



    public static List<BlockPos> getLast30Blocks(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        return new ArrayList<>(lastPlayerBlocks.getOrDefault(id, new ArrayDeque<>()));
    }

    public static void updatePlayerBlockLog(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        BlockPos pos = player.getBlockPos();

        Deque<BlockPos> deque = lastPlayerBlocks.computeIfAbsent(id, k -> new ArrayDeque<>());

        // Если последняя записанная позиция та же — не дублируем
        if (!deque.isEmpty() && deque.getLast().equals(pos)) {
            return;
        }

        // Если игрок помечен принудительно AFK, но двинулся — снимаем пометку немедленно
        forcedAfk.remove(id);

        deque.addLast(pos);
        if (deque.size() > 30) {
            deque.removeFirst();
        }
    }

    // вспомогательный класс для определения позиции игрока
    private static class PlayerPosition {
        double x, y, z;
        long   lastMoveTime;
        long   ridingStartTime;
        PlayerPosition(double x, double y, double z, long t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastMoveTime = t;
            this.ridingStartTime = 0;
        }
    }

    private void onPlayerJoin(ServerPlayerEntity player) {
        String playerName = player.getName().getString();
        long now = System.currentTimeMillis();

        // Создаём запись игрока, если её нет
        if (!playtimeData.has(playerName)) {
            JsonObject playerEntry = new JsonObject();
            JsonObject weeks = new JsonObject();
            weeks.addProperty(getCurrentWeekId(), 0);
            playerEntry.add("weeks", weeks);
            playerEntry.addProperty("PVD", false);
            playtimeData.add(playerName, playerEntry);
        }

        JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
        playerEntry.addProperty("lastJoin", now);

        // Сохраняем данные (короткая запись, можно оптимизировать, если многократно вызывается)
        savePlaytimeData();
    }

    // getters / setters для конфигурации
    public boolean isAfkCheckEnabled() { return afkCheckEnabled; }
    public void setAfkCheckEnabled(boolean v) { afkCheckEnabled = v; saveConfig(); }

    public int getAfkTimeThreshold() { return afkTimeThreshold; }
    public void setAfkTimeThreshold(int minutes) { afkTimeThreshold = minutes; saveConfig(); }

    public int getRequiredMinutes() { return requiredMinutes; }
    public void setRequiredMinutes(int minutes) { requiredMinutes = minutes; saveConfig(); }

    // forcedAfk access (возвращаем копию)
    public Set<java.util.UUID> getForcedAfk() { return new HashSet<>(forcedAfk); }
    public void addForcedAfk(java.util.UUID id) { forcedAfk.add(id); }
    public void removeForcedAfk(java.util.UUID id) { forcedAfk.remove(id); }
    public void clearForcedAfk() { forcedAfk.clear(); }

    // Возвращаем копию JSON playtimeData (чтобы API мог читать безопасно)
    public com.google.gson.JsonObject getPlaytimeDataCopy() {
        // лёгкая глубокая копия через сериализацию (корректно и просто)
        return com.google.gson.JsonParser.parseString(gson.toJson(playtimeData)).getAsJsonObject();
    }
}
