package com.example.armenianalias.ws;

import com.example.armenianalias.game.GameSettings;
import com.example.armenianalias.game.Player;
import com.example.armenianalias.game.Room;
import com.example.armenianalias.game.RoomService;
import com.example.armenianalias.game.Team;
import com.example.armenianalias.game.TurnEntry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Translates client messages into room mutations. One socket == one player. */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final RoomService rooms;
    private final JsonMapper mapper = new JsonMapper();

    public GameWebSocketHandler(RoomService rooms) {
        this.rooms = rooms;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            rooms.sendError(session, "Սխալ հարցում");
            return;
        }
        String type = text(node, "type");
        if (type == null) {
            return;
        }

        switch (type) {
            case "create" -> handleCreate(session, node);
            case "join" -> handleJoin(session, node);
            case "rejoin" -> handleRejoin(session, node);
            case "ping" -> rooms.send(session, Map.of("type", "pong"));
            default -> handleGameMessage(session, type, node);
        }
    }

    // ------------------------------------------------------------ connecting

    private void handleCreate(WebSocketSession session, JsonNode node) {
        Room room = rooms.createRoom();
        Player player;
        synchronized (room) {
            player = room.addPlayer(text(node, "name"));
        }
        bind(session, room, player);
    }

    private void handleJoin(WebSocketSession session, JsonNode node) {
        Room room = rooms.room(text(node, "code"));
        if (room == null) {
            rooms.sendError(session, "Այդ կոդով սենյակ չկա");
            return;
        }
        Player player;
        synchronized (room) {
            if (room.getPlayers().size() >= 40) {
                rooms.sendError(session, "Սենյակը լիքն է");
                return;
            }
            player = room.addPlayer(text(node, "name"));
        }
        bind(session, room, player);
    }

    /** Reconnect after a refresh or a dropped mobile connection. */
    private void handleRejoin(WebSocketSession session, JsonNode node) {
        Room room = rooms.room(text(node, "code"));
        String playerId = text(node, "playerId");
        if (room == null || playerId == null) {
            rooms.send(session, Map.of("type", "rejoinFailed"));
            return;
        }
        Player player;
        synchronized (room) {
            player = room.getPlayers().get(playerId);
        }
        if (player == null) {
            rooms.send(session, Map.of("type", "rejoinFailed"));
            return;
        }
        bind(session, room, player);
    }

    private void bind(WebSocketSession session, Room room, Player player) {
        String previousCode = (String) session.getAttributes().get("roomCode");
        if (previousCode != null) {
            rooms.detach(session, previousCode);
        }
        session.getAttributes().put("roomCode", room.getCode());
        session.getAttributes().put("playerId", player.getId());
        synchronized (room) {
            player.setConnected(true);
        }
        rooms.attach(session, room);
        rooms.send(session, Map.of(
                "type", "joined",
                "code", room.getCode(),
                "playerId", player.getId(),
                "name", player.getName()));
        rooms.broadcast(room);
    }

    // --------------------------------------------------------------- in-game

    private void handleGameMessage(WebSocketSession session, String type, JsonNode node) {
        String code = (String) session.getAttributes().get("roomCode");
        String me = (String) session.getAttributes().get("playerId");
        Room room = rooms.room(code);
        if (room == null || me == null) {
            rooms.sendError(session, "Դուք սենյակում չեք");
            return;
        }

        boolean[] startTimer = {false};
        String[] error = {null};

        rooms.inRoom(code, r -> {
            boolean admin = r.isAdmin(me);
            boolean explainer = me.equals(r.getExplainerId());

            switch (type) {
                case "setName" -> {
                    Player player = r.getPlayers().get(me);
                    String name = text(node, "name");
                    if (player != null && name != null && !name.isBlank()) {
                        player.setName(name.trim().length() > 20 ? name.trim().substring(0, 20) : name.trim());
                        r.touch();
                    }
                }
                case "toSetup" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    if (r.getTeams().isEmpty()) {
                        r.addTeam();
                        r.addTeam();
                    }
                    r.setPhase(Room.SETUP);
                }
                case "toLobby" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.setPhase(Room.LOBBY);
                }
                case "addTeam" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    if (r.getTeams().size() >= 8) { error[0] = "Առավելագույնը 8 թիմ"; return; }
                    r.addTeam();
                }
                case "removeTeam" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.removeTeam(text(node, "teamId"));
                }
                case "renameTeam" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    Team team = r.team(text(node, "teamId"));
                    String name = text(node, "name");
                    if (team != null && name != null && !name.isBlank()) {
                        team.setName(name.trim().length() > 24 ? name.trim().substring(0, 24) : name.trim());
                        r.touch();
                    }
                }
                case "rerollLogo" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    Team team = r.team(text(node, "teamId"));
                    if (team != null) {
                        r.rollLogo(team);
                    }
                }
                case "assign" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.assignPlayer(text(node, "playerId"), text(node, "teamId"));
                }
                case "randomize" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.randomizeTeams();
                }
                case "settings" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    applySettings(r.getSettings(), node.get("settings"));
                    r.touch();
                }
                case "kick" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    String target = text(node, "playerId");
                    if (target != null && !target.equals(me)) {
                        r.removePlayer(target);
                    }
                }
                case "start" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    String reason = r.validateStart();
                    if (reason != null) { error[0] = reason; return; }
                    r.startGame(rooms.wordBank());
                }
                case "startTurn" -> {
                    if (!explainer && !admin) { error[0] = "Սպասեք ձեր հերթին"; return; }
                    r.startTurn(rooms.wordBank());
                    startTimer[0] = true;
                }
                case "answer" -> {
                    if (!explainer) { error[0] = "Միայն բացատրողը կարող է"; return; }
                    String result = text(node, "result");
                    if (TurnEntry.CORRECT.equals(result)
                            || TurnEntry.SKIP.equals(result)
                            || TurnEntry.WRONG.equals(result)) {
                        r.answer(result, rooms.wordBank());
                    }
                }
                case "endTurn" -> {
                    if (!explainer && !admin) { error[0] = "Միայն բացատրողը կարող է"; return; }
                    r.endTurn();
                }
                case "correct" -> {
                    if (!explainer && !admin) { error[0] = "Միայն բացատրողը կարող է"; return; }
                    JsonNode index = node.get("index");
                    String result = text(node, "result");
                    if (index != null && index.isInt() && result != null) {
                        r.correctEntry(index.asInt(), result);
                    }
                }
                case "next" -> {
                    if (!explainer && !admin) { error[0] = "Սպասեք բացատրողին"; return; }
                    r.nextTurn();
                }
                case "restart" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.backToLobby();
                }
                case "playAgain" -> {
                    if (!admin) { error[0] = "Միայն ադմինը կարող է"; return; }
                    r.startGame(rooms.wordBank());
                }
                default -> { }
            }
        });

        if (error[0] != null) {
            rooms.sendError(session, error[0]);
            return;
        }
        if (startTimer[0]) {
            rooms.scheduleTurnEnd(room);
        }
    }

    private void applySettings(GameSettings settings, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        if (node.hasNonNull("roundSeconds")) settings.setRoundSeconds(node.get("roundSeconds").asInt());
        if (node.hasNonNull("skipsPerTurn")) settings.setSkipsPerTurn(node.get("skipsPerTurn").asInt());
        if (node.hasNonNull("penaltyForWrong")) settings.setPenaltyForWrong(node.get("penaltyForWrong").asBoolean());
        if (node.hasNonNull("winMode")) settings.setWinMode(node.get("winMode").asString());
        if (node.hasNonNull("targetScore")) settings.setTargetScore(node.get("targetScore").asInt());
        if (node.hasNonNull("totalRounds")) settings.setTotalRounds(node.get("totalRounds").asInt());
        if (node.hasNonNull("levels") && node.get("levels").isArray()) {
            List<Integer> levels = new ArrayList<>();
            node.get("levels").forEach(value -> levels.add(value.asInt()));
            settings.setLevels(levels);
        }
    }

    // ------------------------------------------------------------ disconnect

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String code = (String) session.getAttributes().get("roomCode");
        String playerId = (String) session.getAttributes().get("playerId");
        if (code == null) {
            return;
        }
        rooms.detach(session, code);
        Room room = rooms.room(code);
        if (room == null || playerId == null) {
            return;
        }
        rooms.inRoom(code, r -> {
            Player player = r.getPlayers().get(playerId);
            if (player != null) {
                player.setConnected(false);
                player.setDisconnectedAt(System.currentTimeMillis());
            }
        });
        // A refresh or a tunnel blip shouldn't cost the admin their crown — but a phone
        // that stays dark must not freeze the game either, so hand over after a grace period.
        if (room.isAdmin(playerId)) {
            rooms.scheduleAdminHandover(room, playerId);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
