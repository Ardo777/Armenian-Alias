package com.example.armenianalias.game;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Owns every live room, the sockets watching it, and the turn timers.
 * Every mutation runs inside {@link #inRoom} so a room is only ever touched by one thread.
 */
@Service
public class RoomService {

    /** Ambiguous characters (0/O, 1/I) are left out so codes are easy to read aloud. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long EMPTY_ROOM_TTL_MS = TimeUnit.HOURS.toMillis(6);

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "alias-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final JsonMapper mapper = new JsonMapper();
    private final WordBank wordBank;

    public RoomService(WordBank wordBank) {
        this.wordBank = wordBank;
        scheduler.scheduleAtFixedRate(this::sweepIdleRooms, 30, 30, TimeUnit.MINUTES);
    }

    public WordBank wordBank() {
        return wordBank;
    }

    // ------------------------------------------------------------------ rooms

    public Room createRoom() {
        String code = newCode();
        Room room = new Room(code);
        rooms.put(code, room);
        roomSessions.put(code, ConcurrentHashMap.newKeySet());
        return room;
    }

    public Room room(String code) {
        return code == null ? null : rooms.get(code.toUpperCase());
    }

    private String newCode() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String code;
        do {
            StringBuilder builder = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private void sweepIdleRooms() {
        long cutoff = System.currentTimeMillis() - EMPTY_ROOM_TTL_MS;
        rooms.entrySet().removeIf(entry -> {
            boolean stale = entry.getValue().getLastActivity() < cutoff;
            if (stale) {
                roomSessions.remove(entry.getKey());
            }
            return stale;
        });
    }

    /** Runs the action with the room locked, then pushes fresh state to everyone in it. */
    public void inRoom(String code, java.util.function.Consumer<Room> action) {
        Room room = room(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            action.accept(room);
        }
        broadcast(room);
    }

    // --------------------------------------------------------------- sessions

    public void attach(WebSocketSession session, Room room) {
        roomSessions.computeIfAbsent(room.getCode(), key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void detach(WebSocketSession session, String code) {
        Set<WebSocketSession> sessions = roomSessions.get(code);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    public void broadcast(Room room) {
        Set<WebSocketSession> sessions = roomSessions.get(room.getCode());
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            String playerId = (String) session.getAttributes().get("playerId");
            Map<String, Object> message;
            synchronized (room) {
                message = new LinkedHashMap<>(Map.of("type", "state"));
                message.put("state", room.stateFor(playerId));
            }
            send(session, message);
        }
    }

    public void send(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignored) {
            // The socket went away mid-send; the close handler will clean it up.
        }
    }

    public void sendError(WebSocketSession session, String message) {
        send(session, Map.of("type", "error", "message", message));
    }

    // ----------------------------------------------------------------- timers

    /**
     * Ends the turn when the clock runs out, unless the room has moved on in the meantime.
     */
    public void scheduleTurnEnd(Room room) {
        long token = room.getTurnToken();
        long delay = Math.max(0, room.getTurnEndsAt() - System.currentTimeMillis());
        scheduler.schedule(() -> {
            boolean changed = false;
            synchronized (room) {
                if (room.getTurnToken() == token && Room.PLAYING.equals(room.getPhase())) {
                    room.endTurn();
                    changed = true;
                }
            }
            if (changed) {
                broadcast(room);
            }
        }, delay + 150, TimeUnit.MILLISECONDS);
    }

    /** Passes the room to someone still online if the admin hasn't come back within the grace period. */
    public void scheduleAdminHandover(Room room, String adminId) {
        scheduler.schedule(() -> {
            boolean changed = false;
            synchronized (room) {
                Player admin = room.getPlayers().get(adminId);
                if (room.isAdmin(adminId) && (admin == null || !admin.isConnected())) {
                    Player heir = room.getPlayers().values().stream()
                            .filter(Player::isConnected)
                            .findFirst()
                            .orElse(null);
                    if (heir != null) {
                        room.promoteAdmin(heir.getId());
                        changed = true;
                    }
                }
            }
            if (changed) {
                broadcast(room);
            }
        }, 45, TimeUnit.SECONDS);
    }
}
