package com.example.armenianalias.game;

public class Player {
    private final String id;
    private String name;
    private String teamId;
    private boolean connected = true;
    private long disconnectedAt;

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public long getDisconnectedAt() { return disconnectedAt; }
    public void setDisconnectedAt(long disconnectedAt) { this.disconnectedAt = disconnectedAt; }
}
