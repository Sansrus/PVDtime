package org.example.pvdtime;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.*;
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
    private int requiredMinutes = 180; // Время, необходимое для получения статуса PVD
    private final Map<UUID, PlayerPosition> playerPositions = new HashMap<>();
    private int afkTimeThreshold = 5; // Время AFK по умолчанию (минуты)
    private boolean afkCheckEnabled = true; // Переменная для хранения статуса проверки AFK


    @Override
    public void onInitialize() {
        loadConfig();
        loadPlaytimeData();
        lastLogSaveTime = System.currentTimeMillis();
        lastWeeklyCheckTime = lastLogSaveTime;
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
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

    private String getCurrentWeekId() {
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
                                            playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                        }
                                    }

                                    playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                    for (Map.Entry<String, Long> entry : playersList) {
                                        long minutes = entry.getValue();
                                        long hours = minutes / 60;
                                        long remainingMinutes = minutes % 60;
                                        sb.append("\n§a- ").append(entry.getKey()).append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м");

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

                                            List<Map.Entry<String, Long>> playersList = new ArrayList<>();
                                            for (String playerName : playtimeData.keySet()) {
                                                JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                if (playerEntry.get("PVD").getAsBoolean()) {
                                                    JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                    long time = weeks.has(currentWeekId) ? weeks.get(currentWeekId).getAsLong() : 0;
                                                    playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                                }
                                            }

                                            playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                            for (Map.Entry<String, Long> entry : playersList) {
                                                long minutes = entry.getValue();
                                                long hours = minutes / 60;
                                                long remainingMinutes = minutes % 60;
                                                sb.append("\n§a- ").append(entry.getKey()).append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м");
                                            }

                                            if (playersList.isEmpty()) {
                                                sb.append("\n§cНет активных PVD игроков.");
                                            }

                                            context.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                                            return 1;
                                        })
                                )
                                .then(literal("last") // Обновленная команда
                                        .executes(context -> {
                                            String previousWeekId = getPreviousWeekId(); // Получаем ID прошлой недели
                                            File archiveFile = Paths.get("playtime_logs", "archive_" + previousWeekId + ".json").toFile();

                                            if (!archiveFile.exists()) {
                                                context.getSource().sendFeedback(() -> Text.literal("§cНет данных за прошлую неделю."), false);
                                                return 1;
                                            }

                                            try (FileReader reader = new FileReader(archiveFile)) {
                                                JsonObject archiveData = JsonParser.parseReader(reader).getAsJsonObject();
                                                StringBuilder sb = new StringBuilder("§6Время игроков за прошлую неделю:");

                                                List<Map.Entry<String, Long>> playersList = new ArrayList<>();
                                                for (String playerName : archiveData.keySet()) {
                                                    JsonObject playerEntry = archiveData.getAsJsonObject(playerName);
                                                    JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                    long time = weeks.has(previousWeekId) ? weeks.get(previousWeekId).getAsLong() : 0;
                                                    if (time > 0) {
                                                        playersList.add(new AbstractMap.SimpleEntry<>(playerName, time));
                                                    }
                                                }

                                                playersList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                                                for (Map.Entry<String, Long> entry : playersList) {
                                                    long minutes = entry.getValue();
                                                    long hours = minutes / 60;
                                                    long remainingMinutes = minutes % 60;
                                                    sb.append("\n§a- ").append(entry.getKey()).append(": §e").append(hours).append("ч ").append(remainingMinutes).append("м");
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
                                .requires(source -> source.hasPermissionLevel(4))
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
                                                        .executes(ctx -> {
                                                                    String playerName = StringArgumentType.getString(ctx, "player");
                                                                    String currentWeekId = getCurrentWeekId();

                                                                    if (playtimeData.has(playerName)) {
                                                                        JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                                        JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                                        weeks.addProperty(currentWeekId, 0);
                                                                        playerEntry.addProperty("PVD", false);

                                                                        // Обновляем тег игрока
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
                                                .then(literal("all")
                                                        .executes(ctx -> {
                                                            for(String playerName : playtimeData.keySet()) {
                                                                JsonObject playerEntry = playtimeData.getAsJsonObject(playerName);
                                                                JsonObject weeks = playerEntry.getAsJsonObject("weeks");
                                                                weeks.entrySet().forEach(entry -> entry.setValue(gson.toJsonTree(0)));
                                                                playerEntry.addProperty("PVD", false);
                                                            }

                                                            // Удаляем теги у всех игроков
                                                            MinecraftServer server = ctx.getSource().getServer();
                                                            for(ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                                                                player.removeCommandTag("PVD");
                                                            }

                                                            savePlaytimeData();
                                                            ctx.getSource().sendFeedback(() ->
                                                                    Text.literal("§6Все счетчики обнулены"), false);
                                                            return 1;
                                                        })
                                                )
                                        )
                                        .then(literal("set")
                                                .then(argument("player", StringArgumentType.word())
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

    private boolean checkAFKStatus(ServerPlayerEntity player) {
        if (!afkCheckEnabled) return false;

        UUID    id      = player.getUuid();
        long    now     = System.currentTimeMillis();
        // округлённые координаты
        double  x       = Math.floor(player.getX());
        double  y       = Math.floor(player.getY());
        double  z       = Math.floor(player.getZ());

        // берём предыдущую запись
        PlayerPosition last = playerPositions.get(id);
        if (last == null) {
            // первый раз — запомним позицию, будем считать активным
            playerPositions.put(id, new PlayerPosition(x, y, z, now));
            return false;
        }

        // если игрок **двинулся** (координаты изменины) — обновим время последнего движения
        if (last.x != x || last.y != y || last.z != z) {
            last.x = x;
            last.y = y;
            last.z = z;
            last.lastMoveTime = now;
            return false;
        }

        // если стоит на месте — проверяем, сколько прошло с последнего движения
        long elapsed = now - last.lastMoveTime;
        return elapsed >= TimeUnit.MINUTES.toMillis(afkTimeThreshold);
    }

    // новый класс для хранения без автогенерируемых таймстемпов:
    private static class PlayerPosition {
        double x, y, z;
        long   lastMoveTime;
        PlayerPosition(double x, double y, double z, long t) {
            this.x = x; this.y = y; this.z = z; this.lastMoveTime = t;
        }
    }

}
