package org.example.pvdtime.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.example.pvdtime.PvdTime;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PvdTimeApi — статический фасад для доступа к данным PvdTime.
 *
 * Требует, чтобы PvdTime вызвал PvdTimeApi.init(this) на старте.
 */
public final class PvdTimeApi {
    private static volatile PvdTime instance = null;

    private PvdTimeApi() {}

    /** Инициализация: вызвать из PvdTime.onInitialize() */
    public static void init(PvdTime modInstance) {
        instance = Objects.requireNonNull(modInstance, "PvdTime instance");
    }

    private static void requireInit() {
        if (instance == null) throw new IllegalStateException("PvdTimeApi not initialized. Call PvdTimeApi.init(...) in onInitialize()");
    }

    // -----------------------
    // Прямые переменные (геттеры / сеттеры)
    // -----------------------
    public static boolean isAfkCheckEnabled() { requireInit(); return instance.isAfkCheckEnabled(); }
    public static void setAfkCheckEnabled(boolean v) { requireInit(); instance.setAfkCheckEnabled(v); }

    public static int getAfkTimeThreshold() { requireInit(); return instance.getAfkTimeThreshold(); }
    public static void setAfkTimeThreshold(int minutes) { requireInit(); instance.setAfkTimeThreshold(minutes); }

    public static int getRequiredMinutes() { requireInit(); return instance.getRequiredMinutes(); }
    public static void setRequiredMinutes(int minutes) { requireInit(); instance.setRequiredMinutes(minutes); }

    /** Возвращает копию множества принудительно AFK (UUID) */
    public static Set<UUID> getForcedAfk() { requireInit(); return new HashSet<>(instance.getForcedAfk()); }
    public static void addForcedAfk(UUID id) { requireInit(); instance.addForcedAfk(id); }
    public static void removeForcedAfk(UUID id) { requireInit(); instance.removeForcedAfk(id); }
    public static void clearForcedAfk() { requireInit(); instance.clearForcedAfk(); }

    // -----------------------
    // AFK: вызвать проверку для игрока (делегирует проверку вашего мода)
    // -----------------------
    public static boolean checkAFKStatus(ServerPlayerEntity player) {
        requireInit();
        return PvdTime.checkAFKStatus(player); // PvdTime.checkAFKStatus у вас уже public static
    }

    /** Прогонит проверку для всех игроков на сервере и вернёт список UUID тех, кто признаётся AFK */
    public static List<UUID> collectAfkPlayers(MinecraftServer server) {
        requireInit();
        if (server == null) return Collections.emptyList();
        return server.getPlayerManager().getPlayerList().stream()
                .filter(p -> PvdTime.checkAFKStatus(p))
                .map(p -> p.getUuid())
                .collect(Collectors.toList());
    }

    // -----------------------
    // Playtime: получить карту playerName -> minutes за неделю
    // -----------------------
    /** Текущая неделя (перегрузка без аргумента) */
    public static Map<String, Long> getWeeklyPlaytime() {
        requireInit();
        return getWeeklyPlaytime(instance.getCurrentWeekId());
    }

    /** Неделя в формате "YYYY-Www" (как в PvdTime) */
    public static Map<String, Long> getWeeklyPlaytime(String weekId) {
        requireInit();
        if (weekId == null || weekId.isEmpty()) return Collections.emptyMap();

        JsonObject data = instance.getPlaytimeDataCopy(); // возвращаем копию внутри PvdTime
        Map<String, Long> out = new HashMap<>();
        for (String playerName : data.keySet()) {
            JsonElement el = data.get(playerName);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonObject weeks = entry.has("weeks") && entry.get("weeks").isJsonObject() ? entry.getAsJsonObject("weeks") : null;
            if (weeks != null && weeks.has(weekId)) {
                try {
                    long minutes = weeks.get(weekId).getAsLong();
                    out.put(playerName, minutes);
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    /** Удобный доступ к времени конкретного игрока за неделю (null => текущая неделя) */
    public static long getPlayerWeekMinutes(String playerName, String weekId) {
        requireInit();
        if (playerName == null || playerName.isEmpty()) return 0;
        String wk = (weekId == null) ? instance.getCurrentWeekId() : weekId;
        Map<String, Long> m = getWeeklyPlaytime(wk);
        return m.getOrDefault(playerName, 0L);
    }
}
