package org.example.pvdtime;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

public class NickColorHandler {

    // --- Константы команд и цветов ---
    private static final String TEAM_OW           = "nick_overworld";
    private static final String TEAM_OW_AFK       = "nick_overworld_afk";
    private static final String TEAM_NETHER       = "nick_nether";
    private static final String TEAM_NETHER_AFK   = "nick_nether_afk";
    private static final String TEAM_END          = "nick_end";
    private static final String TEAM_END_AFK      = "nick_end_afk";
    private static final String TEAM_SLEEP        = "nick_sleepdim";
    private static final String TEAM_SLEEP_AFK    = "nick_sleepdim_afk";

    private static final Identifier DIM_OVERWORLD = World.OVERWORLD.getValue();
    private static final Identifier DIM_NETHER    = World.NETHER.getValue();
    private static final Identifier DIM_END       = World.END.getValue();
    private static final Identifier DIM_SLEEP     = Identifier.of("indefinite", "indefinite");

    // --- Для AFK‑проверки ---
    private static final Map<UUID, Boolean> afkStatus = new HashMap<>();

    // --- Для текущей команды игроков ---
    private static final Map<UUID, String> playerTeams = new HashMap<>();

    public static void register() {
        // — Тик‑обработчик: проверяем AFK и измерение каждый тик
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // 1) Обновляем AFK‑статус
                boolean nowAfk = PvdTime.checkAFKStatus(player);
                Boolean wasAfk = afkStatus.get(player.getUuid());
                if (wasAfk == null) wasAfk = false;

                if (nowAfk != wasAfk) {
                    // Статус изменился
                    if (nowAfk) {
                        player.sendMessage(
                                Text.literal("Ты начал бездельничать!").formatted(Formatting.GOLD)
                                        .append(Text.literal(" ЭТО ГРУСТНО").formatted(Formatting.RED)),
                                false
                        );
                    } else {
                        player.sendMessage(
                                Text.literal("Ты перестал бездельничать!").formatted(Formatting.GOLD)
                                        .append(Text.literal(" МОЛОДЕЦ").formatted(Formatting.GREEN)),
                                false
                        );
                    }
                    afkStatus.put(player.getUuid(), nowAfk);
                }

                // 2) Обновляем команду (и цвет ника) если AFK или измерение изменились
                try {
                    updatePlayerTeam(player, nowAfk);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // --- Обновляет команду и цвет ника под заданный AFK‑статус ---
    private static void updatePlayerTeam(ServerPlayerEntity player, boolean isAfk) {
        String newTeam = getTeamNameByDimensionAndAfk(player, isAfk);
        UUID uuid = player.getUuid();

        if (newTeam == null) {
            // неизвестное измерение → убираем из всех команд
            removeFromAllTeams(player, Objects.requireNonNull(player.getServer()).getScoreboard());
            playerTeams.remove(uuid);
            return;
        }

        String current = playerTeams.get(uuid);
        if (!newTeam.equals(current)) {
            assignPlayerToTeam(player, newTeam, isAfk);
            playerTeams.put(uuid, newTeam);
        }
    }

    // --- Определяет имя команды по измерению и AFK ---
    private static String getTeamNameByDimensionAndAfk(ServerPlayerEntity player, boolean isAfk) {
        Identifier dim = player.getWorld().getRegistryKey().getValue();
        if (dim.equals(DIM_OVERWORLD))   return isAfk ? TEAM_OW_AFK    : TEAM_OW;
        if (dim.equals(DIM_NETHER))      return isAfk ? TEAM_NETHER_AFK: TEAM_NETHER;
        if (dim.equals(DIM_END))         return isAfk ? TEAM_END_AFK   : TEAM_END;
        if (dim.equals(DIM_SLEEP))       return isAfk ? TEAM_SLEEP_AFK : TEAM_SLEEP;
        return null;
    }

    // --- Назначает игрока в команду и настраивает её цвет/суффикс ---
    private static void assignPlayerToTeam(ServerPlayerEntity player, String teamName, boolean isAfk) {
        Scoreboard board = Objects.requireNonNull(player.getServer()).getScoreboard();
        Team team = board.getTeam(teamName);
        if (team == null) {

            team = board.addTeam(teamName);
            Formatting color = switch (teamName) {
                case TEAM_OW, TEAM_OW_AFK         -> Formatting.DARK_GREEN;
                case TEAM_NETHER, TEAM_NETHER_AFK -> Formatting.RED;
                case TEAM_END, TEAM_END_AFK       -> Formatting.DARK_PURPLE;
                case TEAM_SLEEP, TEAM_SLEEP_AFK   -> Formatting.LIGHT_PURPLE;
                default                           -> Formatting.WHITE;
            };
            team.setColor(color);
            team.setPrefix(Text.empty());
            team.setSuffix(Text.empty());
        }
        removeFromAllTeams(player, board);
        board.addScoreHolderToTeam(player.getName().getString(), team);

        // Суффикс: ● только если AFK, и без цвета
        Text suffix = isAfk
                ? Text.literal(" ●").formatted(Formatting.GRAY)
                : Text.empty();
        team.setSuffix(suffix);
        team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
    }

    // --- Убирает игрока из всех наших команд ---
    private static void removeFromAllTeams(ServerPlayerEntity player, Scoreboard board) {
        String name = player.getName().getString();
        for (String tn : new String[]{
                TEAM_OW, TEAM_OW_AFK,
                TEAM_NETHER, TEAM_NETHER_AFK,
                TEAM_END, TEAM_END_AFK,
                TEAM_SLEEP, TEAM_SLEEP_AFK
        }) {
            Team t = board.getTeam(tn);
            if (t != null && board.getScoreHolderTeam(name) == t) {
                board.removeScoreHolderFromTeam(name, t);
            }
        }
    }
}
