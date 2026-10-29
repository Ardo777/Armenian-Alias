package com.example.armenianalias.game;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One game room. All mutations happen under {@link RoomService}'s per-room lock.
 */
public class Room {

    public static final String LOBBY = "LOBBY";
    public static final String SETUP = "SETUP";
    public static final String READY = "READY";
    public static final String PLAYING = "PLAYING";
    public static final String TURN_SUMMARY = "TURN_SUMMARY";
    public static final String FINISHED = "FINISHED";

    private static final String[] EMOJIS = {
            "🦁", "🐯", "🐼", "🦊", "🐻", "🐺", "🦅", "🦉", "🐉", "🦄",
            "🐙", "🦈", "🐬", "🦋", "🐝", "🦂", "🌋", "⚡", "🔥", "💎",
            "🚀", "⚓", "🏔️", "🌪️", "🍀", "🌹", "🍇", "🍎", "🥁", "🎺",
            "⚔️", "🛡️", "👑", "🎯", "🎲", "🧿", "☄️", "🌟", "🦚", "🐘"
    };

    private static final String[][] GRADIENTS = {
            {"#ff6a3d", "#f7b733"},
            {"#8e2de2", "#4a00e0"},
            {"#00b4db", "#0083b0"},
            {"#f953c6", "#b91d73"},
            {"#11998e", "#38ef7d"},
            {"#fc466b", "#3f5efb"},
            {"#e65c00", "#f9d423"},
            {"#1f4037", "#99f2c8"},
            {"#dd2476", "#ff512f"},
            {"#4776e6", "#8e54e9"},
            {"#f12711", "#f5af19"},
            {"#005c97", "#363795"}
    };

    private final String code;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<Team> teams = new ArrayList<>();
    private final GameSettings settings = new GameSettings();

    private String adminId;
    private String phase = LOBBY;
    private int roundNumber = 1;
    private int activeTeamIndex;
    private String explainerId;

    private Deque<WordCard> deck;
    private WordCard currentCard;
    private long turnEndsAt;
    private int skipsLeft;
    private final List<TurnEntry> turnEntries = new ArrayList<>();
    private int turnScoreDelta;
    private String lastError;

    private long lastActivity = System.currentTimeMillis();
    /** Bumped on every mutation so scheduled timers can tell whether they are still current. */
    private long turnToken;

    public Room(String code) {
        this.code = code;
    }

    public String getCode() { return code; }
    public String getPhase() { return phase; }
    public String getAdminId() { return adminId; }
    public GameSettings getSettings() { return settings; }
    public Map<String, Player> getPlayers() { return players; }
    public List<Team> getTeams() { return teams; }
    public long getTurnEndsAt() { return turnEndsAt; }
    public long getTurnToken() { return turnToken; }
    public long getLastActivity() { return lastActivity; }
    public void touch() { lastActivity = System.currentTimeMillis(); }

    // ---------------------------------------------------------------- players

    public Player addPlayer(String name) {
        Player player = new Player(UUID.randomUUID().toString(), uniqueName(name));
        players.put(player.getId(), player);
        if (adminId == null) {
            adminId = player.getId();
        }
        touch();
        return player;
    }

    private String uniqueName(String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.isEmpty()) {
            base = "Խաղացող";
        }
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        String candidate = base;
        int suffix = 2;
        while (nameTaken(candidate)) {
            candidate = base + " " + suffix++;
        }
        return candidate;
    }

    private boolean nameTaken(String name) {
        return players.values().stream().anyMatch(p -> p.getName().equalsIgnoreCase(name));
    }

    public void removePlayer(String playerId) {
        Player removed = players.remove(playerId);
        if (removed == null) {
            return;
        }
        teams.forEach(team -> team.getPlayerIds().remove(playerId));
        if (playerId.equals(adminId)) {
            adminId = players.values().stream()
                    .filter(Player::isConnected)
                    .map(Player::getId)
                    .findFirst()
                    .orElse(players.keySet().stream().findFirst().orElse(null));
        }
        touch();
    }

    public void promoteAdmin(String playerId) {
        if (players.containsKey(playerId)) {
            adminId = playerId;
            touch();
        }
    }

    public boolean isAdmin(String playerId) {
        return playerId != null && playerId.equals(adminId);
    }

    public boolean isEmpty() {
        return players.values().stream().noneMatch(Player::isConnected);
    }

    // ------------------------------------------------------------------ teams

    public Team addTeam() {
        Team team = new Team(UUID.randomUUID().toString(), "Թիմ " + (teams.size() + 1));
        rollLogo(team);
        teams.add(team);
        touch();
        return team;
    }

    public void rollLogo(Team team) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        team.setEmoji(EMOJIS[random.nextInt(EMOJIS.length)]);
        String[] gradient = GRADIENTS[random.nextInt(GRADIENTS.length)];
        team.setColorFrom(gradient[0]);
        team.setColorTo(gradient[1]);
        touch();
    }

    public Team team(String teamId) {
        return teams.stream().filter(t -> t.getId().equals(teamId)).findFirst().orElse(null);
    }

    public void removeTeam(String teamId) {
        Team team = team(teamId);
        if (team == null) {
            return;
        }
        team.getPlayerIds().forEach(id -> {
            Player player = players.get(id);
            if (player != null) {
                player.setTeamId(null);
            }
        });
        teams.remove(team);
        touch();
    }

    public void assignPlayer(String playerId, String teamId) {
        Player player = players.get(playerId);
        if (player == null) {
            return;
        }
        teams.forEach(team -> team.getPlayerIds().remove(playerId));
        player.setTeamId(null);
        Team target = team(teamId);
        if (target != null) {
            target.getPlayerIds().add(playerId);
            player.setTeamId(teamId);
        }
        touch();
    }

    /** Shuffles every player evenly across the current teams (creating two if there are none). */
    public void randomizeTeams() {
        if (teams.size() < 2) {
            while (teams.size() < 2) {
                addTeam();
            }
        }
        List<String> ids = new ArrayList<>(players.keySet());
        java.util.Collections.shuffle(ids);
        teams.forEach(team -> team.getPlayerIds().clear());
        players.values().forEach(player -> player.setTeamId(null));
        for (int i = 0; i < ids.size(); i++) {
            Team team = teams.get(i % teams.size());
            team.getPlayerIds().add(ids.get(i));
            players.get(ids.get(i)).setTeamId(team.getId());
        }
        touch();
    }

    /** @return a human-readable reason the game cannot start, or null when it can. */
    public String validateStart() {
        if (teams.size() < 2) {
            return "Պետք է առնվազն 2 թիմ";
        }
        for (Team team : teams) {
            if (team.getPlayerIds().size() < 2) {
                return "«" + team.getName() + "» թիմում պետք է լինի առնվազն 2 խաղացող";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- game

    public void startGame(WordBank bank) {
        teams.forEach(team -> {
            team.setScore(0);
            team.setExplainerIndex(0);
        });
        deck = bank.newDeck(settings.getLevels());
        roundNumber = 1;
        activeTeamIndex = 0;
        turnEntries.clear();
        turnScoreDelta = 0;
        currentCard = null;
        phase = READY;
        pickExplainer();
        turnToken++;
        touch();
    }

    public Team activeTeam() {
        if (teams.isEmpty()) {
            return null;
        }
        return teams.get(Math.floorMod(activeTeamIndex, teams.size()));
    }

    private void pickExplainer() {
        Team team = activeTeam();
        if (team == null || team.getPlayerIds().isEmpty()) {
            explainerId = null;
            return;
        }
        List<String> ids = team.getPlayerIds();
        explainerId = ids.get(Math.floorMod(team.getExplainerIndex(), ids.size()));
    }

    public String getExplainerId() { return explainerId; }

    public void startTurn(WordBank bank) {
        if (!READY.equals(phase)) {
            return;
        }
        skipsLeft = settings.getSkipsPerTurn();
        turnEntries.clear();
        turnScoreDelta = 0;
        phase = PLAYING;
        turnEndsAt = System.currentTimeMillis() + settings.getRoundSeconds() * 1000L;
        drawCard(bank);
        turnToken++;
        touch();
    }

    private void drawCard(WordBank bank) {
        if (deck == null || deck.isEmpty()) {
            deck = bank.newDeck(settings.getLevels());
        }
        currentCard = deck.poll();
    }

    /** Records the explainer's tap and deals the next word. */
    public void answer(String result, WordBank bank) {
        if (!PLAYING.equals(phase) || currentCard == null) {
            return;
        }
        if (TurnEntry.SKIP.equals(result)) {
            if (skipsLeft <= 0) {
                return;
            }
            skipsLeft--;
        }
        turnEntries.add(new TurnEntry(currentCard.word(), result));
        drawCard(bank);
        touch();
    }

    public void endTurn() {
        if (!PLAYING.equals(phase)) {
            return;
        }
        phase = TURN_SUMMARY;
        currentCard = null;
        turnEndsAt = 0;
        applyTurnScore();
        turnToken++;
        touch();
    }

    /** Points earned so far this turn — shown live to the explainer. */
    public int currentTurnPoints() {
        return turnEntries.stream().mapToInt(entry -> entry.points(settings)).sum();
    }

    /** Re-applies the turn's score; safe to call repeatedly while the summary is open. */
    private void applyTurnScore() {
        Team team = activeTeam();
        if (team == null) {
            return;
        }
        int total = currentTurnPoints();
        team.addScore(total - turnScoreDelta);
        turnScoreDelta = total;
    }

    /** Lets the explainer fix a mis-tap in the summary screen. */
    public void correctEntry(int index, String result) {
        if (!TURN_SUMMARY.equals(phase) || index < 0 || index >= turnEntries.size()) {
            return;
        }
        turnEntries.get(index).setResult(result);
        applyTurnScore();
        touch();
    }

    /** Moves to the next team, closing the round and the game when due. */
    public void nextTurn() {
        if (!TURN_SUMMARY.equals(phase) || teams.isEmpty()) {
            return;
        }
        Team team = activeTeam();
        if (team != null) {
            team.setExplainerIndex(team.getExplainerIndex() + 1);
        }
        turnEntries.clear();
        turnScoreDelta = 0;
        activeTeamIndex++;

        boolean roundComplete = activeTeamIndex % teams.size() == 0;
        if (roundComplete) {
            if ("ROUNDS".equals(settings.getWinMode())) {
                if (roundNumber >= settings.getTotalRounds()) {
                    phase = FINISHED;
                    turnToken++;
                    touch();
                    return;
                }
            } else if (teams.stream().anyMatch(t -> t.getScore() >= settings.getTargetScore())) {
                phase = FINISHED;
                turnToken++;
                touch();
                return;
            }
            roundNumber++;
        }
        phase = READY;
        pickExplainer();
        turnToken++;
        touch();
    }

    public void backToLobby() {
        phase = LOBBY;
        currentCard = null;
        turnEndsAt = 0;
        turnEntries.clear();
        turnScoreDelta = 0;
        teams.forEach(team -> {
            team.setScore(0);
            team.setExplainerIndex(0);
        });
        roundNumber = 1;
        activeTeamIndex = 0;
        explainerId = null;
        turnToken++;
        touch();
    }

    public void setPhase(String phase) {
        this.phase = phase;
        turnToken++;
        touch();
    }

    public void setLastError(String lastError) { this.lastError = lastError; }

    // ------------------------------------------------------------- state view

    /**
     * Builds the state as seen by one player — the current word is only ever sent to the explainer.
     */
    public Map<String, Object> stateFor(String viewerId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("code", code);
        state.put("phase", phase);
        state.put("adminId", adminId);
        state.put("youId", viewerId);
        state.put("isAdmin", isAdmin(viewerId));
        state.put("roundNumber", roundNumber);
        state.put("explainerId", explainerId);
        state.put("isExplainer", explainerId != null && explainerId.equals(viewerId));
        state.put("skipsLeft", skipsLeft);
        state.put("turnEndsAt", turnEndsAt);
        state.put("serverNow", System.currentTimeMillis());
        state.put("turnScore", currentTurnPoints());
        state.put("error", lastError);

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Player player : players.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", player.getId());
            entry.put("name", player.getName());
            entry.put("teamId", player.getTeamId());
            entry.put("connected", player.isConnected());
            entry.put("isAdmin", isAdmin(player.getId()));
            playerList.add(entry);
        }
        state.put("players", playerList);

        List<Map<String, Object>> teamList = new ArrayList<>();
        Team active = activeTeam();
        for (Team team : teams) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", team.getId());
            entry.put("name", team.getName());
            entry.put("emoji", team.getEmoji());
            entry.put("colorFrom", team.getColorFrom());
            entry.put("colorTo", team.getColorTo());
            entry.put("score", team.getScore());
            entry.put("playerIds", new ArrayList<>(team.getPlayerIds()));
            entry.put("isActive", active != null && active.getId().equals(team.getId()));
            teamList.add(entry);
        }
        state.put("teams", teamList);

        Map<String, Object> settingsMap = new LinkedHashMap<>();
        settingsMap.put("roundSeconds", settings.getRoundSeconds());
        settingsMap.put("skipsPerTurn", settings.getSkipsPerTurn());
        settingsMap.put("penaltyForWrong", settings.isPenaltyForWrong());
        settingsMap.put("winMode", settings.getWinMode());
        settingsMap.put("targetScore", settings.getTargetScore());
        settingsMap.put("totalRounds", settings.getTotalRounds());
        settingsMap.put("levels", settings.getLevels());
        state.put("settings", settingsMap);

        boolean viewerExplains = explainerId != null && explainerId.equals(viewerId);
        if (PLAYING.equals(phase) && viewerExplains && currentCard != null) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("word", currentCard.word());
            card.put("level", currentCard.level());
            card.put("taboo", currentCard.taboo());
            state.put("card", card);
        } else {
            state.put("card", null);
        }

        if (TURN_SUMMARY.equals(phase) || PLAYING.equals(phase)) {
            List<Map<String, Object>> entries = new ArrayList<>();
            boolean reveal = TURN_SUMMARY.equals(phase);
            for (int i = 0; i < turnEntries.size(); i++) {
                TurnEntry entry = turnEntries.get(i);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("index", i);
                map.put("word", reveal ? entry.getWord() : null);
                map.put("result", entry.getResult());
                entries.add(map);
            }
            state.put("entries", entries);
        } else {
            state.put("entries", List.of());
        }

        return state;
    }
}
